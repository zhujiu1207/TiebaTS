package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.logBoth
import java.lang.reflect.Method

class RedTip : XposedContext(), IHooker {

    override fun key(): String {
        return "red_tip"
    }

    // 替换值按方法实际返回类型派发安全零值: 这些 setter 当前为 void, 但宿主
    // 版本漂移(重载增删/返回类型变化)时硬编码 null 会在宿主调用点拆箱 NPE,
    // replace 路径模块 wrapper 捕不到; void/引用类型返回 null 与原行为一致
    private fun safeNoOp(param: XC_MethodHook.MethodHookParam): Any? =
        safeConstantFor((param.method as Method).returnType, 0, false)

    override fun hook() {
        // 逐点隔离: 单个类/方法随宿主版本缺失时, 只降级该点, 不让整个 hooker 失效;
        // 失败留痕对齐"底栏红点"分支——静默吞掉会让漂移只能靠"功能没生效"倒查
        runCatching {
            hookReplaceMethod(
                "com.baidu.tieba.homepage.framework.indicator.PagerSlidingTabStrip",
                "setShowConcernRedTip", Boolean::class.javaPrimitiveType
            ) { param -> safeNoOp(param) }
        }.onFailure { logBoth("RedTip: PagerSlidingTabStrip.setShowConcernRedTip 注册失败 -- $it") }
        runCatching {
            hookReplaceMethod(
                "com.baidu.tieba.homepage.framework.indicator.PagerSlidingTabStrip",
                "setShowHotTopicRedTip", Boolean::class.javaPrimitiveType
            ) { param -> safeNoOp(param) }
        }.onFailure { logBoth("RedTip: PagerSlidingTabStrip.setShowHotTopicRedTip 注册失败 -- $it") }
        runCatching {
            hookReplaceMethod(
                "com.baidu.tieba.homepage.framework.indicator.ScrollFragmentTabHost",
                "setShowConcernRedTip", Boolean::class.javaPrimitiveType
            ) { param -> safeNoOp(param) }
        }.onFailure { logBoth("RedTip: ScrollFragmentTabHost.setShowConcernRedTip 注册失败 -- $it") }
        runCatching {
            hookReplaceMethod(
                "com.baidu.tieba.homepage.personalize.view.HomeTabBarView",
                "setShowConcernRedTip", Boolean::class.javaPrimitiveType
            ) { param -> safeNoOp(param) }
        }.onFailure { logBoth("RedTip: HomeTabBarView.setShowConcernRedTip 注册失败 -- $it") }

        //底栏红点
        runCatching {
            hookReplaceMethod(
                "com.baidu.tbadk.core.view.MessageRedDotView",
                "onChangeSkinType"
            ) { param -> safeNoOp(param) }
        }.onFailure {
            runCatching {
                hookReplaceMethod(
                    "com.baidu.tbadk.core.view.MessageRedDotView",
                    "e"
                ) { param -> safeNoOp(param) }
            }
        }
    }
}
