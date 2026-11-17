package gm.tieba.tabswitch.util

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import gm.tieba.tabswitch.bridge.XposedBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原生崩溃捕获的 Java 侧桥接。
 *
 * <p>本模块在 Android 17(API 37) 上存在 native 层随机闪退，而 Java 的
 * {@code UncaughtExceptionHandler} 完全看不到 native 崩溃。LSPosed 日志与
 * tombstone 在用户设备上都拿不到现场，因此必须自己在进程内装信号处理器。
 *
 * <p>信号处理器本体在 {@code libhide.so}（由 LSPosed 原生接口加载，进程内常驻），
 * 这里只负责：
 * <ul>
 *   <li>把日志文件路径传给 native 侧（native 侧用 open/write 直接追加）；</li>
 *   <li>提供 [mark]（落盘 + 环形缓冲）与 [trace]/[traceId]（仅内存环形缓冲）
 *       两级打点；</li>
 *   <li>驱动主线程心跳，让日志能区分"启动期崩"与"用着崩"；</li>
 *   <li>在 Java 侧记录安装结果，便于判断"崩溃捕获是否真的生效"。</li>
 * </ul>
 *
 * <p>注意：native 侧写的是<b>原始文件路径</b>，而模块日志走 SAF（content://）。
 * 两者不是同一个文件。这里额外维护一个同目录的纯文件日志
 * （{@code TiebaTS-native.txt}），因为信号处理器只能做 open/write，
 * 无法调用 ContentResolver。
 */
object NativeCrashGuard {

    private const val NATIVE_LOG_NAME = "TiebaTS-native.txt"

    /** 心跳间隔：5 秒。崩溃时最后一条心跳即死亡时刻，精度足够且开销可忽略。 */
    private const val HEARTBEAT_INTERVAL_MS = 5_000L

    private val sInstalled = AtomicBoolean(false)
    private val sHeartbeatStarted = AtomicBoolean(false)

    /** native 日志文件；安装失败时为 null。 */
    @Volatile
    private var sNativeLogFile: File? = null

    /** libhide 已加载但 nativeInstall 返回 false(信号处理器注册失败/路径不可写等真失败)。 */
    @Volatile
    private var sNativeInstallReturnedFalse = false

    /** 标签名 -> native 侧 id。高频路径只传 id，避免反复拷贝字符串。 */
    private val sTagIds = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 安装信号处理器。在进程早期调用；失败后允许再次调用重试。
     *
     * <p>路径选择：优先用模块日志所在目录（用户已授权的 SAF 目录解析成真实路径
     * 通常不可行），退化为应用私有目录。私有目录的信号处理器写入不需要任何权限，
     * 且崩溃时一定可写，是唯一可靠的选择。
     *
     * <p>为什么允许重试：LSPosed 下 libhide.so 由 native_init 机制在 Application
     * hook 之前 dlopen，首次调用即成功；而 LSPatch 等无 native_init 机制的环境
     * 里，libhide 要到业务初始化中段（hide_native 开关打开时 load("hide")）才被
     * 装入进程——首次调用时按名解析必然抛 UnsatisfiedLinkError。因此失败时不锁死，
     * 由调用方在库就位后再调一次补装。信号处理器本体有 g_installed 幂等守卫，
     * 重复安装是空操作，重复调用安全。
     */
    fun install(context: Context) {
        if (sInstalled.get()) return
        try {
            val dir = File(context.filesDir, "tsnative")
            if (!dir.exists() && !dir.mkdirs()) {
                Logger.log("NativeCrashGuard: mkdirs failed: ${dir.absolutePath}")
                return
            }
            val file = File(dir, NATIVE_LOG_NAME)
            sNativeLogFile = file
            // 这里刻意<b>不</b>调用 System.loadLibrary("hide"): 该库若已被框架
            // 加载, 再次 loadLibrary 不会重复执行 JNI_OnLoad(dlopen 幂等), 但在
            // 库尚未加载时主动 load 会绕过框架的加载时序; 补装时机由调用方
            // (load("hide") 成功之后) 保证。库未加载时按名解析会抛
            // UnsatisfiedLinkError, 由下面的 catch 降级为纯 Java 日志,
            // 是更安全的失败方式。
            val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
            val ok = NativeCrash.nativeInstall(file.absolutePath, currentProcessName() ?: "unknown", tz)
            sNativeInstallReturnedFalse = !ok
            sInstalled.set(ok)
            Logger.log(
                "NativeCrashGuard: install ${if (ok) "OK" else "FAILED"} " +
                    "path=${file.absolutePath} proc=${currentProcessName() ?: "unknown"} " +
                    "api=${Build.VERSION.SDK_INT} tz=${tz}s"
            )
            if (ok) {
                mark("install", "pid=${Process.myPid()} api=${Build.VERSION.SDK_INT}")
                startHeartbeat()
            }
        } catch (tr: Throwable) {
            // 原生库未加载/符号缺失时不能让模块初始化失败;
            // 失败不置位 sInstalled, 允许后续 libhide 就位后重试。
            // 注记"预期内": LSPatch 等环境下 libhide 尚未加载, 首次调用必然
            // 走到这里, 由调用方在 load("hide") 后补装——不加注记会被当成
            // 崩溃排查线索误读(反馈场景已实际发生)
            Logger.log(
                "NativeCrashGuard: install skipped (预期内: libhide 未就绪, 稍后自动补装) -- $tr"
            )
            XposedBridge.log(
                "NativeCrashGuard: install skipped (预期内: libhide 未就绪, 稍后自动补装) -- $tr"
            )
        }
    }

    /**
     * 主线程心跳。每 5 秒打一条 {@code alive}，每 60 秒落盘一次。
     *
     * <p>这是区分"启动期崩溃"与"使用中崩溃"的唯一手段：如果日志里最后一条
     * 是 {@code alive}，说明进程活到了那个时刻，崩溃发生在之后；如果最后一条
     * 是某个 hook 的 {@code enter} 而没有对应的 {@code done}，说明就崩在那次
     * 调用里。没有心跳时，这两种情况在日志上完全无法区分。
     *
     * <p>用主线程 Handler 而非后台线程：崩溃若是主线程卡死/栈溢出导致的，
     * 心跳停止本身就是重要信号；后台线程心跳则会在主线程已死时继续跳，
     * 反而掩盖问题。
     */
    private fun startHeartbeat() {
        if (!sHeartbeatStarted.compareAndSet(false, true)) return
        try {
            val handler = Handler(Looper.getMainLooper())
            val tick = object : Runnable {
                override fun run() {
                    try {
                        NativeCrash.nativeHeartbeat("main")
                    } catch (ignored: Throwable) {
                    }
                    handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                }
            }
            handler.postDelayed(tick, HEARTBEAT_INTERVAL_MS)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 在可疑路径前后打点，<b>并落盘</b>。
     *
     * <p>这是定位 native 随机闪退最有效的手段：崩溃现场本身往往在 libart 里，
     * 但"崩溃前模块正在做什么"只有靠打点才知道。
     *
     * <p>会打开/写入文件，因此只适合低频事件（安装、hooker 注册、反混淆）。
     * 高频路径用 [tagId] 注册标签后走 [traceId]（仅内存环形缓冲）。
     */
    fun mark(tag: String, detail: String? = null) {
        try {
            NativeCrash.nativeMark(tag, detail)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 打点但<b>只写内存环形缓冲</b>，不碰文件。崩溃时随现场一起 dump。
     *
     * <p>按字符串打点的入口 trace(tag) 已随最后一批调用方迁移而整链移除
     * (2026-11-17): XposedBridge 的 hook 回调打点在注册期即以 [tagId] 注册
     * 标签、回调热路径只走 [traceId], 全库再无按字符串打点的调用方。
     * 低频但需要保留前因的路径用 [mark]（落盘）或 [tagId]+[traceId]（仅内存）。
     */
    fun tagId(tag: String): Int {
        sTagIds[tag]?.let { return it }
        return try {
            val id = NativeCrash.nativeRegisterTag(tag)
            if (id >= 0) sTagIds[tag] = id
            id
        } catch (ignored: Throwable) {
            -1
        }
    }

    /** 用已注册的 id 打点（高频路径首选，省掉每次回调的字符串拷贝）。 */
    fun traceId(id: Int, detail: String?) {
        if (id < 0) return
        try {
            NativeCrash.nativeTraceId(id, detail)
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 崩溃捕获未就绪的原因：供导出原生日志失败时区分"捕获未安装"与"无崩溃记录"。
     * 已安装返回 null；未就绪返回面向用户的可行动提示。
     *
     * <p>安装状态是 per-process 的（sInstalled/sNativeLogFile 都是进程内静态），
     * 但 libhide 的加载条件在所有进程一致（LSPatch 只看 hide_native 开关，
     * LSPosed 看 native_init 机制），导出发生在设置页进程，其状态可代表全局。
     */
    fun unavailableReason(): String? = when {
        sInstalled.get() -> null
        // nativeInstall 返回 false = libhide 已加载但注册真失败(信号处理器/路径问题),
        // 提示"开启开关+重启"无效, 引导反馈日志而非让用户白折腾
        sNativeInstallReturnedFalse -> "崩溃捕获安装失败(信号处理器注册未成功), 请开启日志开关后反馈"
        // sNativeLogFile 已赋值 = install 走到了 nativeInstall 但异常退出:
        // 典型场景是 libhide 未加载。LSPatch 无 native_init 机制, libhide 只在
        // hide_native 开启时才被 load("hide") 装入, 开关关闭则永远无原生日志
        sNativeLogFile != null -> "崩溃捕获未安装: 请开启\"隐藏模块（原生）\"开关并重启贴吧"
        else -> "崩溃捕获未初始化, 请重启贴吧后重试"
    }

    /** 读取 native 崩溃日志尾部，供反馈导出使用。 */
    fun readTail(maxBytes: Int = 64 * 1024): String? {
        val file = sNativeLogFile ?: return null
        return try {
            if (!file.exists()) return null
            val length = file.length()
            if (length <= 0L) return null
            java.io.RandomAccessFile(file, "r").use { raf ->
                val skip = if (length > maxBytes) length - maxBytes else 0L
                raf.seek(skip)
                val bytes = ByteArray((length - skip).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    /** 当前 native 日志路径（供设置在 UI 上展示）。 */
    fun nativeLogPath(): String? = sNativeLogFile?.absolutePath
}
