//
// 原生崩溃捕获实现。见 crash.h 的设计约束。
//
// 只依赖 async-signal-safe 的 syscall 包装(open/write/close/getpid/clock_gettime),
// 不碰 malloc/stdio/pthread/dladdr/localtime, 保证在堆已损坏或信号处理器重入时仍能落盘。
//
// 关键设计: 内存环形缓冲("黑匣子")。
// 崩溃现场本身往往在 libart 里, 光有寄存器看不出"模块刚才在做什么"。所以每次
// hook 回调都往一个固定大小的环形缓冲写一条事件(不碰文件, 无 IO), 崩溃时把
// 缓冲里最近 N 条一起 dump 出来。这样即使崩溃发生在任意时刻, 也能看到前因。
//

#include "crash.h"

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <ucontext.h>
#include <unwind.h>
#include <android/log.h>

#define TS_TAG "TiebaTS"

// 单条记录上限: 信号处理器里不能做动态格式化, 用固定缓冲手工拼接
#define TS_BUF 4096
// 崩溃时最多记录的栈帧数
#define TS_MAX_FRAMES 48
// 路径上限
#define TS_PATH 480
// 进程名上限
#define TS_PROC 64
// 备用信号栈大小: 栈溢出(反混淆/DexKit 递归)时处理器必须有独立栈才能跑
#define TS_ALTSTACK (64 * 1024)
// 环形缓冲容量: 256 条足够覆盖"崩溃前最后几十次 hook 调用"
#define TS_EV_MAX 256
// 单条事件文本上限(含 NUL)
#define TS_EV_LEN 96
// 标签表容量: 每个 hook 回调注册一个 id, 避免高频路径上反复拷贝字符串
#define TS_TAG_MAX 256
// 心跳落盘间隔(秒): 崩溃时最后一条 alive 即死亡时刻
#define TS_HEARTBEAT_FLUSH_S 60
// 崩溃记录条数上限: ART 用隐式 SIGSEGV 实现 Java 空指针检查, 高频路径上
// 处理器会被正常代码反复进入; 每次 unwind 48 帧+落盘开销虽小但无意义,
// 且日志文件无界增长。超过上限后只链回旧处理器不再记录
// (tombstone 仍由系统照常生成, 打点 ts_crash_mark 不受此上限约束)。
// 取 256 而非更小: 计数含隐式空检查信号, 上限过低会让长会话后期的真崩溃
// 无处落盘。每次崩溃写两条记录(头+事件 dump ≤4KB、backtrace ≈1.3KB),
// 最坏约 5.4KB/次, 上限封顶约 1.4MB —— 文件体积有界, 导出 readTail 只取
// 64KB 尾部不受影响
#define TS_MAX_LOGGED 256
// 看门狗超时与轮询间隔: 5s 对正常处理器开销(ms 级)足够宽裕, 两阶段间隔
// 同为 5s, 总兜底上限 10s
#define TS_WATCHDOG_TIMEOUT_MS 5000
#define TS_WATCHDOG_POLL_MS 500

static char g_log_path[TS_PATH];
static char g_proc_name[TS_PROC];
// 原子(经 __atomic 编译器内建访问, 不用 <atomic>: ts_crash 编译于
// -nostdinc++ 且刻意零依赖): install 可能被多线程并发调用(Application hook
// 早期与 libhide 加载后两个时机), 裸读写的 check-then-act 会让两个线程各做
// 一份 sigaction, 第二次覆盖第一次保存的 g_old, 旧处理器链断裂
static volatile int g_installed = 0;
// 递归保护: 处理器内部再次触发同一信号时直接放行, 避免死循环
static volatile sig_atomic_t g_in_handler = 0;
// 时区偏移(秒), 由 Java 侧传入, 用于把 epoch 换算成本地时间
static int g_tz_offset = 0;
// 已落盘的崩溃记录条数( relaxed 序即可: 计数竞争最坏是少记一条 )
static volatile int g_logged = 0;

// 看门狗登记。处理器内做不到超时控制——_Unwind_Backtrace 在异常现场可能
// 永不返回(11-15 事件实证: 处理器楔死, 卡住的线程打转 2 小时后独占 CPU
// 拖出 ANR), 只能由 install 时创建的外部线程兜底。
// enter_ms 为 0 表示"当前无活跃处理器"; 置位顺序: in_handler 先置 1,
// enter_ms 后登记(看门狗以两者同时有效为准), 注销时先清 enter_ms 再清
// in_handler, 两个方向都不会让看门狗读到"in_handler=1 但 enter 已失效"。
static volatile uint64_t g_handler_enter_ms = 0;
static volatile int g_handler_tid = 0;
static volatile int g_handler_sig = 0;
// 看门狗阶段: 0=未介入, 1=已补投 SIGABRT。防止每个轮询周期重复投递
static volatile int g_watchdog_stage = 0;

// 被我们接管的信号及其原处理器
// static: 不随 -fvisibility=hidden 的缺席(纯 Debug 构建)意外导出符号
static struct sigaction g_old[8];
static const int g_signals[8] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE, SIGTRAP, 0, 0};
static const int g_signal_count = 6;

// 备用信号栈。必须是静态的: 信号处理器运行时不能依赖当前线程的栈可用。
static char g_altstack[TS_ALTSTACK];

// ---------------------------------------------------------------- 环形缓冲

// 每条事件: 单调序号 + 时间戳 + 文本。seq 为 0 表示该槽未使用。
// 写者用 __atomic_fetch_add 抢槽位, 再写文本, 最后 release 写 seq;
// 读者(信号处理器)只在 seq 非 0 时读, 容忍读到半写状态(可接受, 崩溃现场优先)。
//
// seq 用单调计数器而非时间戳: 同一毫秒内会有多条事件(一次 hook 调用就写两条),
// 用时间戳做序号会冲突, 导致 dump 时按 seq 查找只能取到其中一条。
struct TsEvent {
    uint64_t seq;   // 单调递增序号, 0 = 空
    uint64_t ms;    // epoch 毫秒
    char text[TS_EV_LEN];
};

static TsEvent g_events[TS_EV_MAX];
static uint32_t g_ev_head = 0;
static uint64_t g_ev_seq = 0;

// 标签表: id -> 标签字符串。注册后只读, 高频路径只传 id。
static char g_tags[TS_TAG_MAX][TS_EV_LEN];
static volatile sig_atomic_t g_tag_count = 0;
// 槽位就绪标志: 注册线程写满文本后 release 置 1, 读者 acquire 读 1 才可用
// (防"计数已发布但文本半写"的窗口, 见 ts_crash_register_tag)
static volatile sig_atomic_t g_tag_ready[TS_TAG_MAX];

// ---------------------------------------------------------------- 无锁格式化

// 无符号整数转十进制, 返回写入长度
static int ts_utoa(uint64_t value, char *out) {
    char tmp[24];
    int n = 0;
    if (value == 0) {
        out[0] = '0';
        return 1;
    }
    while (value > 0 && n < (int) sizeof(tmp)) {
        tmp[n++] = (char) ('0' + (value % 10));
        value /= 10;
    }
    for (int i = 0; i < n; ++i) out[i] = tmp[n - 1 - i];
    return n;
}

// 十六进制(带 0x 前缀)
static int ts_hex(uint64_t value, char *out) {
    static const char digits[] = "0123456789abcdef";
    char tmp[20];
    int n = 0;
    if (value == 0) {
        out[0] = '0';
        out[1] = 'x';
        out[2] = '0';
        return 3;
    }
    while (value > 0 && n < (int) sizeof(tmp)) {
        tmp[n++] = digits[value & 0xf];
        value >>= 4;
    }
    out[0] = '0';
    out[1] = 'x';
    for (int i = 0; i < n; ++i) out[2 + i] = tmp[n - 1 - i];
    return 2 + n;
}

// 追加到缓冲, 返回新的偏移; 空间不足时停止追加但保持 NUL 结尾
static int ts_append(char *buf, int off, const char *src, int srcLen) {
    for (int i = 0; i < srcLen && off < TS_BUF - 2; ++i) {
        buf[off++] = src[i];
    }
    buf[off] = '\0';
    return off;
}

// 追加 C 字符串。长度在调用方算好, 直接从这里的内存读, 不做二次拷贝
static int ts_append_str(char *buf, int off, const char *s) {
    if (s == nullptr) return off;
    int n = 0;
    while (s[n] != '\0') ++n;
    return ts_append(buf, off, s, n);
}

static int ts_append_dec(char *buf, int off, uint64_t v) {
    char tmp[24];
    return ts_append(buf, off, tmp, ts_utoa(v, tmp));
}

static int ts_append_hex(char *buf, int off, uint64_t v) {
    char tmp[24];
    return ts_append(buf, off, tmp, ts_hex(v, tmp));
}

// ---------------------------------------------------------------- 时间换算

// Howard Hinnant 的 civil_from_days: 纯整数运算, 不查表不加锁,
// 可在信号处理器内使用(localtime 会加锁, 绝对不能用)。
static void ts_civil_from_days(int64_t z, int *year, unsigned *month, unsigned *day) {
    z += 719468;
    int64_t era = (z >= 0 ? z : z - 146096) / 146097;
    unsigned doe = (unsigned) (z - era * 146097);
    unsigned yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    int64_t y = (int64_t) yoe + era * 400;
    unsigned doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    unsigned mp = (5 * doy + 2) / 153;
    unsigned d = doy - (153 * mp + 2) / 5 + 1;
    unsigned m = mp < 10 ? mp + 3 : mp - 9;
    *year = (int) (y + (m <= 2));
    *month = m;
    *day = d;
}

// 把 epoch 毫秒格式化成 "MM-dd HH:mm:ss.SSS"(本地时间), 返回写入长度(恒 18)。
// 时区偏移由 Java 侧在 install 时传入, 这里只做加减。
// 定宽格式直接按固定下标写入: 此前"累进 off + ts_append_pad 后写分隔符"的写法
// 在 NDK r27 clang -O2(aarch64) 下被错误编译——' ' 之后的分隔符被存到
// off+字段起始偏移 的错位地址, 日字段后的 NUL 未被覆盖, 时间戳整体截断成
// "MM-dd"(反汇编实证: 分隔符 store 的偏移寄存器多加了上一个字段的起始值;
// 5.1.8 模拟器实测全部打点行均为 "[11-13]" 截断)。固定下标写法纯算术无累进,
// 从代码生成路径上绕开该错误。
static int ts_format_time(uint64_t epochMs, char *out) {
    int64_t local = (int64_t) epochMs + (int64_t) g_tz_offset * 1000;
    int64_t days = local / 86400000LL;
    int64_t msOfDay = local % 86400000LL;
    if (msOfDay < 0) {
        msOfDay += 86400000LL;
        days -= 1;
    }
    int year;
    unsigned month, day;
    ts_civil_from_days(days, &year, &month, &day);
    unsigned secOfDay = (unsigned) (msOfDay / 1000);
    unsigned h = secOfDay / 3600, mi = (secOfDay / 60) % 60, s = secOfDay % 60;
    unsigned ms = (unsigned) (msOfDay % 1000);
    out[0]  = (char) ('0' + month / 10);
    out[1]  = (char) ('0' + month % 10);
    out[2]  = '-';
    out[3]  = (char) ('0' + day / 10);
    out[4]  = (char) ('0' + day % 10);
    out[5]  = ' ';
    out[6]  = (char) ('0' + h / 10);
    out[7]  = (char) ('0' + h % 10);
    out[8]  = ':';
    out[9]  = (char) ('0' + mi / 10);
    out[10] = (char) ('0' + mi % 10);
    out[11] = ':';
    out[12] = (char) ('0' + s / 10);
    out[13] = (char) ('0' + s % 10);
    out[14] = '.';
    out[15] = (char) ('0' + ms / 100);
    out[16] = (char) ('0' + (ms / 10) % 10);
    out[17] = (char) ('0' + ms % 10);
    out[18] = '\0';
    return 18;
}

// 当前 epoch 毫秒。clock_gettime 走 vDSO, 无系统调用开销, 可在信号处理器内用。
static uint64_t ts_now_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_REALTIME, &ts) != 0) return 0;
    return (uint64_t) ts.tv_sec * 1000ULL + (uint64_t) (ts.tv_nsec / 1000000);
}

// ---------------------------------------------------------------- 落盘

// 直接 write(2): 信号处理器里唯一可靠的输出方式
static void ts_write_all(int fd, const char *buf, int len) {
    int written = 0;
    while (written < len) {
        ssize_t n = write(fd, buf + written, (size_t) (len - written));
        if (n <= 0) {
            if (errno == EINTR) continue;
            break;
        }
        written += (int) n;
    }
}

// 以追加方式写一条记录。路径未设置时退化为只写 logcat。
static void ts_emit(const char *buf, int len) {
    if (g_log_path[0] != '\0') {
        int fd = open(g_log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
        if (fd >= 0) {
            ts_write_all(fd, buf, len);
            close(fd);
            return;
        }
    }
    if (g_in_handler != 0) {
        // 信号处理器路径禁止 __android_log_write: 它非 async-signal-safe(内部用
        // socket/互斥量/stdio 格式化), 崩溃恰逢本线程持有 logd 客户端锁时处理器
        // 死锁, tombstone 一起丢。降级写 stderr(write 是安全 syscall)
        ts_write_all(STDERR_FILENO, buf, len);
        return;
    }
    // 落盘失败仍要留 logcat 线索(仅正常上下文可达)
    __android_log_write(ANDROID_LOG_ERROR, TS_TAG, buf);
}

// ---------------------------------------------------------------- 事件写入

// 向环形缓冲追加一条事件。不碰文件, 可在任意线程/高频路径调用。
static void ts_push_event(const char *tag, const char *detail) {
    uint64_t now = ts_now_ms();
    uint64_t seq = __atomic_add_fetch(&g_ev_seq, 1ULL, __ATOMIC_RELAXED);
    uint32_t idx = __atomic_fetch_add(&g_ev_head, 1u, __ATOMIC_RELAXED) % TS_EV_MAX;
    TsEvent *ev = &g_events[idx];
    // 先清 seq, 让读者知道该槽正在被改写
    __atomic_store_n(&ev->seq, 0ULL, __ATOMIC_RELAXED);
    int off = 0;
    if (tag != nullptr) off = ts_append(ev->text, off, tag, (int) strnlen(tag, TS_EV_LEN - 1));
    if (detail != nullptr && off < TS_EV_LEN - 4) {
        ev->text[off++] = ':';
        ev->text[off++] = ' ';
        int room = TS_EV_LEN - 1 - off;
        int n = (int) strnlen(detail, (size_t) room);
        for (int i = 0; i < n; ++i) ev->text[off++] = detail[i];
    }
    ev->text[off] = '\0';
    ev->ms = now;
    // release: 保证文本写完后再公开 seq
    __atomic_store_n(&ev->seq, seq, __ATOMIC_RELEASE);
}

// 按 id 写事件(高频路径): 标签已注册, 无需拷贝字符串。
// id < g_tag_count 只是"槽位已占", 文本可用性以 g_tag_ready 为准
// (fetch_add 与文本写完之间有窗口, 见 ts_crash_register_tag)
static void ts_push_event_id(int id, const char *detail) {
    if (id < 0 || id >= TS_TAG_MAX) return;
    if (id >= g_tag_count) return;
    if (__atomic_load_n(&g_tag_ready[id], __ATOMIC_ACQUIRE) != 1) return;
    ts_push_event(g_tags[id], detail);
}

// ---------------------------------------------------------------- 信号处理器

static const char *ts_signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGILL: return "SIGILL";
        case SIGFPE: return "SIGFPE";
        case SIGTRAP: return "SIGTRAP";
        default: return "SIG?";
    }
}

// 从 ucontext 取出崩溃地址与关键寄存器(arm64)
static void ts_dump_context(int sig, siginfo_t *info, void *ucontext, char *buf, int *off) {
    char timeBuf[32];
    ts_format_time(ts_now_ms(), timeBuf);
    *off = ts_append_str(buf, *off, "\n===== 原生崩溃 =====\n");
    *off = ts_append_str(buf, *off, "time=");
    *off = ts_append_str(buf, *off, timeBuf);
    *off = ts_append_str(buf, *off, " signal=");
    *off = ts_append_str(buf, *off, ts_signal_name(sig));
    *off = ts_append_str(buf, *off, "(");
    *off = ts_append_dec(buf, *off, (uint64_t) sig);
    *off = ts_append_str(buf, *off, ") code=");
    *off = ts_append_dec(buf, *off, info != nullptr ? (uint64_t) info->si_code : 0);
    *off = ts_append_str(buf, *off, " pid=");
    *off = ts_append_dec(buf, *off, (uint64_t) getpid());
    *off = ts_append_str(buf, *off, " tid=");
    *off = ts_append_dec(buf, *off, (uint64_t) syscall(__NR_gettid));
    *off = ts_append_str(buf, *off, " proc=");
    *off = ts_append_str(buf, *off, g_proc_name[0] != '\0' ? g_proc_name : "?");
    *off = ts_append_str(buf, *off, "\n");

    if (info != nullptr) {
        *off = ts_append_str(buf, *off, "fault_addr=");
        *off = ts_append_hex(buf, *off, (uint64_t) (uintptr_t) info->si_addr);
        *off = ts_append_str(buf, *off, "\n");
        // SIGABRT 没有 si_addr 语义: 联合体这一格实为 sigqueue 携带的 si_value,
        // fault_addr 一栏对 SIGABRT 应读作"投递时携带的 value"。同一联合体旁是
        // 发送者 si_pid/si_uid——11-15 事件显示 si_code=SI_QUEUE 而非 abort()
        // 的 SI_TKILL, 即存在第三方定向投递; 不记 pid/uid 就永远查不到发送方。
        if (sig == SIGABRT) {
            *off = ts_append_str(buf, *off, "sender_pid=");
            *off = ts_append_dec(buf, *off, (uint64_t) info->si_pid);
            *off = ts_append_str(buf, *off, " sender_uid=");
            *off = ts_append_dec(buf, *off, (uint64_t) info->si_uid);
            *off = ts_append_str(buf, *off, " code_name=");
            const char *codeName = "SI_?";
            if (info->si_code == SI_USER) codeName = "SI_USER";
            else if (info->si_code == SI_QUEUE) codeName = "SI_QUEUE";
            else if (info->si_code == SI_TKILL) codeName = "SI_TKILL";
            else if (info->si_code == SI_KERNEL) codeName = "SI_KERNEL";
            *off = ts_append_str(buf, *off, codeName);
            *off = ts_append_str(buf, *off, "\n");
        }
    }

#if defined(__aarch64__)
    if (ucontext != nullptr) {
        auto *uc = (ucontext_t *) ucontext;
        *off = ts_append_str(buf, *off, "pc=");
        *off = ts_append_hex(buf, *off, (uint64_t) uc->uc_mcontext.pc);
        *off = ts_append_str(buf, *off, " lr=");
        *off = ts_append_hex(buf, *off, (uint64_t) uc->uc_mcontext.regs[30]);
        *off = ts_append_str(buf, *off, " sp=");
        *off = ts_append_hex(buf, *off, (uint64_t) uc->uc_mcontext.sp);
        *off = ts_append_str(buf, *off, "\n");
        // x0-x3 常是崩溃时的参数/返回对象, 对判断"空指针解引用"最有用
        for (int i = 0; i < 4; ++i) {
            *off = ts_append_str(buf, *off, "x");
            *off = ts_append_dec(buf, *off, (uint64_t) i);
            *off = ts_append_str(buf, *off, "=");
            *off = ts_append_hex(buf, *off, (uint64_t) uc->uc_mcontext.regs[i]);
            *off = ts_append_str(buf, *off, " ");
        }
        *off = ts_append_str(buf, *off, "\n");
    }
#else
    (void) ucontext;
#endif
}

// 把环形缓冲里的历史事件按时间顺序 dump 出来。
// 这是定位"崩溃前模块在做什么"的核心: 寄存器只能说明崩在哪, 事件序列说明为什么。
static void ts_dump_events(char *buf, int *off) {
    // 统计有效槽位
    int valid = 0;
    for (int i = 0; i < TS_EV_MAX; ++i) {
        if (__atomic_load_n(&g_events[i].seq, __ATOMIC_ACQUIRE) != 0) ++valid;
    }
    *off = ts_append_str(buf, *off, "--- 崩溃前事件 (");
    *off = ts_append_dec(buf, *off, (uint64_t) valid);
    *off = ts_append_str(buf, *off, "/");
    *off = ts_append_dec(buf, *off, (uint64_t) TS_EV_MAX);
    *off = ts_append_str(buf, *off, ") ---\n");
    if (valid == 0) {
        *off = ts_append_str(buf, *off, "(空: 崩溃发生在任何打点之前)\n");
        return;
    }
    // 只取最近的 kMaxShow 条(picked 数组内部按 seq 升序维护):
    // 再多对定位无益, 反而淹没关键信息
    const int kMaxShow = 64;
    int picked[kMaxShow];
    int pickedCount = 0;
    for (int i = 0; i < TS_EV_MAX; ++i) {
        uint64_t seq = __atomic_load_n(&g_events[i].seq, __ATOMIC_ACQUIRE);
        if (seq == 0) continue;
        // 插入排序维护最大的 kMaxShow 个 seq(保留最近的事件)
        if (pickedCount < kMaxShow) {
            int j = pickedCount++;
            while (j > 0 &&
                   __atomic_load_n(&g_events[picked[j - 1]].seq, __ATOMIC_ACQUIRE) > seq) {
                picked[j] = picked[j - 1];
                --j;
            }
            picked[j] = i;
        } else if (seq > __atomic_load_n(&g_events[picked[0]].seq, __ATOMIC_ACQUIRE)) {
            int j = 0;
            while (j < kMaxShow - 1 &&
                   __atomic_load_n(&g_events[picked[j + 1]].seq, __ATOMIC_ACQUIRE) < seq) {
                picked[j] = picked[j + 1];
                ++j;
            }
            picked[j] = i;
        }
    }
    // 按 seq 降序输出(最新在前): 事件总量超出 4KB 缓冲被截断时, 丢的是最旧
    // 的事件, 崩溃前最后几次打点必定完整落盘
    for (int k = pickedCount - 1; k >= 0; --k) {
        int i = picked[k];
        char timeBuf[32];
        ts_format_time(g_events[i].ms, timeBuf);
        *off = ts_append_str(buf, *off, "  [");
        *off = ts_append_str(buf, *off, timeBuf);
        *off = ts_append_str(buf, *off, "] ");
        *off = ts_append_str(buf, *off, g_events[i].text);
        *off = ts_append_str(buf, *off, "\n");
    }
}

// _Unwind_Backtrace 的回调上下文: 收集原始 PC 到固定数组。
// bionic 的 backtrace() 在 minSdk 28 上不保证可用(NDK 只在 API 33+ 提供
// <execinfo.h>), 而 _Unwind_Backtrace 由 libgcc/libunwind 提供, 全版本可用,
// 且同样不分配内存, 可在信号处理器内使用。
struct TsUnwindState {
    void *frames[TS_MAX_FRAMES];
    int count;
};

static _Unwind_Reason_Code ts_unwind_cb(struct _Unwind_Context *ctx, void *arg) {
    auto *state = (TsUnwindState *) arg;
    if (state->count >= TS_MAX_FRAMES) {
        return _URC_END_OF_STACK;
    }
    // _Unwind_GetIP 返回当前帧的返回地址; 不做 -1 修正, 由符号化侧处理
    uintptr_t ip = (uintptr_t) _Unwind_GetIP(ctx);
    if (ip != 0) {
        state->frames[state->count++] = (void *) ip;
    }
    return _URC_NO_REASON;
}

// 记录调用栈原始地址。不用 dladdr(会加锁), 由用户侧用 addr2line/ndk-stack 符号化。
static void ts_dump_backtrace(char *buf, int *off) {
    TsUnwindState state;
    state.count = 0;
    _Unwind_Backtrace(ts_unwind_cb, &state);
    *off = ts_append_str(buf, *off, "backtrace(");
    *off = ts_append_dec(buf, *off, (uint64_t) state.count);
    *off = ts_append_str(buf, *off, "):\n");
    for (int i = 0; i < state.count; ++i) {
        *off = ts_append_str(buf, *off, "  #");
        *off = ts_append_dec(buf, *off, (uint64_t) i);
        *off = ts_append_str(buf, *off, " ");
        *off = ts_append_hex(buf, *off, (uint64_t) (uintptr_t) state.frames[i]);
        *off = ts_append_str(buf, *off, "\n");
    }
}

// 链到旧处理器: 让系统照常生成 tombstone / 走 debuggerd,
// 不改变宿主原有的崩溃处理流程
static void ts_chain(int sig, siginfo_t *info, void *ucontext) {
    for (int i = 0; i < g_signal_count; ++i) {
        if (g_signals[i] != sig) continue;
        struct sigaction *old = &g_old[i];
        if ((old->sa_flags & SA_SIGINFO) != 0) {
            if (old->sa_sigaction != nullptr) {
                old->sa_sigaction(sig, info, ucontext);
                return;
            }
        } else if (old->sa_handler != SIG_DFL && old->sa_handler != SIG_IGN &&
                   old->sa_handler != nullptr) {
            old->sa_handler(sig);
            return;
        }
        break;
    }
    // 无旧处理器: 恢复默认行为并重新投递, 让进程按原方式终止。
    // 用 sigaction 而非 signal(后者的重装语义各实现不一致),
    // 用 tgkill 而非 raise(raise 不在 POSIX async-signal-safe 名单,
    // 且 tgkill 精确投递到崩溃线程, 不会把致命信号误递给其它线程)
    struct sigaction dfl;
    memset(&dfl, 0, sizeof(dfl));
    dfl.sa_handler = SIG_DFL;
    sigemptyset(&dfl.sa_mask);
    sigaction(sig, &dfl, nullptr);
    syscall(__NR_tgkill, getpid(), syscall(__NR_gettid), sig);
}

// ---------------------------------------------------------------- 处理器看门狗

// 两阶段兜底(处理器内任何代码都无法保证终止, 只能靠外部线程):
//   阶段1(卡死 5s): 给卡住的线程补投一次 SIGABRT —— 处理器重入被 g_in_handler
//     守卫挡掉记录, 直接 ts_chain 走旧处理器/debuggerd, 争取正常死亡+tombstone;
//   阶段2(卡死 10s): SIGKILL 兜底, 线程定向 SIGKILL 必终止整进程。宁可丢
//     tombstone 也不能让楔死线程静默打转数小时(11-15 事件的直接放大器)。
// 轮询用 nanosleep(普通上下文, 无 async-signal-safe 约束)。阶段推进不依赖
// 落盘说明行: 投递(SIGABRT/SIGKILL)都是纯信号操作, 放在最前, 存储故障最多
// 让说明行写不出来, 卡不死兜底本身。唯一残余风险: 说明行写挂会让看门狗线程
// 卡在 write 里, 阶段2 的 SIGKILL 兜底失效, 只剩阶段1 的链路——此时阶段1
// 已生效, 而"阶段1 也失败 + 存储故障"同时发生的概率可忽略。
static void *ts_watchdog_main(void *arg) {
    (void) arg;
    for (;;) {
        struct timespec ts = {0, TS_WATCHDOG_POLL_MS * 1000000L};
        nanosleep(&ts, nullptr);
        if (__atomic_load_n(&g_in_handler, __ATOMIC_ACQUIRE) == 0) continue;
        uint64_t enter = __atomic_load_n(&g_handler_enter_ms, __ATOMIC_ACQUIRE);
        if (enter == 0) continue;
        int tid = __atomic_load_n(&g_handler_tid, __ATOMIC_ACQUIRE);
        if (tid <= 0) continue;
        uint64_t stuckMs = ts_now_ms() - enter;
        if (stuckMs < (uint64_t) TS_WATCHDOG_TIMEOUT_MS) continue;
        int sig = __atomic_load_n(&g_handler_sig, __ATOMIC_ACQUIRE);
        if (__atomic_load_n(&g_watchdog_stage, __ATOMIC_ACQUIRE) == 0) {
            __atomic_store_n(&g_watchdog_stage, 1, __ATOMIC_RELEASE);
            // 投递先于说明落盘: 投递是安全攸关动作且是纯信号操作(不碰文件,
            // 不可能被存储故障卡住); 说明行只是诊断信息, 放在投递之后即使
            // 写挂了也只是丢一条日志, 不影响本次投递已生效。
            syscall(__NR_tgkill, getpid(), (long) tid, SIGABRT);
            char buf[256];
            int off = 0;
            buf[0] = '\0';
            char timeBuf[32];
            ts_format_time(ts_now_ms(), timeBuf);
            off = ts_append_str(buf, off, "[");
            off = ts_append_str(buf, off, timeBuf);
            off = ts_append_str(buf, off, "] pid=");
            off = ts_append_dec(buf, off, (uint64_t) getpid());
            off = ts_append_str(buf, off, " watchdog: 处理器卡死 sig=");
            off = ts_append_str(buf, off, ts_signal_name(sig));
            off = ts_append_str(buf, off, " tid=");
            off = ts_append_dec(buf, off, (uint64_t) tid);
            off = ts_append_str(buf, off, " 已 ");
            off = ts_append_dec(buf, off, stuckMs);
            off = ts_append_str(buf, off, "ms, 补投 SIGABRT 争取 tombstone\n");
            ts_emit(buf, off);
        } else if (stuckMs >= 2 * (uint64_t) TS_WATCHDOG_TIMEOUT_MS) {
            syscall(__NR_tgkill, getpid(), (long) tid, SIGKILL);
            // 双保险: 线程定向 SIGKILL 理论必死, 进程级再补一刀无副作用
            syscall(__NR_kill, getpid(), SIGKILL);
        }
    }
    return nullptr;
}

static void ts_handler(int sig, siginfo_t *info, void *ucontext) {
    // 递归保护: 处理器自身出错时不再尝试记录, 直接交给旧处理器;
    // 记录条数上限: 超限后跳过 unwind/落盘, 只链回旧处理器
    if (g_in_handler == 0 &&
        __atomic_load_n(&g_logged, __ATOMIC_RELAXED) < TS_MAX_LOGGED) {
        g_in_handler = 1;
        // 看门狗登记(见 g_handler_* 注释): 先清阶段、登记线程/信号、最后
        // 写入进入时刻让看门狗开始计时
        __atomic_store_n(&g_watchdog_stage, 0, __ATOMIC_RELAXED);
        __atomic_store_n(&g_handler_tid, (int) syscall(__NR_gettid), __ATOMIC_RELAXED);
        __atomic_store_n(&g_handler_sig, sig, __ATOMIC_RELAXED);
        __atomic_store_n(&g_handler_enter_ms, ts_now_ms(), __ATOMIC_RELAXED);
        __atomic_fetch_add(&g_logged, 1, __ATOMIC_RELAXED);
        char buf[TS_BUF];
        int off = 0;
        buf[0] = '\0';
        ts_dump_context(sig, info, ucontext, buf, &off);
        ts_emit(buf, off);
        // 事件单独一份缓冲: 64 条 × ~118B 约 7.6KB, 与头部分开发会被 4KB 缓冲
        // 截掉与崩溃点最相关的那批事件
        off = 0;
        buf[0] = '\0';
        ts_dump_events(buf, &off);
        ts_emit(buf, off);
        // 外部投递的 SIGABRT(他人 sigqueue/kill, si_pid 非本进程): 中断点只是
        // 线程恰好执行到的位置而非故障现场, 回溯价值低; 且 11-15 卡死事件
        // 实证 _Unwind_Backtrace 在异常现场可能永不返回。此类信号只落盘
        // 头部+事件环, 发送方由 sender_pid/sender_uid 定位, 栈现场由随后
        // ts_chain 链到的 debuggerd tombstone 补全。本进程内的 abort/故障
        // 信号仍照常回溯(卡死兜底交给看门狗)。
        bool externalSigabrt = sig == SIGABRT && info != nullptr &&
                               (info->si_code == SI_USER || info->si_code == SI_QUEUE) &&
                               (pid_t) info->si_pid != getpid();
        off = 0;
        buf[0] = '\0';
        if (externalSigabrt) {
            off = ts_append_str(buf, off,
                                "backtrace skipped: SIGABRT 由外部进程投递(见 sender_pid), 中断点非故障现场\n");
        } else {
            // 栈单独一份缓冲: 48 帧约 1.2KB, 与上面的头部分开发可以避免挤掉事件
            ts_dump_backtrace(buf, &off);
        }
        ts_emit(buf, off);
        // 注销看门狗登记: 先清 enter(0 = 无活跃处理器)再清 in_handler
        __atomic_store_n(&g_handler_enter_ms, 0, __ATOMIC_RELEASE);
        g_in_handler = 0;
    }
    ts_chain(sig, info, ucontext);
}

// ---------------------------------------------------------------- 对外接口

bool ts_crash_install(const char *logPath, const char *procName, int tzOffsetSeconds) {
    // 内建 exchange 兼作幂等守卫与并发互斥: 并发首调只有一个线程继续
    // install, 其余按"已安装"返回, 避免双份 sigaction 覆盖 g_old
    if (__atomic_exchange_n(&g_installed, 1, __ATOMIC_ACQ_REL)) return true;
    if (logPath == nullptr) {
        __atomic_store_n(&g_installed, 0, __ATOMIC_RELEASE);
        return false;
    }

    int len = 0;
    while (logPath[len] != '\0' && len < TS_PATH - 1) {
        g_log_path[len] = logPath[len];
        ++len;
    }
    if (len == 0) {
        g_log_path[0] = '\0';
        // 必须复位: exchange 已把 g_installed 置 1, 不复位会让后续 install
        // 按"已安装"返回 true, 实际却什么都没装且失去重试能力
        __atomic_store_n(&g_installed, 0, __ATOMIC_RELEASE);
        return false;
    }
    g_log_path[len] = '\0';

    if (procName != nullptr) {
        int n = 0;
        while (procName[n] != '\0' && n < TS_PROC - 1) {
            g_proc_name[n] = procName[n];
            ++n;
        }
        g_proc_name[n] = '\0';
    }
    g_tz_offset = tzOffsetSeconds;

    // 备用信号栈: 没有它, SA_ONSTACK 是空操作, 而栈溢出(反混淆/DexKit 递归)
    // 恰恰是最需要捕获的一类崩溃 —— 主栈已耗尽时处理器根本进不去。
    // 已知边界: sigaltstack 是每线程属性, 这里只覆盖调用 install 的线程;
    // 其它线程(尤其后台线程)的栈溢出退化为无 ONSTACK 处理, 二次 SIGSEGV 被
    // g_in_handler 挡掉后链回默认行为。逐线程补装需 hook pthread_create,
    // 代价大未实施, 故障排查时勿以"所有线程都有黑匣子"为前提。
    stack_t ss;
    memset(&ss, 0, sizeof(ss));
    ss.ss_sp = g_altstack;
    ss.ss_size = TS_ALTSTACK;
    ss.ss_flags = 0;
    if (sigaltstack(&ss, nullptr) != 0) {
        __android_log_print(ANDROID_LOG_WARN, TS_TAG,
                            "crash: sigaltstack failed errno=%d", errno);
    }

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = ts_handler;
    // SA_SIGINFO: 需要 si_addr/si_code 判断是空指针还是越界
    // SA_ONSTACK: 栈溢出导致的 SIGSEGV 需要备用栈才能跑处理器
    // SA_NODEFER: 处理器内部再次崩溃时仍能进入(配合 g_in_handler 递归保护)
    action.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_NODEFER;
    sigemptyset(&action.sa_mask);

    int ok = 0;
    for (int i = 0; i < g_signal_count; ++i) {
        if (sigaction(g_signals[i], &action, &g_old[i]) == 0) {
            ++ok;
        }
    }
    // 看门狗线程: 处理器内无法实现超时, 卡死兜底必须来自外部线程
    // (见 ts_watchdog_main)。install 由 g_installed 幂等守卫保证每进程
    // 只走一次, 恰好一狗。创建失败只失去兜底能力, 记 logcat 即可,
    // 不影响崩溃记录本身。pthread 在 bionic 位于 libc, 无需额外链接。
    if (ok > 0) {
        pthread_t wd;
        if (pthread_create(&wd, nullptr, ts_watchdog_main, nullptr) == 0) {
            pthread_detach(wd);
        } else {
            __android_log_print(ANDROID_LOG_WARN, TS_TAG,
                                "crash: watchdog pthread_create failed errno=%d", errno);
        }
    }
    __atomic_store_n(&g_installed, ok > 0, __ATOMIC_RELEASE);
    return ok > 0;
}

// 注册标签, 返回 id。高频路径(每次 hook 回调)只传 id, 避免反复拷贝字符串。
// 抢槽用原子 fetch_add: nativeRegisterTag 可从任意线程调用, 普通读改写会让
// 两个并发注册写进同一槽并返回同一 id, 之后 trace 的标签文本错乱
int ts_crash_register_tag(const char *tag) {
    if (tag == nullptr) return -1;
    int n = __atomic_fetch_add(&g_tag_count, 1, __ATOMIC_RELAXED);
    if (n >= TS_TAG_MAX) return -1;
    int len = 0;
    while (tag[len] != '\0' && len < TS_EV_LEN - 1) {
        g_tags[n][len] = tag[len];
        ++len;
    }
    g_tags[n][len] = '\0';
    // 槽位就绪发布: fetch_add 先占槽, 文本写满后 release 置就绪标志。
    // g_tag_count 本身先递增无妨——读者(ts_push_event_id)以就绪标志为准,
    // 没就绪时按未注册标签降级, 不会拿到半写文本(事件文本错乱)。
    // 不用"回写 g_tag_count"的方式发布: 并发注册下后写的 release store 会把
    // 计数压回旧值, 永久遮蔽已注册的槽
    __atomic_store_n(&g_tag_ready[n], 1, __ATOMIC_RELEASE);
    return n;
}

void ts_crash_mark(const char *tag, const char *detail) {
    ts_push_event(tag, detail);
    char buf[TS_BUF];
    int off = 0;
    buf[0] = '\0';
    char timeBuf[32];
    ts_format_time(ts_now_ms(), timeBuf);
    off = ts_append_str(buf, off, "[");
    off = ts_append_str(buf, off, timeBuf);
    off = ts_append_str(buf, off, "] pid=");
    off = ts_append_dec(buf, off, (uint64_t) getpid());
    off = ts_append_str(buf, off, " tid=");
    off = ts_append_dec(buf, off, (uint64_t) syscall(__NR_gettid));
    off = ts_append_str(buf, off, " ");
    off = ts_append_str(buf, off, tag != nullptr ? tag : "?");
    if (detail != nullptr) {
        off = ts_append_str(buf, off, ": ");
        off = ts_append_str(buf, off, detail);
    }
    off = ts_append_str(buf, off, "\n");
    ts_emit(buf, off);
}

// 按字符串打点的 ts_crash_trace 已随其唯一调用链(Kotlin trace() →
// nativeTrace JNI)一并移除: 全部打点调用方已迁移到 tagId/traceId 的 id 路径,
// ts_push_event 仍被 mark/heartbeat 复用。
void ts_crash_trace_id(int id, const char *detail) {
    ts_push_event_id(id, detail);
}

void ts_crash_heartbeat(const char *detail) {
    ts_push_event("alive", detail);
    // 每 TS_HEARTBEAT_FLUSH_S 秒落盘一条, 让日志文件本身也有"活到什么时候"的证据。
    // 用 atomic 而非普通 static: 心跳虽由主线程驱动, 但多进程/多线程下
    // 仍可能并发进入, 非原子读改写会让节流失效并刷爆日志文件
    static uint64_t lastFlush = 0;
    uint64_t now = ts_now_ms();
    uint64_t prev = __atomic_load_n(&lastFlush, __ATOMIC_RELAXED);
    if (prev != 0 && now - prev < (uint64_t) TS_HEARTBEAT_FLUSH_S * 1000ULL) return;
    // CAS 失败说明别的线程刚抢到落盘权, 让给它即可
    if (!__atomic_compare_exchange_n(&lastFlush, &prev, now, false,
                                     __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
        return;
    }
    char buf[TS_BUF];
    int off = 0;
    buf[0] = '\0';
    char timeBuf[32];
    ts_format_time(now, timeBuf);
    off = ts_append_str(buf, off, "[");
    off = ts_append_str(buf, off, timeBuf);
    off = ts_append_str(buf, off, "] pid=");
    off = ts_append_dec(buf, off, (uint64_t) getpid());
    off = ts_append_str(buf, off, " alive");
    if (detail != nullptr) {
        off = ts_append_str(buf, off, ": ");
        off = ts_append_str(buf, off, detail);
    }
    off = ts_append_str(buf, off, "\n");
    ts_emit(buf, off);
}
