package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.XposedContext.Companion.hookBeforeMethod
import gm.tieba.tabswitch.XposedContext.Companion.hookReplaceMethod
import gm.tieba.tabswitch.XposedContext.Companion.safeConstantReplacement
import gm.tieba.tabswitch.bridge.XposedBridge
import java.lang.reflect.Modifier

// 广告策略层 + 广告 SDK 初始化。从 PurgeAd 原文搬移（逐字保留注释）。

internal object PurgeAdStrategy {

    internal fun blockAdStrategy() {
        // 伪装会员免广告状态(参照 ForbidAd4TieBa StrategyAdHook.hookStatic)。
        // ⚠️ 已知取舍: hookAllMethods 对 AccountData 全部调用点生效, 宿主的会员权益
        // 展示/上报会读到假等级, 与服务端会员状态不符存在被风控标记的理论风险——
        // 这是"广告屏蔽"的有意设计(移植自上游), 不做调用方过滤
        try {
            val accountClass = findClass(PurgeAdKeys.ACCOUNT_DATA)
            // 按命中方法实际返回类型派发常量(而非固定装箱值): 同名方法出现返回类型
            // 漂移/新重载时, 固定 Integer/Boolean 会在宿主拆箱处崩溃且 hook 层兜不住
            XposedBridge.hookAllMethods(accountClass, "getMemberCloseAdVipClose", safeConstantReplacement(1, true))
            XposedBridge.hookAllMethods(accountClass, "isMemberCloseAdVipClose", safeConstantReplacement(1, true))
            XposedBridge.hookAllMethods(accountClass, "getMemberCloseAdIsOpen", safeConstantReplacement(1, true))
            XposedBridge.hookAllMethods(accountClass, "isMemberCloseAdIsOpen", safeConstantReplacement(1, true))
            XposedBridge.hookAllMethods(accountClass, "getMemberType", safeConstantReplacement(2, true))
        } catch (ignored: Throwable) {
        }
        // 广告/SDK 开关查询一律返回 0(关闭)
        try {
            hookBeforeMethod(PurgeAdKeys.SWITCH_MANAGER, "findType", String::class.java) { param ->
                if (PurgeAdKeys.BLOCKED_SWITCH_KEYS.contains(param.args[0] as? String)) {
                    param.setResult(0)
                }
            }
        } catch (ignored: Throwable) {
        }
        // 允许关闭开屏广告: SplashForbidAdHelperKt 的静态布尔方法恒为 true
        try {
            findClass(PurgeAdKeys.SPLASH_FORBID_HELPER).declaredMethods.filter { md ->
                Modifier.isStatic(md.modifiers) &&
                    md.parameterTypes.isEmpty() &&
                    md.returnType == Boolean::class.javaPrimitiveType
            }.forEach { md ->
                runCatching { hookReplaceMethod(md) { true } }
            }
        } catch (ignored: Throwable) {
        }
        // 关闭广告数据恒为"可关闭": CloseAdData 的无参 int 方法恒为 1
        try {
            findClass(PurgeAdKeys.CLOSE_AD_DATA).declaredMethods.filter { md ->
                !Modifier.isStatic(md.modifiers) &&
                    md.parameterTypes.isEmpty() &&
                    md.returnType == Int::class.javaPrimitiveType
            }.forEach { md ->
                runCatching { hookReplaceMethod(md) { 1 } }
            }
        } catch (ignored: Throwable) {
        }
    }

    // ---------- 广告 SDK 初始化 ----------

    // 穿山甲/快手/广点通初始化阻断, 广告组件不再启动(参照 ForbidAd4TieBa AdSdkInitBlockHook)
    internal fun blockAdSdkInit() {
        hookStaticReplace(PurgeAdKeys.TTAD_SDK, "init",
            listOf("android.content.Context", "com.bytedance.sdk.openadsdk.TTAdConfig"))
        hookStaticReplace(PurgeAdKeys.TTAD_SDK, "start",
            listOf("com.bytedance.sdk.openadsdk.TTAdSdk\$Callback"))
        hookStaticReplace(PurgeAdKeys.KSAD_SDK, "init",
            listOf("android.content.Context", "com.kwad.sdk.api.SdkConfig"))
        hookStaticReplace(PurgeAdKeys.KSAD_SDK, "start", emptyList())
        hookStaticReplace(PurgeAdKeys.GDT_SDK, "initWithoutStart",
            listOf("android.content.Context", "java.lang.String"))
        hookStaticReplace(PurgeAdKeys.GDT_SDK, "start",
            listOf("com.qq.e.comm.managers.GDTAdSdk\$OnStartListener"))
    }

    private fun hookStaticReplace(
        className: String,
        methodName: String,
        paramTypeNames: List<String>
    ) {
        try {
            val md = findClass(className).declaredMethods.firstOrNull { m ->
                Modifier.isStatic(m.modifiers) &&
                    m.name == methodName &&
                    m.parameterTypes.map { it.name } == paramTypeNames
            } ?: return
            hookReplaceMethod(md) { safeBlockedResult(md.returnType) }
        } catch (ignored: Throwable) {
        }
    }

    // 阻断结果按返回值类型安全化: init 类方法可能返回管理器对象, 宿主若解引用固定的
    // false/null 会在宿主代码中堆污染 CCE/NPE(hook 桥兜不住宿主调用点的异常)。
    // 接口返回值给动态代理(基本类型方法返回零值, 集合返回空集), 其余引用类型维持 null
    private fun safeBlockedResult(type: Class<*>): Any? {
        if (type == Void.TYPE) return null
        if (type.isInterface) {
            return java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
                when (method.returnType) {
                    Void.TYPE -> null
                    Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
                    Int::class.javaPrimitiveType, java.lang.Integer::class.java,
                    Short::class.javaPrimitiveType, java.lang.Short::class.java,
                    Byte::class.javaPrimitiveType, java.lang.Byte::class.java -> 0
                    Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
                    Float::class.javaPrimitiveType, java.lang.Float::class.java -> 0f
                    Double::class.javaPrimitiveType, java.lang.Double::class.java -> 0.0
                    Char::class.javaPrimitiveType, java.lang.Character::class.java -> ' '
                    java.lang.String::class.java -> ""
                    java.util.List::class.java -> emptyList<Any>()
                    java.util.Map::class.java -> emptyMap<Any, Any>()
                    else -> null
                }
            }
        }
        return when (type) {
            Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> false
            Int::class.javaPrimitiveType, java.lang.Integer::class.java -> 0
            Long::class.javaPrimitiveType, java.lang.Long::class.java -> 0L
            Float::class.javaPrimitiveType, java.lang.Float::class.java -> 0f
            Double::class.javaPrimitiveType, java.lang.Double::class.java -> 0.0
            else -> null
        }
    }
}
