package gm.tieba.tabswitch.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * parsePbContent 楼层文本拼接语义固化。
 *
 * <p>依赖的 XposedHelpers 是纯反射工具(不触框架 API, 见其类注释), 因此直接用
 * 本文件内的 fixture 类在 JVM 上测试——与宿主真实结构的关系只有"存在 text 字段"
 * 这一条契约, 热路径的 Field 缓存按 (元素类, 字段名) 分桶, fixture 类即真实路径。
 *
 * <p>覆盖点来自既有实现注释: 元素可为 null(mapNotNull 不拦截异常), 非 String 的
 * text(如 Integer)按 null 丢弃, contents 字段缺失/类型不符返回空串。
 */
class ParserTest {

    /** 模拟宿主 content 元素: 带合法 text 字段。 */
    class Content(val text: String)

    /** text 字段在父类: 覆盖 findField 沿继承链查找的路径。 */
    open class BaseContent(val text: String)
    class DerivedContent(text: String) : BaseContent(text)

    /** text 为非 String 类型: as? String 过滤为 null。 */
    class WeirdTextContent(val text: Int)

    /** 宿主数据类: 持有 content 列表(元素可含 null)。 */
    class PbPage(val content: List<Any?>)

    @Test
    fun joinsTextsOfAllElements() {
        val page = PbPage(listOf(Content("a"), Content("b"), Content("c")))
        assertEquals("abc", parsePbContent(page, "content"))
    }

    @Test
    fun skipsNullElements() {
        val page = PbPage(listOf(Content("a"), null, Content("c")))
        assertEquals("ac", parsePbContent(page, "content"))
    }

    @Test
    fun skipsNonStringTextField() {
        val page = PbPage(listOf(Content("a"), WeirdTextContent(42), Content("c")))
        assertEquals("ac", parsePbContent(page, "content"))
    }

    @Test
    fun resolvesFieldThroughInheritanceChain() {
        val page = PbPage(listOf(DerivedContent("x"), DerivedContent("y")))
        assertEquals("xy", parsePbContent(page, "content"))
    }

    @Test
    fun returnsEmptyForNullInstanceAndFieldName() {
        assertEquals("", parsePbContent(null, "content"))
        assertEquals("", parsePbContent(PbPage(emptyList()), null))
    }

    @Test
    fun returnsEmptyWhenFieldMissingOrTypeMismatched() {
        // 缺字段: getObjectField 把 NoSuchFieldError 包成 IllegalStateException 抛出,
        // 这是既有行为(调用方逐点 catch)。钉死"缺字段不静默", 防止有人顺手加 catch
        // 把"字段缺失"降级成空串——那会让漂移检测(规则失效提示)静默失效
        class NoContent(val other: String)
        org.junit.Assert.assertThrows(
            IllegalStateException::class.java
        ) { parsePbContent(NoContent("x"), "content") }

        // content 不是 List: as? List<*> 返回 null → 空串
        class NonListContent(val content: String)
        assertEquals("", parsePbContent(NonListContent("x"), "content"))
    }
}
