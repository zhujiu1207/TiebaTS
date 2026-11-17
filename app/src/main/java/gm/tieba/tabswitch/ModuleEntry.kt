package gm.tieba.tabswitch

import gm.tieba.tabswitch.bridge.LoadPackageParam
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.util.DexKitLoader
import gm.tieba.tabswitch.util.Logger
import gm.tieba.tabswitch.util.NativeCrashGuard
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * libxposed API 102 模块入口。
 *
 * <p>本类只做一件事：把 API 102 的生命周期回调翻译成旧版 Xposed 的两个入口
 * （{@code initZygote} / {@code handleLoadPackage}），然后转发给
 * {@link XposedInit}。业务逻辑一行都不在这里，因此迁移框架不需要改动 700 行
 * 初始化代码，回归面被压到最小。
 *
 * <p>生命周期对应关系：
 * <ul>
 *   <li>{@code IXposedHookZygoteInit#initZygote(StartupParam.modulePath)}
 *       → {@link #onModuleLoaded}。API 102 用
 *       {@code getModuleApplicationInfo().sourceDir} 提供同一信息。</li>
 *   <li>{@code IXposedHookLoadPackage#handleLoadPackage(lpparam)}
 *       → {@link #onPackageReady}。该回调保证宿主 ClassLoader 与
 *       AppComponentFactory 已就绪，正是旧版 {@code isFirstApplication}
 *       分支的时机。</li>
 * </ul>
 *
 * <p>API 102 下 {@code de.robv.android.xposed} 系列 API 被框架主动拒绝，
 * 因此全部 hook 经 {@code gm.tieba.tabswitch.bridge} 兼容层落到拦截链上。
 */
class ModuleEntry : XposedModule() {

    /** 业务入口。延迟到 onModuleLoaded 才构造，避免类加载早于框架就绪。 */
    @Volatile
    private var mInit: XposedInit? = null

    /**
     * 当前进程名。API 102 只在 {@code ModuleLoadedParam} 提供进程名，
     * 而 {@code PackageReadyParam} 没有该 getter，故在 onModuleLoaded 缓存。
     */
    @Volatile
    private var mProcessName: String = ""

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        // 兼容层先登记入口实例：此后 XposedBridge.log 才能走框架日志通道
        XposedBridge.attach(this)
        mProcessName = param.processName
        // 框架日志出口在 Logger 初始化前就要可用，这里挂一个直通 sink
        XposedBridge.setLogSink(object : XposedBridge.LogSink {
            override fun log(message: String) {
                Logger.log(message)
            }

            override fun log(tr: Throwable) {
                Logger.log(tr)
            }
        })

        // 高频打点出口：每次 hook 回调进出各写一条到 native 环形缓冲。
        // 用接口注入而非直接调用 NativeCrashGuard，避免 XposedBridge 反向依赖
        // Logger 形成初始化环（NativeCrashGuard 依赖 Logger）。
        XposedBridge.setTraceSink(object : XposedBridge.TraceSink {
            override fun registerTag(tag: String): Int = NativeCrashGuard.tagId(tag)

            override fun trace(id: Int, detail: String?) = NativeCrashGuard.traceId(id, detail)
        })

        val init = XposedInit()
        mInit = init
        try {
            val modulePath = getModuleApplicationInfo().sourceDir
            init.initZygote(modulePath)
        } catch (tr: Throwable) {
            // 取不到模块路径时 hide/check 原生库与 assets 均不可用(handleLoadPackage
            // 构造 AssetManager 会因 sPath 未初始化重抛, 终止整个入口)，视为模块
            // 整体失效，仅留日志
            XposedBridge.log("onModuleLoaded: resolve module path failed: $tr")
            Logger.log("onModuleLoaded: resolve module path failed: $tr")
        }
        // x86_64 翻译运行时(模拟器 arm 转译)下, libdexkit.so 必须抢在宿主建立
        // 自己的翻译命名空间之前加载, 否则扫描期的加载可能落入不可用命名空间;
        // 仅主进程预载(库只在主进程的反混淆里使用), 非翻译环境此调用直接返回
        if (param.processName == "com.baidu.tieba") {
            runCatching { DexKitLoader.preloadForTranslatedRuntime() }
                .onFailure { tr -> XposedBridge.log("DexKitLoader 预载异常: $tr") }
        }
        XposedBridge.log(
            "onModuleLoaded: process=${param.processName} " +
                "framework=$frameworkName/$frameworkVersion api=$apiVersion " +
                "systemServer=${param.isSystemServer}"
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // 只处理贴吧自身。类名探测不能代替包名校验，否则其他应用只要包含同名类
        // 就会被注入一整套贴吧专用 Hook。
        if (param.packageName != "com.baidu.tieba") return
        val init = mInit ?: run {
            XposedBridge.log("onPackageReady: module entry not initialized yet, skip")
            return
        }
        val lpparam = LoadPackageParam(
            param.packageName,
            mProcessName,
            param.classLoader,
            param.isFirstPackage
        )
        try {
            init.handleLoadPackage(lpparam)
        } catch (tr: Throwable) {
            // 初始化异常必须落盘：这是"模块装上但完全不生效"的唯一线索
            XposedBridge.log("handleLoadPackage failed: $tr")
            Logger.log("handleLoadPackage failed: $tr")
            Logger.log(tr)
        }
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        // 本模块大量 hook 持有宿主对象引用（Activity/View/Adapter），热重载后旧
        // ClassLoader 无法回收，且新旧两代 hook 会同时生效导致行为翻倍。
        // 明确拒绝热重载，由框架走冷启动路径。
        XposedBridge.log("onHotReloading: rejected (module holds host object references)")
        return false
    }
}
