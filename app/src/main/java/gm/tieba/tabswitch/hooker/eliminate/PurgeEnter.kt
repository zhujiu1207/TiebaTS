package gm.tieba.tabswitch.hooker.eliminate

import android.view.View
import android.view.ViewGroup
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.bridge.XposedHelpers.ClassNotFoundError
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.util.findFirstMethodByExactReturnType
import gm.tieba.tabswitch.util.getAssetFileContent

class PurgeEnter : XposedContext(), IHooker, Obfuscated {

    private val mHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var mPollRunnable: Runnable? = null

    override fun key(): String {
        return "purge_enter"
    }

    override fun matchers(): List<Matcher> {
        // 22.11 移除了 enterForum.recforum 推荐体系和 enter_forum_login_tip 字符串，规则不再可用
        return emptyList()
    }

    override fun hook() {
        try {   // 12.56.4.0+ 禁用WebView进吧页 (包名为 enterForum, 注意大小写)
            hookReplaceMethod(
                findFirstMethodByExactReturnType(
                    "com.baidu.tieba.enterForum.helper.HybridEnterForumHelper",
                    Boolean::class.javaPrimitiveType!!
                )
            ) { false }
            // 捕 Throwable 而非仅 ClassNotFound/NoSuchMethod: 反射框架抛出的其它
            // RuntimeException 会中断其后 purgeEnterWebView 的注册(单点漂移
            // 不得拖垮整个 hooker, 理由同 HomeTopBar 开头的注册隔离注释)
        } catch (tr: Throwable) {
            // 失败留痕: "hook 注册成功: purge_enter" 只代表 hook() 未抛异常,
            // 内部任一钩点静默漂移时无日志可查(2026-11-24 真机 22.14 净化失效
            // 排查困境的直接教训)。限频由 XposedContext.logHookError 统一处理
            logBoth("PurgeEnter: HybridEnterForumHelper 禁用注册失败 -- $tr")
        }

        try {   // 22.11+ 进吧页整体迁移为WebView(HomeBottomWebFragment), 注入JS净化H5页面
            // (22.14 形状核实: onPrimary 为类上自有声明 enterforum/home/
            // EnterForumTabFragment.java:381, 非混淆名, 四版本同名)
            if (XposedHelpers.findClassIfExists(
                    "com.baidu.tieba.enterforum.home.EnterForumTabFragment", sClassLoader
                ) != null
            ) {
                purgeEnterWebView()
            } else {
                // 静默漂移会让"hook 注册成功"表象下净化整体无生效路径, 留痕
                logBoth("PurgeEnter: EnterForumTabFragment 类不存在(包名/混淆漂移), H5 净化放弃")
            }
        } catch (tr: Throwable) {
            logBoth("PurgeEnter: H5 净化注册失败 -- $tr")
        }
    }

    // 22.11+: 进吧Tab渲染于TbWebView, 页面加载完成后注入脚本净化。
    // H5加载时机不定且久不进Tab后贴吧会重载H5(净化脚本随旧文档消失,
    // 且文档更换后 observer 失效), 故注入不能是一次性短窗口: onPrimary后按秒
    // 持续轮询注入, 脚本幂等(动作扫描每轮执行, 标记只拦 observer 注册),
    // 页面任何时刻重载完成都会在下一秒被重新驱动。
    // 只收集Fragment自身视图子树内的WebView并要求isShown, 不影响其它Tab;
    // Fragment视图不可见(切走)即休眠, onPrimary/应用恢复回进吧时重新拉起。
    private fun purgeEnterWebView() {
        val enterFragmentClass = findClass("com.baidu.tieba.enterforum.home.EnterForumTabFragment")
        // 隐藏首页后原生读不到访问足迹(脚本说明见 companion 注释), 进吧页注入兜底脚本。
        // 两个脚本逐字迁自本文件原内嵌常量至 assets(2026-11-17)。与旧内嵌常量的
        // 行为差异: assets 读取失败(模块路径异常等极端场景)时 purgeScript 为 null,
        // 此处留痕后 return——整个 WebView 净化不注册(旧常量恒在, 必然注册);
        // footFix 仍按 null 跳过对应注入, 两者降级口径不同是有意的: 净化脚本缺失
        // 时轮询与注入机制整体失去意义, 不值得为半失效状态空转
        val purgeScript = getAssetFileContent("PurgeEnter.js")
        if (purgeScript == null) {
            logBoth("PurgeEnter: PurgeEnter.js assets 读取失败, H5 净化放弃(assets 路径异常?)")
            return
        }
        val footFixScript = if (getBoolean("home_recommend")) {
            getAssetFileContent("PurgeEnterFootFix.js")
        } else null
        fun startPolling(fragment: Any) {
            mPollRunnable?.let { mHandler.removeCallbacks(it) }
            val poll = object : Runnable {
                private var attempts = 0
                // 连续不可见计数: 切走/退出页面后立即停止轮询, 避免每秒空转
                // 最长 1 小时并强持有 fragment(下次 onPrimary/onResume 会重新拉起)
                private var invisibleStreak = 0
                override fun run() {
                    attempts++
                    var viewVisible = false
                    try {
                        val view = XposedHelpers.callMethod(fragment, "getView") as? View
                        if (view != null) {
                            viewVisible = view.isShown
                            if (viewVisible) {
                                val webs = ArrayList<android.webkit.WebView>()
                                collectWebViews(view, webs)
                                for (web in webs) {
                                    if (!web.isShown) continue
                                    injectInto(web, purgeScript, footFixScript)
                                }
                            }
                        }
                    } catch (ignored: Throwable) {
                    }
                    if (viewVisible) invisibleStreak = 0 else invisibleStreak++
                    // 进吧Tab可见期间持续补注入; 连续不可见或超长兜底上限后停止,
                    // 下次 onPrimary/恢复 会重新拉起
                    if (invisibleStreak < MAX_INVISIBLE_STREAK && attempts < MAX_POLL_ATTEMPTS) {
                        mHandler.postDelayed(this, POLL_INTERVAL_MS)
                    } else if (mPollRunnable === this) {
                        // 自停时解除自引用: 停止后的 Runnable 仍强持有 fragment 及其
                        // 视图树, hooker 实例进程级存活会让该 Fragment 永久无法回收
                        mPollRunnable = null
                    }
                }
            }
            mPollRunnable = poll
            mHandler.postDelayed(poll, FIRST_POLL_DELAY_MS)
        }
        // EnterForumTabFragment 未重写 onResume, 用其声明的 onPrimary(Tab选中时触发)。
        // 独立隔离: onPrimary 被混淆改名时不应连带放弃下方 onResume 兜底
        try {
            hookAfterMethod(enterFragmentClass, "onPrimary") { param ->
                startPolling(param.thisObject)
            }
        } catch (tr: Throwable) {
            logBoth("PurgeEnter: onPrimary 注册失败 -- $tr")
        }
        // 应用切后台再回来时若停在进吧Tab, onPrimary不会重触发, 用基类 onResume 兜底
        try {
            hookAfterMethod("androidx.fragment.app.Fragment", "onResume") { param ->
                if (enterFragmentClass.isInstance(param.thisObject)) {
                    startPolling(param.thisObject)
                }
            }
        } catch (tr: Throwable) {
            logBoth("PurgeEnter: onResume 兜底注册失败 -- $tr")
        }
    }

    private fun injectInto(web: android.webkit.WebView, purge: String, footFix: String?) {
        // 回调=true(页面已带标记)不再进入复验降频: 2026-11-24 真机实锤——标记在但
        // 内容文档可能已更换(observer 挂在旧 document 上), 每轮重复 evaluate 才
        // 能在新文档上重跑动作扫描(脚本幂等且短路范围已收窄, 见 PurgeEnter.js
        // 文件头注释)。代价是每秒一次跨 JavaBridge 的脚本编译执行, 可接受
        footFix?.let { web.evaluateJavascript(it, null) }
        web.evaluateJavascript(purge, null)
    }

    private fun collectWebViews(view: View, out: MutableList<android.webkit.WebView>) {
        if (view is android.webkit.WebView) {
            out.add(view)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectWebViews(view.getChildAt(i), out)
            }
        }
    }

    companion object {
        private const val FIRST_POLL_DELAY_MS = 100L
        private const val POLL_INTERVAL_MS = 1000L
        // 兜底上限: 1小时后停止, 正常由 Tab切走休眠/onPrimary 重新拉起
        private const val MAX_POLL_ATTEMPTS = 3600
        // 连续 15 秒不可见即停止轮询(防 fragment 强引用长持 + 每秒空转)
        private const val MAX_INVISIBLE_STREAK = 15

        // 进吧页 H5 净化脚本已迁至 assets/PurgeEnter.js、足迹兜底脚本迁至
        // assets/PurgeEnterFootFix.js(2026-11-17, 行为不变):
        // PurgeEnter.js —— 进吧页 H5 内容(吧单横幅/标签均为图片素材, 无法按文案
        // 匹配), 分三步执行: 步骤1: "一键签到"→白底灰圆环深色字的圆形小图标"签",
        // 保留在搜索栏右侧原位; 步骤2: 文本精确为"展开"的按钮(首次加载/下拉刷新后)
        // 自动点击展开; 步骤3: 列表展开完成(forum-list 带 unfold 类且高度>500)并
        // 稳定200ms后, 以"我关注的吧"区块为锚点隐藏其后兄弟区块(吧单来袭等),
        // 并按文案隐藏"创建吧"单元格(40~400px)。收起状态下屏蔽会破坏H5展开时的
        // 高度计算导致整页塌陷白屏, 故必须门控。关注吧不足一屏时列表默认全显、
        // 无展开键, 走"无展开/收起键且 forum-list 可见"的旁路直接屏蔽(同样稳定200ms)。
        // 脚本幂等: 动作扫描(改写/隐藏均为幂等操作)每次注入都执行; window 标记
        // 只短路 observer/心跳注册防重复挂载(2026-11-24 短路收窄, 见 JS 文件头)。
        // PurgeEnterFootFix.js —— 隐藏首页修复: 隐藏底栏"首页"Tab后, 贴吧原生侧
        // 进吧H5读取本机访问足迹的 JSAPI(system.getHistoryForumData)返回空, H5以
        // 空列表(history=[])请求 /c/f/forum/gethistoryforum, 服务端不返回足迹数据,
        // "我的足迹"整块消失。原生读写存储分离且写入不受影响, 无从原生侧修复,
        // 只能在H5层兜底: 1. 用localStorage做足迹备份: 从forumTab.forumData缓存或
        // __tsFootBackup取最近一次非空足迹列表; 2. 拦截XHR, 请求体中history=[]时
        // 改写为备份列表, 服务端即可正常返回; 3. 拦截localStorage写入, 空结果不许
        // 覆盖forumTab.forumData中的非空足迹。开启隐藏首页前若从未显示过足迹
        // (无备份可取), 无法凭空补数据, 维持原样。
    }
}
