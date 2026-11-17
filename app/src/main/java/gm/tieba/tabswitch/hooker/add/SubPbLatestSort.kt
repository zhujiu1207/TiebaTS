package gm.tieba.tabswitch.hooker.add

import android.content.Context
import android.content.ContextWrapper
import android.webkit.WebView
import android.webkit.WebViewClient
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.util.getAssetFileContent

/**
 * 楼中楼时间序更改
 *
 * 楼中楼浮层(NewSubPbActivity, H5 hybrid)原生有"热门/最早"两个排序按钮.
 * 通过向楼中楼 WebView 注入 JS: 把"热门"改名为"最早"、"最早"改名为"最晚",
 * 并拦截两个 tab 的点击, 改为对 Vue 组件的 lzlList 本地按时间排序
 * (保留首条槽位): "最早"升序、"最晚"降序(模块功能, 不走 H5 原生升序重载),
 * 瞬时生效; 开关开启后默认按最早序显示并高亮"最早".
 * 排序引发的重渲染会重建 tab 行并还原激活态, 脚本以首次改名时快照的
 * 原生激活/未激活 class 为准, 由 MutationObserver + 轮询持续对齐样式.
 */
class SubPbLatestSort : XposedContext(), IHooker {

    override fun key(): String {
        return "sub_pb_latest_sort"
    }

    override fun hook() {
        val jsScript = getAssetFileContent("SubPbLatestSort.js") ?: return
        // 在 onPageFinished 注入: onPageStarted 时新文档尚未提交,
        // evaluateJavascript 会落在旧文档上随导航丢失, 导致按钮不出现
        hookAfterMethod(
            WebViewClient::class.java,
            "onPageFinished", WebView::class.java, String::class.java
        ) { param ->
            val webView = param.args[0] as? WebView ?: return@hookAfterMethod
            // 仅楼中楼页面注入: WebView 的 context 可能被 ContextWrapper
            // 包装(hybrid 容器常用 MutableContextWrapper), 需遍历基链判断
            var ctx: Context? = webView.context
            var match = false
            while (ctx != null) {
                if (ctx.javaClass.name == "com.baidu.tieba.pb.pb.sub.NewSubPbActivity") {
                    match = true
                    break
                }
                ctx = (ctx as? ContextWrapper)?.baseContext
            }
            if (match) {
                webView.evaluateJavascript(jsScript, null)
            }
        }
    }
}
