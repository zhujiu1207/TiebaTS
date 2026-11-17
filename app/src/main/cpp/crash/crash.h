//
// 原生崩溃捕获: 把 native 层 SIGSEGV/SIGABRT/SIGBUS 等的现场写进模块日志。
//
// 背景: 本模块在 Android 17(API 37) 上会出现 native 层随机闪退, 但 LSPosed 日志
// 与 tombstone 都拿不到现场(用户设备上 LSPosed 日志为空)。Java 侧的
// UncaughtExceptionHandler 完全看不到 native 崩溃, 因此必须自己在信号处理器里
// 记录, 否则这类问题无法定位。
//
// 设计约束(信号处理器里能做的事非常有限):
//   * 只用 async-signal-safe 的调用: open/write/close/clock_gettime 可以,
//     dladdr/malloc/stdio/localtime 不行(会加锁或分配内存)。
//     故只记录原始地址, 由用户侧符号化; 时间戳用纯整数运算换算, 不调 localtime。
//   * 不分配内存, 不使用 stdio, 不使用 C++ 异常/RTTI(本工程已 -fno-exceptions)。
//   * 先写自己的 fd, 再链到旧处理器(让系统照常生成 tombstone)。
//   * 递归保护: 处理器自身崩溃时直接放行, 避免无限递归。
//   * 外部投递的 SIGABRT(他人 sigqueue/kill)只落盘头部+事件环并记录
//     sender_pid/sender_uid, 不做栈回溯——中断点非故障现场, 且 11-15 事件
//     实证 _Unwind_Backtrace 在异常现场可能永不返回; 栈现场由链到的
//     debuggerd tombstone 补全。
//   * 看门狗兜底: 处理器内无法实现超时, install 时创建常驻看门狗线程;
//     卡死 5s 补投 SIGABRT 争取 tombstone, 10s 仍不死则 SIGKILL 终止进程,
//     杜绝楔死线程打转数小时拖垮整个应用(11-15 事件)。
//
// 三层记录手段(按开销从低到高):
//   ts_crash_trace_id
//       仅写内存环形缓冲, 不碰文件。给高频 hook 回调打点用,
//       崩溃时随现场一起 dump, 相当于"黑匣子"。
//   ts_crash_mark
//       落盘 + 环形缓冲。给低频关键事件(安装/注册/反混淆)用。
//   ts_crash_heartbeat
//       仅环形缓冲 + 每 60s 落盘一条 alive。
//       崩溃时最后一条心跳就是死亡时刻, 用来区分"启动期崩"和"用着崩"。
//

#ifndef TS_CRASH_H
#define TS_CRASH_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// 安装信号处理器。
//   logPath     可写的绝对路径(应用私有目录), 信号处理器用 open/write 直接追加。
//   procName    进程名, 仅用于日志标识子进程(可为 NULL)。
//   tzOffsetSec 本地时区相对 UTC 的偏移秒数(东八区为 28800), 用于时间戳换算。
// 返回是否至少接管了一个信号。可重复调用: 已安装时直接返回 true;
// 失败后允许再次调用重试(Java 侧在 libhide 就位后补装, 见 NativeCrashGuard)。
bool ts_crash_install(const char *logPath, const char *procName, int tzOffsetSec);

// 注册一个标签, 返回 id(供高频路径复用)。失败返回 -1。
int ts_crash_register_tag(const char *tag);

// 落盘 + 环形缓冲。给低频关键事件打点。
void ts_crash_mark(const char *tag, const char *detail);

// 同 ts_crash_mark, 但用已注册的标签 id, 避免高频路径反复拷贝字符串;
// 仅写环形缓冲(不碰文件)。
void ts_crash_trace_id(int id, const char *detail);

// 心跳: 仅写环形缓冲; 距上次落盘超过 60s 时额外写一条 alive。
void ts_crash_heartbeat(const char *detail);

#ifdef __cplusplus
}
#endif

#endif // TS_CRASH_H
