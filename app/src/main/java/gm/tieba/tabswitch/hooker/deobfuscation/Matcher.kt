package gm.tieba.tabswitch.hooker.deobfuscation

import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher

abstract class Matcher(private val name: String) {
    var classMatcher: ClassMatcher? = null
    var requiredVersion: String? = null
    // 歧义丢弃上限(见 Deobfuscation 的歧义防护): 单 matcher 命中方法数超过该值
    // 时整条丢弃。null = 用全局默认(10); 特征本身就以"多调用方"为合法产出的
    // matcher(如按构造器签名找全部调用方)必须显式放宽, 否则会被误判为歧义
    var maxRules: Int? = null
    abstract val methodMatcher: MethodMatcher

    override fun toString(): String = name
}

class StringMatcher (str: String, name: String = str) : Matcher(name) {
    override val methodMatcher = MethodMatcher.create().usingStrings(str)
}

class SmaliMatcher (descriptor: String, name: String = descriptor) : Matcher(name) {
    override val methodMatcher = MethodMatcher.create().addInvoke(MethodMatcher.create().descriptor(descriptor))
}

class MethodNameMatcher(methodName: String, name: String) : Matcher(name) {
    override val methodMatcher = MethodMatcher.create().name(methodName)
}

class ReturnTypeMatcher<T>(returnType: Class<T>, name: String) : Matcher(name) {
    override val methodMatcher = MethodMatcher.create().returnType(returnType)
}

class ResMatcher(id: Long, name: String) : Matcher(name) {
    override val methodMatcher = MethodMatcher.create().usingNumbers(id)
}