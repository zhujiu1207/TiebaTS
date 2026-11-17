#include <jni.h>
#include <dlfcn.h>
#include <cstring>
#include <atomic>
#include <sched.h>
#include <android/log.h>
#include "../common/tslog.h"
#include "hide.h"
#include "crash.h"

// atomic: native_init(框架线程)写入 / JNI_OnLoad(加载线程)读取,
// relaxed 足够, 只依赖"写入后可见"的最终性(与 backup_FindClass 同理)
static std::atomic<HookFunType> hook_func{nullptr};

// atomic: hook 安装线程写入 / 任意宿主线程在 fake_FindClass 中读取,
// 无同步的裸全局指针是数据竞争。安装侧在 hook 补丁生效后以 release 重写,
// 读侧 acquire: 保证"任何线程观察到 hook 已生效(fake 被调用)时, backup 写入
// 必然已可见", 消除半初始化窗口内的 NoClassDefFoundError 误抛
std::atomic<jclass (*)(JNIEnv *, const char *)> backup_FindClass{nullptr};

// NoClassDefFoundError 的全局引用: 半初始化窗口(函数表已替换、backup 尚未写回)内
// env->FindClass 会再次命中 fake_FindClass 自身, 上述两个分支若在其中调用 FindClass
// 将无限递归直至栈溢出, 故异常类必须在安装 hook 之前(JNI_OnLoad)预解析缓存
static jclass gNoClassDefFoundError = nullptr;

// 抛 NoClassDefFoundError; 预解析失败(极端场景)下虽无法满足 JNI 契约,
// 至少留一次日志线索, 避免"无异常的 nullptr"完全不可排查
static void throwNcdfe(JNIEnv *env, const char *name) {
    if (gNoClassDefFoundError != nullptr) {
        if (!env->ExceptionCheck()) {
            env->ThrowNew(gNoClassDefFoundError, name);
        }
        return;
    }
    // exchange 保证并发首调下也严格只记一次
    static std::atomic<bool> logged{false};
    if (!logged.exchange(true)) {
        TS_LOGE("hide: NoClassDefFoundError unresolved, FindClass returns null without exception");
    }
}

// libcheck.so 的调用放行: hide 与 check 是并列的用户开关, 共享同一 JNI 函数表——
// checkClassLoader 探测的恰是被屏蔽的 BaseDexClassLoader, 不放行会让痕迹检测的
// 类加载器通道恒报假阴性
// 调用方是否位于 libcheck.so: 用返回地址反查所属 so 的文件名。
// 仅在命中被屏蔽类名时调用, dladdr 的开销可忽略; 失败按"非 check 调用"处理
static bool fromCheckLib(void *retAddr) {
    Dl_info info{};
    if (dladdr(retAddr, &info) == 0 || info.dli_fname == nullptr) return false;
    return strstr(info.dli_fname, "libcheck.so") != nullptr;
}

// backup 指针读取(带窗口期自旋): 半初始化窗口通常只有几条指令, 让出 CPU
// 数次即可等到安装线程的 release 写; 自旋耗尽仍为 null 才按异常路径处理
static jclass (*loadBackupFindClass())(JNIEnv *, const char *) {
    jclass (*backup)(JNIEnv *, const char *) = backup_FindClass.load(std::memory_order_acquire);
    for (int spins = 0; backup == nullptr && spins < 16; ++spins) {
        sched_yield();
        backup = backup_FindClass.load(std::memory_order_acquire);
    }
    return backup;
}

jclass fake_FindClass(JNIEnv *env, const char *name) {
    if (!strcmp(name, "dalvik/system/BaseDexClassLoader")) {
        // 痕迹检测(libcheck.so)的调用方需要真实环境状态, 原样放行;
        // 其余调用方按"类不存在"隐藏
        if (fromCheckLib(__builtin_return_address(0))) {
            jclass (*backup)(JNIEnv *, const char *) = loadBackupFindClass();
            if (backup != nullptr) {
                return backup(env, name);
            }
            throwNcdfe(env, name);
            return nullptr;
        }
        // JNI 契约要求 FindClass 失败必须抛异常: 静默返回 nullptr 时, 不判空的
        // 调用方解引用 jclass 即 SIGSEGV(崩溃点远离模块代码, 无法排查), CheckJNI
        // 环境下 ART 直接 abort。抛 NoClassDefFoundError 与"类确实不存在"的
        // 真实行为一致, 检测方按 ClassNotFoundException/NoClassDefFoundError
        // 处理即是预期的隐藏效果, 宿主侧未捕获时也只是可定位的 Java 异常
        throwNcdfe(env, name);
        return nullptr;
    }
    // 半初始化窗口(hook 已替换函数表但 backup 尚未写回)下绝不调空指针:
    // 保持契约, 抛异常并返回 nullptr, 避免无 pending exception 的 nullptr
    jclass (*backup)(JNIEnv *, const char *) = loadBackupFindClass();
    if (backup == nullptr) {
        throwNcdfe(env, name);
        return nullptr;
    }
    return backup(env, name);
}

void on_library_loaded(const char *name, void *handle) {
    // 放行逻辑在 fake_FindClass 内用返回地址动态判定, 无需在此登记
}

// ---------------------------------------------------------------- 崩溃捕获 JNI
//
// 用 JNI 的名字修饰导出, 不用 RegisterNatives:
//   - libhide.so 由 LSPosed 的 native_init 机制 dlopen, JNI_OnLoad 里
//     FindClass 走系统 ClassLoader, 找不到模块类, RegisterNatives 必然失败;
//   - 名字导出由 ART 在首次调用时解析, 此时模块类已加载, 且 ART 会在全部
//     已加载库中按名查找, 不依赖 ClassLoader 关联。
// 符号名必须与 NativeCrash.java 的类名/方法名严格一致, R8 已 keep。
// Java 侧是 static native, 故第二个参数是 jclass。

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
jboolean Java_gm_tieba_tabswitch_util_NativeCrash_nativeInstall(
        JNIEnv *env, jclass, jstring jpath, jstring jproc, jint jtz) {
    if (jpath == nullptr) return JNI_FALSE;
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return JNI_FALSE;
    const char *proc = nullptr;
    if (jproc != nullptr) {
        proc = env->GetStringUTFChars(jproc, nullptr);
    }
    bool ok = ts_crash_install(path, proc, (int) jtz);
    if (proc != nullptr) env->ReleaseStringUTFChars(jproc, proc);
    env->ReleaseStringUTFChars(jpath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
void Java_gm_tieba_tabswitch_util_NativeCrash_nativeMark(
        JNIEnv *env, jclass, jstring jtag, jstring jdetail) {
    if (jtag == nullptr) return;
    const char *tag = env->GetStringUTFChars(jtag, nullptr);
    if (tag == nullptr) return;
    const char *detail = nullptr;
    if (jdetail != nullptr) {
        detail = env->GetStringUTFChars(jdetail, nullptr);
    }
    ts_crash_mark(tag, detail);
    if (detail != nullptr) env->ReleaseStringUTFChars(jdetail, detail);
    env->ReleaseStringUTFChars(jtag, tag);
}

// 用已注册的标签 id 打点: 省掉每次回调的字符串拷贝与比较, 是高频路径的首选。
// (按字符串打点的 nativeTrace 已随 Kotlin 侧 trace() 一并移除: 全部调用方
// 已迁移到 tagId/traceId 的 id 路径。)
extern "C" [[gnu::visibility("default")]] [[gnu::used]]
jint Java_gm_tieba_tabswitch_util_NativeCrash_nativeRegisterTag(
        JNIEnv *env, jclass, jstring jtag) {
    if (jtag == nullptr) return -1;
    const char *tag = env->GetStringUTFChars(jtag, nullptr);
    if (tag == nullptr) return -1;
    int id = ts_crash_register_tag(tag);
    env->ReleaseStringUTFChars(jtag, tag);
    return (jint) id;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
void Java_gm_tieba_tabswitch_util_NativeCrash_nativeTraceId(
        JNIEnv *env, jclass, jint jid, jstring jdetail) {
    const char *detail = nullptr;
    if (jdetail != nullptr) {
        detail = env->GetStringUTFChars(jdetail, nullptr);
    }
    ts_crash_trace_id((int) jid, detail);
    if (detail != nullptr) env->ReleaseStringUTFChars(jdetail, detail);
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
void Java_gm_tieba_tabswitch_util_NativeCrash_nativeHeartbeat(
        JNIEnv *env, jclass, jstring jdetail) {
    const char *detail = nullptr;
    if (jdetail != nullptr) {
        detail = env->GetStringUTFChars(jdetail, nullptr);
    }
    ts_crash_heartbeat(detail);
    if (detail != nullptr) env->ReleaseStringUTFChars(jdetail, detail);
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
jint JNI_OnLoad(JavaVM *jvm, void *v __unused) {
    JNIEnv *env;

    if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // 在安装 hook 之前预解析异常类并建立全局引用,
    // 避免 fake_FindClass 在半初始化窗口内递归调用自身
    jclass localNcdfe = env->FindClass("java/lang/NoClassDefFoundError");
    if (localNcdfe != nullptr) {
        gNoClassDefFoundError = (jclass) env->NewGlobalRef(localNcdfe);
        env->DeleteLocalRef(localNcdfe);
    }

    if (HookFunType installHook = hook_func.load(std::memory_order_relaxed); installHook != nullptr) {
        // HookFunType 契约(LSPosed wiki typedef 注释 "if success, return 0" +
        // native_api.cpp 内部按 Dobby RS_SUCCESS(0) 判定): 0 为成功; 失败时 hook
        // 未安装、backup 不写入(保持 nullptr), fake 不会被调用, 状态自洽——
        // 打日志让"隐藏静默失效"在 logcat/LSPosed 日志可见
        int ret = installHook((void *) env->functions->FindClass, (void *) fake_FindClass,
                              (void **) &backup_FindClass);
        if (ret != 0) {
            TS_LOGE("hide: install FindClass hook failed (ret=%d), hide disabled", ret);
        } else {
            // LSPosed 框架对 backup 指针的写入是非原子 plain store, 与其它线程的
            // 读之间无 happens-before: 补一次 release 重写, 与 fake_FindClass 的
            // acquire 读配对, 保证"观察到 hook 已生效的线程必然能看到 backup"。
            // 窗口期内的残余读取由 fake 侧自旋兜底(loadBackupFindClass)
            jclass (*bk)(JNIEnv *, const char *) =
                    backup_FindClass.load(std::memory_order_relaxed);
            if (bk != nullptr) {
                backup_FindClass.store(bk, std::memory_order_release);
            }
            TS_LOGI("hide: FindClass hook installed");
        }
    } else {
        // 无 native API 的旧版 LSPosed 不调用 native_init: Java 侧 load("hide") 仍会
        // 进入 JNI_OnLoad, 留日志让"隐藏静默失效"可见(版本不匹配已在 native_init 留痕)
        TS_LOGE("hide: no native API hook_func, hide disabled");
    }
    return JNI_VERSION_1_6;
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    // 布局版本守卫: 实测 LSPosed v1.8.0 起均下发 version=2, 更早版本为 1 且
    // 布局相同; 未知版本按旧布局解读会取到错误函数指针, 直接放弃
    // (隐藏失效但行为确定, 并留 logcat 线索)
    if (entries == nullptr ||
        (entries->version != TS_NATIVE_API_VERSION &&
         entries->version != TS_NATIVE_API_VERSION_LEGACY)) {
        TS_LOGE("hide: native API version mismatch (got=%u expect=%u/%u), hide disabled",
                entries == nullptr ? 0u : entries->version,
                TS_NATIVE_API_VERSION, TS_NATIVE_API_VERSION_LEGACY);
        hook_func.store(nullptr, std::memory_order_relaxed);
        return nullptr;
    }
    hook_func.store(entries->hook_func, std::memory_order_relaxed);
    TS_LOGI("hide: native API ready (version=%u)", entries->version);
    return on_library_loaded;
}
