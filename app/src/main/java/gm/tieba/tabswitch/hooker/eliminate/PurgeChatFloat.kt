package gm.tieba.tabswitch.hooker.eliminate

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XC_MethodHook.XC_MethodReplacement
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.logBoth

/**
 * 屏蔽消息页群聊横幅（key=purge_chat_float）：群助手@我时消息页顶部弹出的悬浮横幅
 * （群头像+"xx群 群助手@了你"+回复按钮，22.x 新增）。
 *
 * 22.11 实测定位（诊断版调用栈）：横幅由通用弹窗框架(BaseCommonStrategy)构建——
 * es6.i(TopNotifyData, kp8$e) 组装 qr6$h 配置并 new qr6(...)，构造器把横幅根视图
 * addContentView 到 Activity content（与 setContentView 的 DrawerLayout 平级），
 * 随后 qr6.t() 显示。两处调用方（sf6.c / z87.a）均不使用返回值，因此把 es6.i
 * 替换为返回 null 即可从源头阻断，横幅完全不会创建，无可见瞬间。
 * 旧方案（addView 后按"@了你"文本隐藏）横幅会闪现最长 1s：文本在 addView 之后
 * 才绑定，最早也要 300ms 复查才能命中。
 *
 * es6/qr6 为混淆类，按"参数首类型为 TopNotifyData 的两参方法"锚定查找；
 * 找不到（版本混淆名变化）时退回 content.addView 通用屏蔽兜底：检查新视图子树
 * 是否含"@了你"文本，命中即 GONE 并保持（延迟 300ms/1000ms 复查晚绑定文本）。
 */
class PurgeChatFloat : XposedContext(), IHooker {

    companion object {
        private const val TAG = "TSDiag"
        private const val TOP_NOTIFY_DATA = "com.baidu.tbadk.data.TopNotifyData"
        private const val BANNER_TEXT_MARK = "@了你"
        private val RESCAN_DELAYS_MS = longArrayOf(300, 1000)

        // 复用主线程 Handler: 此前每次 addView 回调新建两个, 大布局页面开销可观
        private val sMainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        private fun diag(msg: String) {
            XposedBridge.log("[$TAG] $msg")
            android.util.Log.e(TAG, msg)
        }

        private fun subtreeHasBannerText(view: View): Boolean {
            if (view is TextView) {
                val text = view.text?.toString() ?: return false
                if (text.contains(BANNER_TEXT_MARK)) return true
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    if (subtreeHasBannerText(view.getChildAt(i))) return true
                }
            }
            return false
        }
    }

    override fun key(): String {
        return "purge_chat_float"
    }

    override fun hook() {
        if (!hookTopNotifyFactory()) {
            diag("精准拦截未生效, 启用 content.addView 通用兜底")
            hookContentViewFallback()
        }
    }

    // 精准拦截: 工厂类中参数首类型为 TopNotifyData 的两参方法 → 返回 null。
    // 工厂类名随宿主版本混淆轮换, 逐候选锚定: 22.12.1.0 实测工厂为 es6
    // (i(TopNotifyData, kp8.e)→qr6 原样存在), 22.13.1.1 轮换为 vs6
    // (与 22.12 的 ls6 同构, i(TopNotifyData, ir8.e)→hs6), 22.14.1.0 轮换为
    // ws6(与 22.13 的 vs6 同构, i(TopNotifyData, jr8.e)→is6); 排在后面的旧名
    // 形状校验不通过自动跳过, 仅多一条启动 diag
    private fun hookTopNotifyFactory(): Boolean {
        for (factoryName in listOf(
            "com.baidu.tieba.ws6", "com.baidu.tieba.vs6",
            "com.baidu.tieba.ls6", "com.baidu.tieba.es6"
        )) {
            val hooked = try {
                val factoryClass = findClass(factoryName)
                val topNotifyDataClass = findClass(TOP_NOTIFY_DATA)
                val candidates = factoryClass.declaredMethods.filter {
                    it.parameterTypes.size == 2 && it.parameterTypes[0] == topNotifyDataClass &&
                        (it.returnType == Void.TYPE || !it.returnType.isPrimitive)
                }
                if (candidates.size != 1) {
                    diag("$factoryName 工厂方法候选=${candidates.size}, 尝试下一候选")
                    false
                } else {
                    val method = candidates[0]
                    XposedBridge.hookMethod(method, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? = null
                    })
                    diag(
                        "已拦截横幅工厂 $factoryName.${method.name}" +
                            "(${method.parameterTypes.joinToString(",") { it.simpleName }}) → null"
                    )
                    true
                }
            } catch (t: Throwable) {
                diag("横幅工厂查找失败($factoryName): $t")
                false
            }
            if (hooked) return true
        }
        return false
    }

    // 通用兜底: 加进 Activity content 的视图含横幅特征文本则隐藏并保持
    private fun hookContentViewFallback() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // 全 app 每次 addView 都进此回调: 就地包裹避免反射异常刷 LSPosed 日志
                runCatching {
                    val parent = param.thisObject as? ViewGroup ?: return@runCatching
                    if (parent.id != android.R.id.content) return@runCatching
                    val child = param.args.getOrNull(0) as? View ?: return@runCatching
                    onContentChildAdded(child)
                }.onFailure {
                    logBoth("PurgeChatFloat addView 兜底异常: $it")
                }
            }
        }
        val viewGroupClass = ViewGroup::class.java
        val overloads = listOf(
            arrayOf(View::class.java),
            arrayOf(View::class.java, Int::class.javaPrimitiveType),
            arrayOf(View::class.java, ViewGroup.LayoutParams::class.java),
            // 终端实现: 前三个重载全部委托给它, 应用直接调 3 参形式的调用
            // 完全绕过上面三个 hook(Xposed 只 hook 被调用的具体实现)
            arrayOf(
                View::class.java, Int::class.javaPrimitiveType,
                ViewGroup.LayoutParams::class.java
            ),
        )
        for (types in overloads) {
            try {
                val method = viewGroupClass.getMethod("addView", *types)
                XposedBridge.hookMethod(method, hook)
            } catch (t: Throwable) {
                diag("挂ViewGroup.addView(${types.joinToString(",") { it?.simpleName ?: "?" }}) 失败: $t")
            }
        }
    }

    private fun onContentChildAdded(child: View) {
        if (subtreeHasBannerText(child)) {
            diag("横幅特征命中(同步) → 隐藏")
            hideBanner(child)
            return
        }
        for (delay in RESCAN_DELAYS_MS) {
            sMainHandler.postDelayed({
                // 延迟复查时 child 可能已 detach: 跳过遍历, 避免开屏期无效全树扫描
                if (child.isAttachedToWindow && child.visibility != View.GONE &&
                    subtreeHasBannerText(child)
                ) {
                    diag("横幅特征命中(延迟${delay}ms) → 隐藏")
                    hideBanner(child)
                }
            }, delay)
        }
    }

    // 隐藏横幅根视图并用布局监听保持 GONE(防止其他链路改回可见)
    private fun hideBanner(view: View) {
        view.visibility = View.GONE
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.visibility != View.GONE && subtreeHasBannerText(v)) {
                v.visibility = View.GONE
            }
        }
    }
}
