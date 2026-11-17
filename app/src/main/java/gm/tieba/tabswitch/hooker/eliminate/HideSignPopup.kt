package gm.tieba.tabswitch.hooker.eliminate

import android.content.Intent
import android.os.Bundle
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.logBoth

/**
 * 隐藏吧页面签到弹窗：吧页面/关注页签到成功后的"连续签到 X 天/今日签到第 N 名"结果弹窗
 * 是一个透明 H5 容器页(TBWebContainerActivity, url 路径含 /signPopup/, 由签到 H5 在签到
 * 成功后调起, pageData.popupType=3), 不经过任何原生 Dialog。签到请求在该弹窗打开前已完成,
 * 因此拦截该 Activity 的启动即可, 不影响签到本身。
 *
 * 启动路径不固定(Activity.startActivityForResult / 非 Activity Context 直接 start 等均有),
 * 故同时在 Instrumentation.execStartActivity(所有 start 的汇聚点)拦截; 并兜底: 弹窗仍被
 * 拉起时在其 onResume 立即 finish。
 */
class HideSignPopup : XposedContext(), IHooker, Obfuscated {

    override fun key(): String {
        return "hide_sign_popup"
    }

    override fun matchers(): List<Matcher> {
        return listOf<Matcher>()
    }

    private fun isSignPopupIntent(intent: Intent?): Boolean {
        if (intent?.component?.className != "com.baidu.tbadk.browser.TBWebContainerActivity") {
            return false
        }
        val url = intent.getStringExtra("tag_url") ?: return false
        // signPopup: "连续签到 X 天/去补签"结果弹窗; ruleDialog: "签到成功+打卡任务"弹窗
        return url.contains("/signPopup/") || url.contains("/ruleDialog/")
    }

    override fun hook() {
        // 逐点隔离对齐本文件其余两个拦截点: 此点若漂移抛异常且未隔离,
        // 会被 hooker 级 catch 接住导致后两个拦截点一并失去注册机会
        runCatching {
            hookBeforeMethod(
                "android.app.Activity",
                "startActivityForResult",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java
            ) { param ->
                if (isSignPopupIntent(param.args[0] as? Intent)) {
                    logBoth("HideSignPopup: 经 startActivityForResult 拦截")
                    param.setResult(null)
                }
            }
        }

        // execStartActivity 自 API 14 起就是统一的 7 参签名(所有 Activity start 的
        // 汇聚点, referrer 变化在 ActivityThread 层而非此公开签名), 精确匹配足以
        // 覆盖全部启动路径。原"失败时 hookAllMethods 兜底"分支的门控恒为假, 属
        // 死代码, 已删——真失败时靠下方 onResume 兜底拦截, 并在此留痕提示漂移
        runCatching {
            hookBeforeMethod(
                "android.app.Instrumentation",
                "execStartActivity",
                android.content.Context::class.java,
                android.os.IBinder::class.java,
                android.os.IBinder::class.java,
                android.app.Activity::class.java,
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java
            ) { param ->
                if (isSignPopupIntent(param.args[4] as? Intent)) {
                    logBoth("HideSignPopup: 经 Instrumentation.execStartActivity 拦截")
                    param.setResult(null)
                }
            }
        }.onFailure {
            logBoth("HideSignPopup: execStartActivity 精确 hook 失败(签名漂移?), 仅剩 onResume 兜底 -- ${it.javaClass.simpleName}")
        }

        // 兜底: 弹窗仍被拉起时, 进入前台立刻关闭
        runCatching {
            val webContainerClass = XposedHelpers.findClassIfExists(
                "com.baidu.tbadk.browser.TBWebContainerActivity", sClassLoader
            )
            webContainerClass?.let {
            hookAfterMethod(it, "onResume") { param ->
                val activity = param.thisObject as? android.app.Activity ?: return@hookAfterMethod
                val url = activity.intent?.getStringExtra("tag_url")
                // 拦截条件与 hook() 的 isSignPopupIntent 保持一致(signPopup + ruleDialog),
                // 否则 ruleDialog 弹窗被放行后兜底认不出、无法关闭
                if (url != null && (url.contains("/signPopup/") || url.contains("/ruleDialog/"))) {
                    logBoth("HideSignPopup: 兜底 finish signPopup 弹窗")
                    activity.finish()
                }
            }
            }
        }.onFailure {
            logBoth("HideSignPopup: hook TBWebContainerActivity 失败 -- ${it.javaClass.simpleName}")
        }
    }
}
