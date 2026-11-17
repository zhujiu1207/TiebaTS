package gm.tieba.tabswitch.util;

/**
 * {@code libhide.so} 导出的原生崩溃捕获接口。
 *
 * <p>方法名与签名必须与 {@code cpp/hide/hide.cpp} 的导出符号严格一致：
 * <pre>
 * Java_gm_tieba_tabswitch_util_NativeCrash_nativeInstall
 * Java_gm_tieba_tabswitch_util_NativeCrash_nativeMark
 * Java_gm_tieba_tabswitch_util_NativeCrash_nativeRegisterTag
 * Java_gm_tieba_tabswitch_util_NativeCrash_nativeTraceId
 * Java_gm_tieba_tabswitch_util_NativeCrash_nativeHeartbeat
 * </pre>
 * 因此类名、方法名都不能被 R8 改名（已在 proguard-rules.pro 中 keep）。
 * （按字符串打点的 nativeTrace 已随 Kotlin 侧 trace() 一并移除: 全部调用方
 * 已迁移到 tagId/traceId 的 id 路径。）
 *
 * <p><b>为什么用 Java 而不是 Kotlin object</b>：JNI 按名字解析符号时，
 * 静态方法与实例方法的符号名不同（第二个参数分别是 {@code jclass} 与
 * {@code jobject}）。Kotlin {@code object} + {@code @JvmStatic} 生成的方法
 * 究竟是静态还是实例，取决于编译器的桥接策略，不适合作为稳定的 JNI 契约。
 * 普通 Java 类的 {@code static native} 方法符号名是确定的。
 *
 * <p><b>为什么不用 RegisterNatives</b>：{@code libhide.so} 由 LSPosed 的
 * {@code native_init.list} 机制 dlopen，此时 {@code JNI_OnLoad} 里的
 * {@code FindClass("gm/tieba/tabswitch/util/NativeCrash")} 走的是系统
 * ClassLoader，而模块类在 LSPosed 的模块 ClassLoader 里——必然找不到，
 * 注册静默失败。改用名字导出后，ART 在<b>首次调用</b>该 native 方法时才解析
 * 符号，此时模块类已加载，且 ART 会在全部已加载库中按名查找，不依赖
 * ClassLoader 关联。
 *
 * <p>所有方法都可能在极早期被调用，因此不依赖任何 Android 框架类，
 * 不抛受检异常，失败时静默降级。
 */
public final class NativeCrash {

    private NativeCrash() {
    }

    /**
     * 安装信号处理器。返回是否至少接管了一个信号。
     *
     * @param logPath     原生日志的绝对路径（native 侧用 open/write 追加）
     * @param procName    进程名，用于在日志里区分主进程与子进程
     * @param tzOffsetSec 本地时区相对 UTC 的偏移秒数（东八区为 28800）
     */
    public static native boolean nativeInstall(String logPath, String procName, int tzOffsetSec);

    /** 打点并落盘：崩溃时最后一条 mark 即嫌疑点。给低频关键事件用。 */
    public static native void nativeMark(String tag, String detail);

    /**
     * 注册标签，返回 id。高频路径先用 id 注册，之后只传 id 打点，
     * 省掉每次回调的字符串拷贝。失败返回 -1。
     */
    public static native int nativeRegisterTag(String tag);

    /** 用已注册的标签 id 打点（高频路径首选）。 */
    public static native void nativeTraceId(int id, String detail);

    /** 心跳：仅写环形缓冲，每 60s 额外落盘一条 alive。 */
    public static native void nativeHeartbeat(String detail);
}
