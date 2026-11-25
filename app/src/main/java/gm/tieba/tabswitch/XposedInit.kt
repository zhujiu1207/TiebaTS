package gm.tieba.tabswitch

import android.app.Activity
import android.app.AlertDialog
import android.app.AppComponentFactory
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import gm.tieba.tabswitch.bridge.LoadPackageParam
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import gm.tieba.tabswitch.Constants.strings
import gm.tieba.tabswitch.dao.AcRules
import gm.tieba.tabswitch.dao.AcRules.countAll
import gm.tieba.tabswitch.dao.Adp.initializeAdp
import gm.tieba.tabswitch.dao.Preferences
import gm.tieba.tabswitch.dao.Preferences.getAll
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.dao.Preferences.putBoolean
import gm.tieba.tabswitch.dao.Preferences.putStringSet
import gm.tieba.tabswitch.dao.Preferences.putSignature
import gm.tieba.tabswitch.dao.Preferences.remove
import gm.tieba.tabswitch.dao.ThreadSnapshotStore
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.MAIN_TAB_ACTIVITY
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.TSPreference
import gm.tieba.tabswitch.hooker.add.HistoryCache
import gm.tieba.tabswitch.hooker.add.ProfileUserId
import gm.tieba.tabswitch.hooker.add.SaveImages
import gm.tieba.tabswitch.hooker.add.SelectClipboard
import gm.tieba.tabswitch.hooker.add.SubPbLatestSort
import gm.tieba.tabswitch.hooker.add.ThreadSnapshot
import gm.tieba.tabswitch.hooker.auto.AutoOpenSign
import gm.tieba.tabswitch.hooker.auto.AutoSign
import gm.tieba.tabswitch.hooker.auto.FrsTab
import gm.tieba.tabswitch.hooker.auto.MsgCenterTab
import gm.tieba.tabswitch.hooker.auto.NotificationDetect
import gm.tieba.tabswitch.hooker.auto.OneKeyLottery
import gm.tieba.tabswitch.hooker.auto.OriginSrc
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.currentModuleId
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.getDeobfsModuleId
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.getTbVersion
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.isTbBetweenVersionRequirement
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.isTbSatisfyVersionRequirement
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.isVersionChanged
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.recordDeobfsRun
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.saveAndRestart
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHooker
import gm.tieba.tabswitch.hooker.eliminate.ContentFilter
import gm.tieba.tabswitch.hooker.eliminate.CommentToolbar
import gm.tieba.tabswitch.hooker.eliminate.FoldTopCardView
import gm.tieba.tabswitch.hooker.eliminate.FollowFilter
import gm.tieba.tabswitch.hooker.eliminate.HideAiCard
import gm.tieba.tabswitch.hooker.eliminate.HideSignPopup
import gm.tieba.tabswitch.hooker.eliminate.FragmentTab
import gm.tieba.tabswitch.hooker.eliminate.FrsPageFilter
import gm.tieba.tabswitch.hooker.eliminate.HomeSearchIcon
import gm.tieba.tabswitch.hooker.eliminate.HomeFeedFilter
import gm.tieba.tabswitch.hooker.eliminate.BountyPostFilter
import gm.tieba.tabswitch.hooker.eliminate.GamePromoPostFilter
import gm.tieba.tabswitch.hooker.eliminate.ScorePostFilter
import gm.tieba.tabswitch.hooker.eliminate.GoodsPostFilter
import gm.tieba.tabswitch.hooker.eliminate.HotPostFilter
import gm.tieba.tabswitch.hooker.eliminate.LivePostFilter
import gm.tieba.tabswitch.hooker.eliminate.RecommendForumFilter
import gm.tieba.tabswitch.hooker.eliminate.HomeTopBar
import gm.tieba.tabswitch.hooker.eliminate.HomeTopTab
import gm.tieba.tabswitch.hooker.eliminate.MsgCenterPurge
import gm.tieba.tabswitch.hooker.eliminate.Purge
import gm.tieba.tabswitch.hooker.eliminate.PurgeAd
import gm.tieba.tabswitch.hooker.eliminate.PurgeYunDialog
import gm.tieba.tabswitch.hooker.eliminate.PurgeEnter
import gm.tieba.tabswitch.hooker.eliminate.PurgeChatFloat
import gm.tieba.tabswitch.hooker.eliminate.PurgeMy
import gm.tieba.tabswitch.hooker.eliminate.PurgeVideo
import gm.tieba.tabswitch.hooker.eliminate.PurgeShareChatroom
import gm.tieba.tabswitch.hooker.eliminate.RedTip
import gm.tieba.tabswitch.hooker.eliminate.RemoveUpdate
import gm.tieba.tabswitch.hooker.eliminate.UserFilter
import gm.tieba.tabswitch.hooker.extra.AutoRefresh
import gm.tieba.tabswitch.hooker.extra.ForbidGesture
import gm.tieba.tabswitch.hooker.extra.LowSpecMode
import gm.tieba.tabswitch.hooker.extra.StackTrace
import gm.tieba.tabswitch.util.fixAlertDialogWidth
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.util.Logger
import gm.tieba.tabswitch.util.NativeCrashGuard
import gm.tieba.tabswitch.widget.TbToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 模块业务入口：全部 hooker 的注册与初始化流程。
 *
 * <p>本类<b>不</b>直接实现框架接口。libxposed API 102 的入口是
 * {@link ModuleEntry}（由框架按 META-INF/xposed/java_init.list 声明反射实例化），
 * 其职责是绑定后端后把生命周期转发到本类的
 * {@code initZygote}/{@code handleLoadPackage}。本类只依赖
 * {@code gm.tieba.tabswitch.bridge} 门面，对实际框架无感知。
 */
class XposedInit : XposedContext() {

    companion object {
        // 初始化重入守卫: callApplicationOnCreate 对每个 Application 实例都会触发,
        // 宿主在单进程内重建/多加载 Application 时若不拦截, 全部 hooker 会重复注册
        // (过滤类幂等但 AutoSign/Deobf 重定向等会双重生效, 且浪费启动耗时)
        private val sInited = AtomicBoolean(false)

        // 崩溃守护定时器每进程只装一次: 初始化失败重试会重跑整个初始化流程,
        // scheduleCrashGuardCheck 不幂等(每次调用都新建 Handler+Runnable 链),
        // 持续失败场景会堆积多组 30s 周期任务
        private val sCrashGuardScheduled = AtomicBoolean(false)

        // "规则异常"对话框钩点每进程只注册一次: 初始化在对话框注册之后失败重试时,
        // 重复注册会让 MainTabActivity.onCreate 叠加弹出多个相同对话框。注册失败
        // (类漂移)也不重试——失败即对话框缺失, 重试大概率同样失败, 与防堆积权衡
        private val sRulesDialogRegistered = AtomicBoolean(false)
    }

    private var mAppComponentFactory: AppComponentFactory? = null

    /**
     * 旧版 {@code IXposedHookZygoteInit#initZygote} 的等价入口。
     *
     * <p>职责只有一件事：拿到模块自身 APK 路径（用于
     * {@code System.load("$path!/lib/abi/lib*.so")} 与模块 assets 读取）。
     * 由 {@link ModuleEntry} 从 {@code getModuleApplicationInfo().sourceDir}
     * 提供。
     */
    fun initZygote(modulePath: String) {
        sPath = modulePath
    }

        // 包裹宿主原 UncaughtExceptionHandler: 崩溃时先落盘日志再转发,
    // 不改变宿主原有崩溃流程; 日志写入自身抛异常时静默忽略, 避免二次崩溃
    private class CrashLogHandler(
        private val wrapped: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                // 同步落盘: 进程即将退出, 异步队列来不及写会丢崩溃日志
                Logger.logSync("===== 未捕获异常闪退 =====")
                // @Suppress("DEPRECATION"): Thread.getId() 仅编译期 JDK 19+ 标注 deprecated,
                // 运行环境 Android(API 28) 全版本可用且无替代 API(threadId 为 JVM 19+)
                @Suppress("DEPRECATION")
                val threadId = thread.id
                Logger.logSync("thread=${thread.name}(id=$threadId)")
                Logger.logSync(throwable)
            } catch (ignored: Throwable) {
            }
            try {
                wrapped?.uncaughtException(thread, throwable)
            } catch (ignored: Throwable) {
            }
        }
    }

    // 安装崩溃日志处理器; 若当前 handler 已是本模块的则跳过, 避免重复包裹。
    // 返回是否实际执行了包裹(供守护校验退避判断)
    private fun installCrashLogger(): Boolean {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is CrashLogHandler) return false
        Thread.setDefaultUncaughtExceptionHandler(CrashLogHandler(current))
        return true
    }

    // 宿主 SDK(如崩溃上报组件)可能在模块安装之后才设置自己的 handler, 顶掉模块的包裹层,
    // 导致闪退记录静默失效。主线程定时校验: 一旦被顶掉就重新包裹当前 handler(保留宿主行为);
    // 连续无异常校验后逐步拉长间隔(30s→最大 10min), 降低常驻唤醒
    private fun scheduleCrashGuardCheck() {
        // compareAndSet 幂等守卫: 已调度过直接返回, 防失败重试路径重复堆积定时任务
        if (!sCrashGuardScheduled.compareAndSet(false, true)) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var okStreak = 0
        var delay = 30_000L
        val check = object : Runnable {
            override fun run() {
                try {
                    val wrapped = installCrashLogger()
                    okStreak = if (wrapped) 0 else (okStreak + 1).coerceAtMost(600)
                    if (okStreak > 0 && okStreak % 10 == 0 && delay < 600_000L) {
                        delay = (delay * 2).coerceAtMost(600_000L)
                    }
                } catch (ignored: Throwable) {
                }
                handler.postDelayed(this, delay)
            }
        }
        handler.postDelayed(check, delay)
    }

    // hook Runtime 的两个加载入口覆盖 System.loadLibrary/load 两条路径。
    // hookAllMethods 而非 findAndHookMethod: loadLibrary0 的签名随 API 演进
    // (参数增减/重载), 按名匹配全部重载最稳; 单个 hook 失败只损失屏蔽能力,
    // 不中断模块初始化
    private fun blockMsaSecurityLibrary() {
        for (methodName in listOf("loadLibrary0", "load0")) {
            try {
                XposedBridge.hookAllMethods(
                    Runtime::class.java, methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val libName = param.args.filterIsInstance<String>().firstOrNull() ?: return
                            if (libName.contains("msaoaidsec")) {
                                Logger.log("Blocked native library load: $libName")
                                // 必须经 setResult 置位 returnEarly: 直接给 result 字段
                                // 赋值不保证跳过原方法(见 FragmentTab 同类注释),
                                // 短路失败会让 MSA 库照常加载并崩溃
                                param.setResult(null)
                            }
                        }
                    }
                )
            } catch (tr: Throwable) {
                logBoth("Deobfuscation: hook Runtime.$methodName failed -- $tr")
            }
        }
    }

    /**
     * 偏好迁移: 旧版本遗留键/合并开关向现行结构收敛。每段迁移天然幂等——
     * 旧键移除后下一次启动快照中不再出现, 条件不成立即空转。
     *
     * <p>调用方传入 Preferences.init 之后的快照副本(getAll 每次返回副本):
     * 键存在性判定以快照为准(半程写入不影响本段判定), 开关值读取走 getBoolean
     * 实时读(与快照同库, 语义一致)。
     */
    private fun applyPreferenceMigrations(allPrefs: Map<String, *>) {
        // 迁移: 4.4.1 将"精简首页顶栏"拆分为隐藏游戏手柄/隐藏侧边栏,
        // 旧开关开启时把两个新开关置开, 仅迁移一次后移除旧键
        if (getBoolean("home_top_bar") &&
            !allPrefs.containsKey("hide_sidebar") &&
            !allPrefs.containsKey("hide_gamepad")
        ) {
            putBoolean("hide_sidebar", true)
            putBoolean("hide_gamepad", true)
            remove("home_top_bar")
            Logger.log("migrated home_top_bar -> hide_sidebar + hide_gamepad")
        }

        // 迁移: 顶部Tab隐藏的 5 个分项开关(隐藏直播/有料/关注/三角洲/S16)合并为
        // "隐藏顶部Tab"(home_top_tab_hide), tab 名称随热更新变化改为设置弹窗
        // 实时勾选; 旧开关任一存在时按固定映射迁移名称集合, 仅迁移一次后移除旧键
        val topTabOldKeys = listOf("hide_live", "hide_material", "hide_follow", "hide_delta", "hide_s16")
        if (topTabOldKeys.any { allPrefs.containsKey(it) }) {
            val topTabHideNames = LinkedHashSet<String>()
            if (getBoolean("hide_live")) topTabHideNames.add("直播")
            if (getBoolean("hide_material")) topTabHideNames.add("有料")
            if (getBoolean("hide_follow")) topTabHideNames.add("关注")
            if (getBoolean("hide_delta")) topTabHideNames.add("三角洲")
            if (getBoolean("hide_s16")) topTabHideNames.add("S16")
            if (topTabHideNames.isNotEmpty()) {
                putBoolean("home_top_tab_hide", true)
                putStringSet("home_top_tab_hide_names", topTabHideNames)
            }
            topTabOldKeys.forEach { remove(it) }
            Logger.log("migrated hide_live/material/follow/delta/s16 -> home_top_tab_hide: $topTabHideNames")
        }

        // 迁移: 清理历史版本遗留的 auto_deobf_<versionCode> 自愈记录键。
        // 现行代码不读不写该键(旧版"本版本已自动补扫"标记), 纯孤儿数据;
        // 残留会被 logSessionStart 误列入"已开启功能", 一次清空即根除
        val staleDeobfKeys = allPrefs.keys.filter { it.startsWith("auto_deobf_") }
        if (staleDeobfKeys.isNotEmpty()) {
            staleDeobfKeys.forEach { remove(it) }
            Logger.log("migrated 清理遗留 auto_deobf_* 键: $staleDeobfKeys")
        }

        // 迁移: "隐藏模块"(hide, Java 层 getStackTrace 特征帧过滤)功能已移除,
        // 清理残留开关。残留键无对应 hooker 不会注册, 但会虚增启动日志的
        // "成功=N"计数(activeHookerKeys 按 preferences 全集统计)
        if (allPrefs.containsKey("hide")) {
            remove("hide")
            Logger.log("migrated 清理已移除的 hide(隐藏模块)键")
        }

        // 迁移: "过场动画"(transition_animation)hooker 已移除(2026-11-21 审查:
        // 22.11 设置页重写起 UI 即无此开关, 键全项目无写入点, hooker 对新装用户
        // 不可达; 老用户残留键使其以"幽灵态"激活且无 UI 可关闭)。清键 + 摘除登记。
        // 该 hooker 的效果本就是把宿主自己的过场常量原样回放(等效宿主默认行为),
        // 摘除后幽灵用户仅回到宿主原生过场, 无可感知行为差
        if (allPrefs.containsKey("transition_animation")) {
            remove("transition_animation")
            Logger.log("migrated 清理已移除的 transition_animation(过场动画)键")
        }
    }

    fun handleLoadPackage(lpparam: LoadPackageParam) {
        // 先过滤再打日志: 该回调对贴吧的每个进程都会触发, 未命中的进程无需留下日志
        // 只处理贴吧自身。类名探测不能代替包名校验，否则其他应用只要包含同名类
        // 就会被注入一整套贴吧专用 Hook。
        if (lpparam.packageName != "com.baidu.tieba" || !lpparam.isFirstApplication) return
        Logger.log("handleLoadPackage: pkg=${lpparam.packageName} process=${lpparam.processName} firstApp=${lpparam.isFirstApplication}")

        // 屏蔽 MSA 安全组件 libmsaoaidsec.so: 该库初始化时检测到 hook 框架即主动
        // 触发 SIGSEGV 自毁(loki 回溯: 崩溃点在其 _init 检测代码内), 是贴吧带模块
        // 启动闪退的根源(与检测时序存在竞争, 故表现为偶发)。拦截加载入口跳过该库,
        // 其后续 native 调用只会抛可被宿主捕获的 UnsatisfiedLinkError。
        // 必须在 handleLoadPackage 阶段注册, 抢在宿主 Application 加载任何库之前
        blockMsaSecurityLibrary()

        sClassLoader = lpparam.classLoader
        // 模块自身资源：XModuleResources 是 legacy 框架提供的类，双后端下不可用；
        // 反射 AssetManager.addAssetPath 是纯 framework API，两代框架行为一致
        sAssetManager = try {
            android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
                .also { am ->
                    val addAssetPath = android.content.res.AssetManager::class.java
                        .getDeclaredMethod("addAssetPath", String::class.java)
                    addAssetPath.isAccessible = true
                    addAssetPath.invoke(am, sPath)
                }
        } catch (tr: Throwable) {
            XposedBridge.log("open module assets failed: $tr")
            throw tr
        }
        // 该 factory 仅用于 LSPatch 下 Flutter 页的 workaround, 类名随版本漂移时
        // 降级为 null(跳过对应 workaround), 不能让整个模块初始化中断
        mAppComponentFactory = try {
            sClassLoader.loadClass("com.baidu.nps.hook.component.NPSComponentFactory")
                .getDeclaredConstructor().newInstance() as AppComponentFactory
        } catch (tr: Throwable) {
            XposedBridge.log(tr)
            null
        }
        Logger.log("NPSComponentFactory ${if (mAppComponentFactory != null) "OK" else "MISSING(degraded)"}")

        // For some reason certain flutter page will not load in LSPatch unless we manually load the flutter plugin
        // 注册动作本身也要防护: 宿主移除/改名 FlutterPluginManager 时 findAndHookMethod
        // 抛 ClassNotFoundError 会中断 handleLoadPackage, 整个模块(含后续全部初始化)失效;
        // 该 workaround 仅 LSPatch 需要, 失败降级跳过即可
        try {
            hookAfterMethod(
                "com.baidu.tieba.flutter.FlutterPluginManager",
                "invokePlugin", "com.baidu.nps.main.invoke.IInvokeCallback"
            ) { param ->
                try {
                    val npsManager = XposedHelpers.callStaticMethod(
                        findClass("com.baidu.nps.main.manager.NPSManager"),
                        "getInstance"
                    )
                    XposedHelpers.callMethod(
                        npsManager,
                        "loadClazz",
                        "com.baidu.tieba.plugin.flutter",
                        "com.baidu.tieba.flutter.FlutterPluginImpl",
                        findClass("com.baidu.tieba.flutter.IFlutterPlugin"),
                        param.args[0]
                    )
                } catch (ignored: Error) {
                } catch (ignored: RuntimeException) {
                    // 新桥接层 callMethod/callStaticMethod 把宿主方法抛出的异常包装为
                    // IllegalStateException(非 Error), 仅接 Error 会漏掉宿主侧普通异常
                }
            }
        } catch (tr: Throwable) {
            XposedBridge.log(tr)
            Logger.log("FlutterPluginManager hook 注册失败(降级跳过): $tr")
        }

        hookAfterMethod(
            Instrumentation::class.java,
            "callApplicationOnCreate", Application::class.java
        ) { param ->
            if (param.args[0] !is Application) return@hookAfterMethod
            // 只有完整初始化成功后才置位。初始化中途失败时保留 false，允许下一次
            // Application 回调重试，避免一次瞬时异常永久关闭整个模块。
            if (sInited.get()) {
                Logger.log("module already initialized, skip re-init")
                return@hookAfterMethod
            }

            attachBaseContext((param.args[0] as Application))
            Logger.log("Application.onCreate hook fired")
            Preferences.init(getContext())
            // 日志开关缓存失效监听: 必须在 init 后登记, 热路径 isEnabled 才能走缓存
            Logger.registerPrefsListener()
            AcRules.init(getContext())
            // 帖子浏览缓存库: 与规则库同款惰性初始化, 失败静默降级(读写点按 isReady 守卫)
            ThreadSnapshotStore.init(getContext())
            // Room 首次查询会在调用线程打开库文件并做 schema 校验: 启动路径的
            // findMissing 必须同步等结果, 把"打开连接"部分先在后台线程预热。
            // 预热与 findMissing 是并发启动的, 主线程若先到仍会同步承担打开开销,
            // 用 latch 建立先后关系: findMissing 前等预热完成(有超时上限防病态
            // 挂起), 等待的只是预热剩余时间——中间的版本查询/日志等耗时已与之重叠,
            // 严格优于主线程从头打开
            val roomWarmupDone = java.util.concurrent.CountDownLatch(1)
            thread(name = "TiebaTS-RoomWarmup", isDaemon = true) {
                try {
                    AcRules.countAll()
                } catch (ignored: Throwable) {
                } finally {
                    roomWarmupDone.countDown()
                }
            }
            DeobfuscationHelper.sCurrentTbVersion = getTbVersion(getContext())
            Logger.log("TB version=${DeobfuscationHelper.sCurrentTbVersion}")
            // 注册全局未捕获异常处理器: 闪退时先把崩溃堆栈写入模块日志, 再交给原处理器;
            // 并定时校验处理器未被宿主 SDK 顶掉
            installCrashLogger()
            scheduleCrashGuardCheck()
            // 安装原生信号处理器: Java 的 UncaughtExceptionHandler 看不到 native 崩溃,
            // 而本模块在 API 37 上的随机闪退正是 native 层的。必须在初始化早期装上,
            // 才能覆盖后续全部可疑路径。
            // 挪到 logSessionStart 之前: logEnvironment 的"原生日志:"才能打出真实
            // 路径(首次 install 已执行), 而不是恒为 unavailable
            NativeCrashGuard.install(getContext())
            NativeCrashGuard.mark("session-start", "tb=${DeobfuscationHelper.sCurrentTbVersion}")
            // 日志开关已开启时写一条环境信息(模块/贴吧版本、设备、已开启功能)
            if (Logger.isEnabled()) Logger.logSessionStart()

            // 偏好迁移: 旧版本遗留键/合并开关向现行结构收敛, 详见方法内逐段注释
            applyPreferenceMigrations(getAll())

            // Workaround to address an issue with LSPatch (unable to open personal homepage)
            // com.baidu.tieba.flutter.base.view.FlutterPageActivity must be instantiated by com.baidu.nps.hook.component.NPSComponentFactory
            // However, LSPatch incorrectly sets appComponentFactory to null, causing android.app.Instrumentation.getFactory to fall back to AppComponentFactory.DEFAULT
            // (see https://github.com/LSPosed/LSPatch/blob/bbe8d93fb9230f7b04babaf1c4a11642110f55a6/patch-loader/src/main/java/org/lsposed/lspatch/loader/LSPApplication.java#L173)
            if (mAppComponentFactory != null && getContext().applicationInfo.appComponentFactory == null) {
                // hookSafely 隔离: getFactory 随 API 版本增减(低版本不存在/非公开),
                // findMethodExact 抛 NoSuchMethod 时不能拖垮其后的 hooker 注册循环
                hookSafely("XposedInit", "lspatch-getfactory") {
                    hookAfterMethod(
                        Instrumentation::class.java,
                        "getFactory", String::class.java
                    ) { hookParam ->
                        if (hookParam.args[0].toString() == "com.baidu.tieba") {
                            hookParam.result = mAppComponentFactory
                        }
                    }
                }
            }

            // 惰性构造: hooker 实例化与 hook() 同级隔离, 单个构造器抛异常
            // (如字段初始化中的资源/反射失败)只损失自身, 不再炸掉全部 hooker
            val hookerProviders: List<() -> IHooker> = listOf(
                ::TSPreference,
                ::FragmentTab,
                ::Purge,
                ::PurgeAd,
                ::PurgeYunDialog,
                ::HomeTopBar,
                ::HomeTopTab,
                ::HomeSearchIcon,
                ::MsgCenterPurge,
                ::PurgeChatFloat,
                ::PurgeEnter,
                ::PurgeMy,
                ::RedTip,
                ::FollowFilter,
                ::HideSignPopup,
                ::HideAiCard,
                ::ContentFilter,
                { CommentToolbar },
                ::FrsPageFilter,
                ::HistoryCache,
                ::SaveImages,
                ::AutoSign,
                ::AutoOpenSign,
                ::OriginSrc,
                ::ForbidGesture,
                ::FrsTab,
                ::MsgCenterTab,
                ::StackTrace,
                ::RemoveUpdate,
                ::FoldTopCardView,
                ::PurgeShareChatroom,
                ::NotificationDetect,
                ::OneKeyLottery,
                ::PurgeVideo,
                ::HomeFeedFilter,
                ::HotPostFilter,
                ::LivePostFilter,
                ::BountyPostFilter,
                ::GamePromoPostFilter,
                ::GoodsPostFilter,
                ::ScorePostFilter,
                ::RecommendForumFilter,
                ::SelectClipboard,
                ::ProfileUserId,
                ::UserFilter,
                ::SubPbLatestSort,
                ::ThreadSnapshot,
                ::AutoRefresh,
                ::LowSpecMode
            )
            val hookers: List<IHooker> = hookerProviders.mapNotNull { provider ->
                try {
                    provider()
                } catch (tr: Throwable) {
                    Logger.log("hooker 构造失败: $tr")
                    XposedBridge.log(tr)
                    null
                }
            }
            val matchers = ArrayList<Obfuscated>(hookers.size + 1)
            // 构造隔离与 hookerProviders 一致: TbToast 初始化失败只损失 toast 功能,
            // 不中止整轮初始化(hooker 构造已全部隔离, 此处是最后一个裸露点)
            try {
                matchers.add(TbToast())
            } catch (tr: Throwable) {
                Logger.log("TbToast 构造失败: $tr")
                XposedBridge.log(tr)
            }
            hookers.forEach { hooker ->
                if (hooker is Obfuscated) {
                    matchers.add(hooker as Obfuscated)
                }
            }

            // matchers() 求值同样隔离: 单个 hooker 的 matchers() 抛异常(如其字段
            // 初始化链失败)只丢弃该 hooker 的规则, 不中止其余规则的收集
            val matchersList = matchers.flatMap { obfuscated ->
                try {
                    obfuscated.matchers()
                } catch (tr: Throwable) {
                    Logger.log("matcher 收集失败(${obfuscated.javaClass.simpleName}): $tr")
                    XposedBridge.log(tr)
                    emptyList()
                }
            }.toMutableList()

            // Remove matchers that does not satisfy version requirement
            matchersList.removeIf { matcher ->
                matcher.requiredVersion?.let { requiredVersion ->
                    val isVersionSatisfied = isTbSatisfyVersionRequirement(requiredVersion)
                    if (!isVersionSatisfied) {
                        XposedBridge.log(
                            "Skipping rule [%s] due to version mismatch (current version: %s)".format(
                                Locale.CHINA,
                                matcher.toString(),
                                DeobfuscationHelper.sCurrentTbVersion
                            )
                        )
                        true
                    } else {
                        false
                    }
                } ?: false
            }

            if (isVersionChanged(getContext())) {
                XposedBridge.log("Deobfuscation")
                // 延迟触发: 首页(MainTab)渲染稳定后弹确认框, 用户确认后再执行反混淆,
                // 完成后由用户确认重启; 不再重定向 Logo 页/冻结导航/自动重启。
                // 本会话不加载任何 hooker(规则库即将重建), 裸跑贴吧
                // 无论 hook() 成败都必须置位 sInited: hook() 非全防护, 注册失败抛出的
                // 异常会被外层吞掉, 若不置位下一个 Application 实例会重复注册触发钩子
                runCatching { DeobfuscationHooker(matchersList).hook() }
                    .onFailure {
                        // 反混淆是本会话唯一入口, 失败后"模块装了但功能全没了",
                        // 两种日志都不留痕则用户反馈无从排查
                        XposedBridge.log("DeobfuscationHooker hook failed: $it")
                        Logger.log("版本变化触发反混淆失败: ${it.javaClass.name}: ${it.message}")
                    }
                sInited.set(true)
                return@hookAfterMethod
            }

            // 规则缺失查询下沉到后台线程（内含等预热）：findMissing 的结果决定后续走
            // 补扫还是正常加载, 但"打开库文件 + 查询"不必占用主线程——这里正是宿主
            // 冷启动最忙的窗口, 主线程只为收口等待（上限 1s, 病态挂起时放弃等待走
            // 正常路径）。超时未完成时按"无缺失"继续: 缺失规则由 hooker 逐规则隔离
            // 自然降级, 且补扫判据在下次启动仍会重试, 不会永久跳过自愈
            val ruleQueryDone = java.util.concurrent.CountDownLatch(1)
            val missingRef = arrayOfNulls<List<String>>(1)
            thread(name = "TiebaTS-RuleQuery", isDaemon = true) {
                try {
                    runCatching { roomWarmupDone.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                    missingRef[0] = AcRules.findMissing(matchersList.map { it.toString() })
                } catch (ignored: Throwable) {
                } finally {
                    ruleQueryDone.countDown()
                }
            }
            // await 返回 true 才采用结果: latch 建立 happens-before, 后台线程的写入
            // 对主线程可见; 超时路径即使读到非 null 也属无同步的跨线程发布, 一律弃用
            val queryInTime = runCatching { ruleQueryDone.await(1, java.util.concurrent.TimeUnit.SECONDS) }
                .getOrDefault(false)
            if (!queryInTime) Logger.log("规则查询超时(>1s), 本次按无缺失继续加载")
            // 单条 IN 查询替代约 50 次逐条全表扫
            val lostList = if (queryInTime) missingRef[0] ?: emptyList() else emptyList()
            Logger.log("matchers=${matchersList.size} lost=${lostList.size} lostList=$lostList")

            // 高于已适配区间的版本：不阻断、不提示，加载仍然有效的规则
            val isTbVersionHigher = lostList.isNotEmpty() &&
                isTbSatisfyVersionRequirement(BuildConfig.TARGET_VERSION) &&
                !isTbBetweenVersionRequirement(BuildConfig.MIN_VERSION, BuildConfig.TARGET_VERSION)
            if (isTbVersionHigher) {
                XposedBridge.log(
                    "TiebaTS: 贴吧版本 %s 高于已适配版本（%s ~ %s），跳过 %d 条失效规则，其余功能正常加载".format(
                        Locale.CHINA,
                        DeobfuscationHelper.sCurrentTbVersion,
                        BuildConfig.MIN_VERSION,
                        BuildConfig.TARGET_VERSION,
                        lostList.size
                    )
                )
            }

            if (lostList.isNotEmpty() && !isTbVersionHigher && "com.baidu.tieba" == lpparam.processName) {
                // 规则缺失静默自愈(仅主进程): 模块更新引入新 matcher(或规则库损坏)时,
                // 自动触发反混淆补扫(等首页稳定后弹确认框, 用户确认后执行,
                // 完成后由用户确认重启),
                // 不弹"规则异常"打断用户。判据是"上次反混淆/完整加载时的模块版本号":
                // 与当前版本不一致即允许补扫, 反混淆完成或本版本完成完整加载后落盘
                // 版本号, 天然防循环(补扫后规则仍缺失则版本号已一致, 回落对话框);
                // 瞬时扫描失败(未落盘)时下次启动自动重试, 直至熔断。
                // 后台进程不进此分支: 补扫期间它们可能读到 dropAllRules 后的空库,
                // 且并发 putSignature(0) 会覆盖主进程扫描收尾写入的正确签名;
                // 直接落到下方正常加载, 按部分规则运行(hooker 逐规则隔离, 缺失静默降级)
                if (getDeobfsModuleId(getContext()) != currentModuleId() &&
                    !DeobfuscationHooker.isBreakerTripped(getContext())
                ) {
                    putSignature(0)
                    Logger.log("规则缺失(${lostList.size}条), 自动触发反混淆补扫: $lostList")
                    // 延迟触发补扫: 与版本变化路径一致, 等首页稳定后由
                    // DeobfuscationHooker 弹进度对话框执行
                    // 无论 hook() 成败都必须置位 sInited, 理由同版本变化路径
                    runCatching { DeobfuscationHooker(matchersList).hook() }
                        .onFailure {
                            // 同版本变化路径: 自愈入口失败必须留痕
                            XposedBridge.log("DeobfuscationHooker rescan hook failed: $it")
                            Logger.log("规则缺失补扫触发反混淆失败: ${it.javaClass.name}: ${it.message}")
                        }
                    sInited.set(true)
                    return@hookAfterMethod
                }
                // hookSafely 隔离: 该对话框钩点恰在"规则缺失"(最可能版本漂移)场景
                // 才注册, 若裸注册抛 ClassNotFound/NoSuchMethod 被外层 after 包装吞掉,
                // 其后的 initializeAdp/hide 加载/全部 hooker 注册循环都会被一并跳过
                // (sInited 保持 false, 模块整体静默失效)——单点注册失败只损失对话框。
                // compareAndSet 守卫: 初始化在对话框注册之后失败重试时重复注册会让
                // MainTabActivity.onCreate 叠加弹多个相同对话框; 注册失败不重试,
                // 失败即钩点漂移, 重试大概率同样失败
                if (sRulesDialogRegistered.compareAndSet(false, true)) hookSafely("XposedInit", "rules-incomplete-dialog") {
                    hookBeforeMethod(
                        MAIN_TAB_ACTIVITY,
                        "onCreate", Bundle::class.java
                    ) { hookParam ->
                    val activity = hookParam.thisObject as Activity
                    val messages = ArrayList<String?>().apply {
                        add(
                            if (isTbBetweenVersionRequirement(BuildConfig.MIN_VERSION, BuildConfig.TARGET_VERSION)) {
                                strings["exception_rules_incomplete"]
                            } else {
                                strings["version_mismatch"]?.format(Locale.CHINA, BuildConfig.MIN_VERSION, BuildConfig.TARGET_VERSION)
                            }
                        )
                        add("贴吧版本：%s, 模块版本：%d".format(Locale.CHINA, DeobfuscationHelper.sCurrentTbVersion, BuildConfig.VERSION_CODE))
                        add("%d rule(s) lost: %s".format(Locale.CHINA, lostList.size, lostList))
                    }
                    val message = TextUtils.join("\n", messages)
                    XposedBridge.log(message)
                    Logger.log("规则异常\n$message")

                    val alert = AlertDialog.Builder(
                        activity,
                        getDialogTheme(getContext())
                    )
                        .setTitle("规则异常")
                        .setMessage(message)
                        .setCancelable(false)
                        .setNeutralButton("关闭弹窗", null)
                        .setPositiveButton("重试并导出日志") { _, _ ->
                            // SAF 读取日志尾部有 I/O 延迟, 放后台执行, 分享面板回主线程拉起;
                            // 日志开关未开启时仍导出诊断摘要(版本/设备/缺失规则/deobfs 状态),
                            // 不为导出而绕过"开关关闭不记录"的约定
                            recordDeobfsRun(activity, "用户点重试并导出日志")
                            // 用户主动重试 = 明确要求再扫: 清零失败计数, 否则熔断
                            // (连续失败3次)未解除时重启后仍直接跳过补扫, 重试永远无效
                            DeobfuscationHelper.resetDeobfsFailCount(activity)
                            thread {
                                val report = buildString {
                                    appendLine("===== TiebaTS 反馈日志 =====")
                                    appendLine(
                                        "时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}"
                                    )
                                    appendLine("模块: ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
                                    appendLine("贴吧: ${DeobfuscationHelper.sCurrentTbVersion}")
                                    appendLine(
                                        "设备: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                                            "Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})"
                                    )
                                    appendLine("反混淆失败计数: ${DeobfuscationHelper.getDeobfsFailCount(activity)}")
                                    appendLine("缺失规则(${lostList.size}): $lostList")
                                    // deobfs 状态块: 与日志开关无关, 判断反混淆流程是否被触发过
                                    appendLine(
                                        "运行日志开关: ${if (Logger.isEnabled()) "开" else "关"}"
                                    )
                                    appendLine("deobfs_version: ${DeobfuscationHelper.getDeobfsVersion(activity)}")
                                    appendLine("预期规则条数: ${DeobfuscationHelper.getRuleCount(activity)}")
                                    appendLine("实际规则条数: ${AcRules.countAll()}")
                                    appendLine("上次反混淆事件: ${DeobfuscationHelper.getDeobfsRun(activity)}")
                                    appendLine()
                                    appendLine("===== 模块日志(尾部) =====")
                                    val logText = Logger.readLogTail()
                                    appendLine(
                                        if (logText.isNullOrBlank()) "(无: 日志开关未开启或日志文件为空)" else logText
                                    )
                                }
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "TiebaTS 反馈日志")
                                    putExtra(Intent.EXTRA_TEXT, report)
                                }
                                activity.runOnUiThread {
                                    try {
                                        activity.startActivity(Intent.createChooser(share, "导出日志"))
                                    } catch (ignored: Throwable) {
                                        // 无分享目标等场景: 静默, 仍走重试路径
                                    }
                                    // 分享面板已拉起, 延迟重启进入反混淆: 立即重启会杀进程
                                    // 导致 chooser 未及显示
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        // durable: 紧随 saveAndRestart 杀进程, 同步落盘
                                        // 保证重启后按 signature=0 进入反混淆路径
                                        putSignature(0, durable = true)
                                        saveAndRestart(activity, "unknown")
                                    }, 1500L)
                                }
                            }
                        }.create()

                    alert.show()
                    fixAlertDialogWidth(alert)
                    }
                }
                // 规则缺失不再零加载: 此前在此置位 sInited 并 return, 本轮会话除告警
                // 对话框外全部功能失效, 且若对话框钩点漂移连提示都没有(完全静默)。
                // 与后台进程路径口径一致: 按部分规则运行(hooker 逐规则隔离, 缺失静默
                // 降级), 告警对话框叠加展示。sInited 由下方正常加载路径末尾置位,
                // 同样防止下一个 Application 实例重跑导致同一 MainTab 叠加多个对话框
            }

            initializeAdp()
            Logger.log("initializeAdp done")
            if (getBoolean("hide_native")) {
                try {
                    // 统一走 XposedContext.load() 的模块路径加载("$sPath!/lib/$abi/libhide.so"):
                    // System.loadLibrary 依赖调用类的 classloader 解析库路径, LSPatch 嵌入等
                    // 环境下可能解析不到模块 so → hide 功能静默失效
                    load("hide")
                    // libhide 就位后补装崩溃捕获: 首次 install 在 Application hook 早期,
                    // LSPatch 等无 native_init 机制的环境下此时 libhide 尚未加载, 首次
                    // 必然 skipped; LSPosed 下首次已成功, 这里按幂等守卫直接返回
                    NativeCrashGuard.install(getContext())
                } catch (e: UnsatisfiedLinkError) {
                    logBoth(e)
                }
            }

            // 清空上一轮注册的失败记录: 初始化中途失败时 sInited 保持 false, 下一个
            // Application 实例会重跑整个注册流程, 不清空则旧失败条目残留, "成功=N
            // 失败=M"把已修复的 hooker 重复计入失败。注册循环在主线程单次执行,
            // 无并发清理风险
            exceptions.clear()
            val activeHookerKeys = getAll().entries
                .filter { it.value != false }
                .map { it.key }
                .toMutableSet()
                .apply {
                    add("ts_pref")
                    add("fragment_tab")
                    // 3 个文本过滤(关键词/用户 ID): 无值=未启用, 各回调判空直通零开销,
                    // 故无条件注册——首次保存设置值无需重启贴吧即可生效(值变更本就
                    // 按回调动态读取, 无需重启)
                    add("frs_page_filter")
                    add("content_filter")
                    add("user_filter")
                    // 顶部Tab数据层hooker: 隐藏/注入关注Tab与"隐藏顶部Tab"合并开关共用,
                    // 始终激活(弹窗快照也依赖 i() 钩子), 行为由合并开关控制
                    add("home_top_tab")
                    // 首页两类屏蔽(求助贴/回复贴)共用一个hooker, 任一开启即激活
                    if (getBoolean("purge_help_post") || getBoolean("purge_reply_post")) {
                        add("home_feed_filter")
                    }
                    // 顶栏隐藏游戏手柄/隐藏侧边栏/隐藏发帖(+)共用一个hooker, 任一开启即激活,
                    // 各行为由独立开关(hide_gamepad/hide_sidebar/write_thread)控制;
                    // 直播Tab视觉层隐藏也在此聚合: 12.74 及以下版本的数据层 HomeTopTab
                    // 解析失败, HomeTopBar 的视觉层隐藏是唯一生效路径, 激活条件缺直播
                    // 会导致合并开关选中"直播"但旧版本上功能无效
                    if (getBoolean("hide_gamepad") || getBoolean("hide_sidebar") ||
                        getBoolean("write_thread") || HomeTopTab.isLiveNameSelected()
                    ) {
                        add("home_top_bar")
                    }
                    // 强力去广告与云控弹窗根源屏蔽均由"广告屏蔽"(purge)开关
                    // 控制, 无独立设置项
                    if (getBoolean("purge")) {
                        add("purge_ad")
                        add("purge_yun_dialog")
                    }
                    // 隐藏首页后贴吧原生侧读不到访问足迹(进吧页"我的足迹"消失),
                    // 需要进吧页注入脚本兜底, 故 purge_enter 与 home_recommend 任一开启即激活
                    if (getBoolean("purge_enter") || getBoolean("home_recommend")) {
                        add("purge_enter")
                    }
                }

            hookers.forEach { hooker ->
                try {
                    if (activeHookerKeys.contains(hooker.key())) {
                        // 打点: 每个 hooker 的注册区间。若进程在某个 hooker 内崩溃,
                        // 原生日志里最后一条 mark 就是它——这是把"随机 native 崩溃"
                        // 收敛到具体功能点的唯一手段
                        NativeCrashGuard.mark("hooker-enter", hooker.key())
                        val startedAt = android.os.SystemClock.elapsedRealtime()
                        hooker.hook()
                        val cost = android.os.SystemClock.elapsedRealtime() - startedAt
                        Logger.logHookAudit(hooker.key(), true, "耗时 ${cost}ms")
                        NativeCrashGuard.mark("hooker-done", "${hooker.key()} ${cost}ms")
                    }
                } catch (tr: Throwable) {
                    Logger.logHookAudit(hooker.key(), false, tr.toString())
                    Logger.log(tr)
                    XposedBridge.log(tr)
                    exceptions[hooker.key()] = tr
                    NativeCrashGuard.mark("hooker-failed", "${hooker.key()} $tr")
                }
            }
            Logger.log("all hookers done, 成功=${activeHookerKeys.size - exceptions.size} 失败=${exceptions.size}")
            if (exceptions.isNotEmpty()) {
                Logger.log("失败明细: ${exceptions.entries.joinToString { "${it.key} -> ${it.value}" }}")
            }
            // 注册完成: 之后的崩溃发生在"使用中"而非"启动期", 日志里靠这一条区分
            NativeCrashGuard.mark("hookers-ready", "成功=${activeHookerKeys.size - exceptions.size}")
            // 主线程卡顿看门狗: 卡顿自动采样堆栈写入模块日志(日志开关关闭时只算不写)
            try {
                gm.tieba.tabswitch.util.JankMonitor.start()
                Logger.log("JankMonitor started")
            } catch (tr: Throwable) {
                Logger.log("JankMonitor start failed: $tr")
            }
            sInited.set(true)
        }
    }
}
