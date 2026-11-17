package gm.tieba.tabswitch.hooker.extra

import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.AcRules.findRule
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.hooker.deobfuscation.StringMatcher

class AutoRefresh : XposedContext(), IHooker, Obfuscated {

    override fun key(): String {
        return "auto_refresh"
    }

    override fun matchers(): List<Matcher> {
        return listOf(
            StringMatcher("recommend_frs_refresh_time")
        )
    }

    override fun hook() {
        findRule(matchers()) { _, clazz, method ->
            val md = XposedHelpers.findMethodExactIfExists(
                findClass(clazz),
                method,
                Boolean::class.javaPrimitiveType
            ) ?: XposedHelpers.findMethodExactIfExists(
                findClass(clazz),
                method
            )
            // 按返回类型安全化: 签名定位无法保证目标返回 boolean, 统一走
            // safeConstantFor——本地拷贝的零值函数曾缺 Short/Byte/Char 分支,
            // 目标返回这些类型时会被置 null 在宿主调用点拆箱 NPE
            md?.let { hookReplaceMethod(it) { param ->
                safeConstantFor((param.method as java.lang.reflect.Method).returnType, 0, false)
            } }
        }
    }
}
