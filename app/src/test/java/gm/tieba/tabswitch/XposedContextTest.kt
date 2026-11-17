package gm.tieba.tabswitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * safeConstantFor 语义固化: hookAllMethods 按名匹配全部重载, 固定装箱值在返回
 * 类型漂移(基本类型互换/新增重载)时会在宿主调用点拆箱崩溃; 本组测试把"按目标
 * 类型派发可安全拆箱的值"的行为钉死, 防止后续改动悄悄破坏类型派发表。
 */
class XposedContextTest {

    private fun constantFor(type: Class<*>, number: Long = 0L, bool: Boolean = false): Any? =
        XposedContext.safeConstantFor(type, number, bool)

    @Test
    fun booleanResolvesToGivenFlag() {
        assertEquals(true, constantFor(Boolean::class.javaPrimitiveType!!, bool = true))
        assertEquals(false, constantFor(Boolean::class.javaObjectType, bool = false))
        // 未指定布尔值时默认 false(与 returnConstant(false) 同义)
        assertEquals(false, constantFor(Boolean::class.javaPrimitiveType!!))
    }

    @Test
    fun integerTypesResolveToExactNumber() {
        assertEquals(4294967296L, constantFor(Long::class.javaPrimitiveType!!, 4294967296L))
        assertEquals(1000, constantFor(Int::class.javaPrimitiveType!!, 1000))
        assertEquals(1000.toShort(), constantFor(Short::class.javaPrimitiveType!!, 1000))
        assertEquals('A', constantFor(Char::class.javaPrimitiveType!!, 65))
    }

    @Test
    fun floatingTypesResolveToConvertedNumber() {
        assertEquals(1000f, constantFor(Float::class.javaPrimitiveType!!, 1000))
        assertEquals(1000.0, constantFor(Double::class.javaPrimitiveType!!, 1000))
    }

    @Test
    fun narrowerTypesTruncateLikeJavacCasts() {
        // 窄化截断是既有语义(与 (byte) number 的 JLS 行为一致), 钉死防止
        // 有人"顺手"改成饱和/报错版而改变 hook 返回值
        assertEquals(2.toByte(), constantFor(Byte::class.javaPrimitiveType!!, 258))
        assertEquals(4464.toShort(), constantFor(Short::class.javaPrimitiveType!!, 70000))
    }

    @Test
    fun boxedTypesResolveSameAsPrimitives() {
        assertEquals(1000, constantFor(Int::class.javaObjectType, 1000))
        assertEquals('A', constantFor(Character::class.javaObjectType, 65))
    }

    @Test
    fun unknownAndReferenceTypesResolveToNull() {
        // void 与无法转换的引用类型返回 null(与 returnConstant(null) 同义),
        // 禁止硬编码其他零值——对未知返回类型这是唯一安全值
        assertNull(constantFor(String::class.java))
        assertNull(constantFor(Any::class.java))
        assertNull(constantFor(java.lang.Void.TYPE))
        assertNull(constantFor(List::class.java))
    }
}
