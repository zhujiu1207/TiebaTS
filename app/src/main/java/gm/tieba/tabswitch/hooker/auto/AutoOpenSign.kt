package gm.tieba.tabswitch.hooker.auto

import android.app.Activity
import android.os.Bundle
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.clearOpenSignDate
import gm.tieba.tabswitch.dao.Preferences.getIsOpenSignShown
import gm.tieba.tabswitch.dao.Preferences.putOpenSignDate
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.MAIN_TAB_ACTIVITY
import gm.tieba.tabswitch.logBoth

// 自动打开一键签到界面: 每天首次打开贴吧(MainTabActivity 冷启动)时拉起
// 进吧右上角"签"对应的 com.baidu.tieba.signall.SignAllForumActivity,
// 供不使用自动签到(API 直签)但怕忘记手动签到的用户。
// 与 AutoSign 互不影响: 本功能只负责"把界面打开", 是否签到由用户手动完成。
class AutoOpenSign : XposedContext(), IHooker {

    companion object {
        // 宿主页面配置类与目标页: 两类名均未混淆(宿主 route/注册表按名引用,
        // 多版本稳定), 反射仍全程 runCatching——漂移时本功能静默失效, 不击穿宿主
        private const val CONFIG_CLASS = "com.baidu.tbadk.core.atomData.SignAllForumActivityConfig"
        private const val SIGN_ACTIVITY_CLASS = "com.baidu.tieba.signall.SignAllForumActivity"

        // 主页 onCreate 尚未走完时立即 startActivity 会被宿主冷启动期自身的页面
        // 跳转(启动页/闪屏广告)挤占或触发其 500ms 同类页启动限流, 延后主线程打开
        private const val OPEN_DELAY_MS = 1500L
    }

    override fun key(): String {
        return "auto_open_sign"
    }

    override fun hook() {
        hookAfterMethod(
            MAIN_TAB_ACTIVITY,
            "onCreate", Bundle::class.java
        ) { param ->
            // 当日已拉起过即跳过: 标记在发起时落盘(不依赖用户是否真的完成签到),
            // Activity 重建(主题切换/授权回归)重入 onCreate 也不会二次弹页。
            // 拉起失败时清除标记回滚: 反射漂移/同类页限流类失败回滚后当天后续
            // 冷启动可重试, 不把失败静默烧满一天; 成功路径保持"发起即标记"的
            // 防重入语义(延迟窗内二次 onCreate 仍被标记拦住)
            if (getIsOpenSignShown()) return@hookAfterMethod
            val activity = param.thisObject as? Activity ?: return@hookAfterMethod
            val cal = java.util.Calendar.getInstance()
            putOpenSignDate(cal[java.util.Calendar.DAY_OF_YEAR], cal[java.util.Calendar.YEAR])
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            main.postDelayed({
                runCatching { openSignActivity(activity) }
                    .onFailure {
                        logBoth("AutoOpenSign 打开签到界面失败 -- $it")
                        clearOpenSignDate()
                    }
            }, OPEN_DELAY_MS)
        }
    }

    // 宿主拉起签到页的标准路径: IntentConfig 子类构造(内部建 Intent 并挂来源追踪)
    // + startActivity(Class)(preJump/同类页限流/setClass 后 context.startActivity)。
    // 不走 start() 的消息总线(2002001 处理器注册时机依赖页面生命周期, 冷启动早期
    // 不保证就绪), 直调 startActivity 自包含且与宿主页面管理(TAG/轨迹统计)一致
    private fun openSignActivity(activity: Activity) {
        val configClass = findClass(CONFIG_CLASS)
        // 构造参数声明类型是 Context(非 Activity), getConstructor 必须按声明类型查,
        // 否则 NoSuchMethodException(实测 22.14 已踩); 实参传 Activity 子类合法
        val config = configClass.getConstructor(android.content.Context::class.java).newInstance(activity)
        configClass
            .getMethod("startActivity", Class::class.java)
            .invoke(config, findClass(SIGN_ACTIVITY_CLASS))
        logBoth("AutoOpenSign: 已拉起一键签到界面")
    }
}
