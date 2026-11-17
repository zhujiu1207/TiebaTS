package gm.tieba.tabswitch.hooker.auto

import android.app.Activity
import android.os.Build
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.isTbSatisfyVersionRequirement

@Suppress("DEPRECATION")
class TransitionAnimation : XposedContext(), IHooker {

    private var CHAT_SQUARE_FADE_IN = 0
    private var CHAT_SQUARE_FADE_OUT = 0
    private var RES_BIG_IMAGE_IN_FROM_RIGHT = 0
    private var RES_BIG_IMAGE_OUT_TO_RIGHT = 0
    private var RES_CUSTOM_FADE_IN = 0
    private var RES_CUSTOM_FADE_OUT = 0
    private var RES_CUSTOM_IN_FROM_RIGHT = 0
    private var RES_CUSTOM_OUT_TO_RIGHT = 0
    private var RES_FADE_OUT = 0
    private var RES_NFADE_IN = 0
    private var RES_NORMAL_IN_FROM_BOTTOM = 0
    private var RES_NORMAL_IN_FROM_LEFT = 0
    private var RES_NORMAL_IN_FROM_RIGHT = 0
    private var RES_NORMAL_OUT_TO_BOTTOM = 0
    private var RES_NORMAL_OUT_TO_LEFT = 0
    private var RES_NORMAL_OUT_TO_RIGHT = 0
    private lateinit var activityPendingTransitionFactory: Class<*>

    override fun key(): String {
        return "transition_animation"
    }

    override fun hook() {
        if (!(Build.VERSION.SDK_INT >= 34 && isTbSatisfyVersionRequirement("12.58.2.1"))) {
            return
        }

        // 注册期整体隔离: 16 个静态字段逐一读取无逐项兜底, 宿主任一常量漂移抛出的
        // NoSuchFieldError 会冲出 hook() 使整个 hooker 注册失败; 与 replace 回调的
        // 三重防护对齐, 失败只记日志早退, 不向外抛
        runCatching {
            activityPendingTransitionFactory =
                findClass("com.baidu.tbadk.ActivityPendingTransitionFactory")

            CHAT_SQUARE_FADE_IN = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "CHAT_SQUARE_FADE_IN")
            CHAT_SQUARE_FADE_OUT = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "CHAT_SQUARE_FADE_OUT")
            RES_BIG_IMAGE_IN_FROM_RIGHT = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_BIG_IMAGE_IN_FROM_RIGHT")
            RES_BIG_IMAGE_OUT_TO_RIGHT = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_BIG_IMAGE_OUT_TO_RIGHT")
            RES_CUSTOM_FADE_IN = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_CUSTOM_FADE_IN")
            RES_CUSTOM_FADE_OUT = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_CUSTOM_FADE_OUT")
            RES_CUSTOM_IN_FROM_RIGHT = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_CUSTOM_IN_FROM_RIGHT")
            RES_CUSTOM_OUT_TO_RIGHT = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_CUSTOM_OUT_TO_RIGHT")
            RES_FADE_OUT = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_FADE_OUT")
            RES_NFADE_IN = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_NFADE_IN")
            RES_NORMAL_IN_FROM_BOTTOM = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_NORMAL_IN_FROM_BOTTOM")
            RES_NORMAL_IN_FROM_LEFT = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_NORMAL_IN_FROM_LEFT")
            RES_NORMAL_IN_FROM_RIGHT = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_NORMAL_IN_FROM_RIGHT")
            RES_NORMAL_OUT_TO_BOTTOM = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_NORMAL_OUT_TO_BOTTOM")
            RES_NORMAL_OUT_TO_LEFT = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_NORMAL_OUT_TO_LEFT")
            RES_NORMAL_OUT_TO_RIGHT = XposedHelpers.getStaticIntField(activityPendingTransitionFactory, "RES_NORMAL_OUT_TO_RIGHT")
        }.onFailure {
            gm.tieba.tabswitch.util.Logger.log("TransitionAnimation 常量读取失败, 功能整体停用: $it")
            return
        }

        // replace 回调异常会经 XposedContext 重抛直达宿主(每次页面切换都是触发点),
        // 反射失败(宿主版本漂移)必须兜底为调用原方法, 避免贴吧闪退循环
        hookReplaceMethod(
            activityPendingTransitionFactory,
            "enterExitAnimation",
            "com.baidu.tbadk.TbPageContext", Int::class.javaPrimitiveType
        ) { param ->
            runCatching { enterExitAnimation(param.args[0], param.args[1] as Int) }
                .getOrElse { invokeOriginalOrNoAnimation(param) }
        }

        hookReplaceMethod(
            activityPendingTransitionFactory,
            "closeAnimation",
            "com.baidu.tbadk.TbPageContext", Int::class.javaPrimitiveType
        ) { param ->
            runCatching { closeAnimation(param.args[0], param.args[1] as Int) }
                .getOrElse { invokeOriginalOrNoAnimation(param) }
        }
    }

    // 二道防线: 原方法回退调用也可能抛(反射/原方法内部异常), 再失败时按返回
    // 类型返回安全零值(void 即 null, 不设置自定义动画, 走系统默认转场),
    // 绝不让异常沿 replace 语义重抛直达宿主; 仅首次落日志避免每次切页刷屏
    private fun invokeOriginalOrNoAnimation(param: XC_MethodHook.MethodHookParam): Any? {
        return try {
            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
        } catch (tr: Throwable) {
            if (sDoubleFallbackLogged.compareAndSet(false, true)) {
                gm.tieba.tabswitch.util.Logger.log("TransitionAnimation 原方法回退失败, 降级为默认转场: $tr")
            }
            safeConstantFor((param.method as java.lang.reflect.Method).returnType, 0, false)
        }
    }

    private fun enterExitAnimation(tbPageContext: Any, i: Int) {
        var animationType = i
        // getPageActivity 在页面切换/销毁窗口期可为 null, 硬 as 会经 replace 重抛直达宿主
        val pageActivity = XposedHelpers.callMethod(tbPageContext, "getPageActivity") as? Activity
            ?: return
        if (XposedHelpers.getStaticBooleanField(activityPendingTransitionFactory, "IS_CUSTOM_FROM_THIRD_PARTY")) {
            animationType = 3
        }
        when (animationType) {
            0 -> pageActivity.overridePendingTransition(0, 0)
            1 -> pageActivity.overridePendingTransition(RES_NORMAL_IN_FROM_RIGHT, RES_FADE_OUT)
            2 -> pageActivity.overridePendingTransition(RES_BIG_IMAGE_IN_FROM_RIGHT, RES_FADE_OUT)
            3 -> pageActivity.overridePendingTransition(RES_CUSTOM_IN_FROM_RIGHT, RES_CUSTOM_FADE_OUT)
            4 -> pageActivity.overridePendingTransition(RES_NORMAL_IN_FROM_BOTTOM, RES_FADE_OUT)
            5 -> pageActivity.overridePendingTransition(CHAT_SQUARE_FADE_IN, CHAT_SQUARE_FADE_OUT)
            6 -> pageActivity.overridePendingTransition(RES_NORMAL_IN_FROM_LEFT, RES_FADE_OUT)
            else -> pageActivity.overridePendingTransition(RES_NORMAL_IN_FROM_RIGHT, RES_FADE_OUT)
        }
    }

    private fun closeAnimation(tbPageContext: Any, i: Int) {
        var animationType = i
        val pageActivity = XposedHelpers.callMethod(tbPageContext, "getPageActivity") as? Activity
            ?: return
        if (XposedHelpers.getStaticBooleanField(activityPendingTransitionFactory, "IS_CUSTOM_FROM_THIRD_PARTY")) {
            animationType = 3
        }
        when (animationType) {
            0 -> pageActivity.overridePendingTransition(0, 0)
            1 -> pageActivity.overridePendingTransition(RES_NFADE_IN, RES_NORMAL_OUT_TO_RIGHT)
            2 -> pageActivity.overridePendingTransition(RES_NFADE_IN, RES_BIG_IMAGE_OUT_TO_RIGHT)
            3 -> pageActivity.overridePendingTransition(RES_CUSTOM_FADE_IN, RES_CUSTOM_OUT_TO_RIGHT)
            4 -> pageActivity.overridePendingTransition(RES_NFADE_IN, RES_NORMAL_OUT_TO_BOTTOM)
            5 -> pageActivity.overridePendingTransition(CHAT_SQUARE_FADE_IN, CHAT_SQUARE_FADE_OUT)
            6 -> pageActivity.overridePendingTransition(RES_NFADE_IN, RES_NORMAL_OUT_TO_LEFT)
            else -> pageActivity.overridePendingTransition(RES_NFADE_IN, RES_NORMAL_OUT_TO_RIGHT)
        }
    }

    companion object {
        // 二道防线降级只记一次日志(每次切页都触发, 逐次落盘会刷屏)
        private val sDoubleFallbackLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
