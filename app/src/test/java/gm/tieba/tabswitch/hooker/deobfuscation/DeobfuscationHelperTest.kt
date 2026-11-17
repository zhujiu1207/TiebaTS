package gm.tieba.tabswitch.hooker.deobfuscation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 宿主版本区间判断语义固化: 它是"规则加载/静默降级/版本提示"三态分流的总判据,
 * 误判直接决定高版本宿主上的用户看到什么行为。既有语义: 位数不齐按缺失位为 0
 * 比较, 任何一位不可解析(带字母后缀等)整体判不满足。
 */
class DeobfuscationHelperTest {

    @Before
    fun setUp() {
        // 每个用例先把当前版本复位为本模块的适配目标, 需要其他版本的用例自行覆盖
        DeobfuscationHelper.sCurrentTbVersion = "22.13.1.1"
    }

    @Test
    fun satisfiesLowerBoundAndExactVersion() {
        assertTrue(DeobfuscationHelper.isTbSatisfyVersionRequirement("12.53.1.0"))
        assertTrue(DeobfuscationHelper.isTbSatisfyVersionRequirement("22.13.1.1"))
    }

    @Test
    fun doesNotSatisfyHigherVersion() {
        assertFalse(DeobfuscationHelper.isTbSatisfyVersionRequirement("22.14.1.0"))
    }

    @Test
    fun comparisonIsNumericNotLexicographic() {
        // 字符串序 "10" < "9", 数值序相反——这里必须是数值序
        DeobfuscationHelper.sCurrentTbVersion = "22.13.1.10"
        assertTrue(DeobfuscationHelper.isTbSatisfyVersionRequirement("22.13.1.9"))
    }

    @Test
    fun missingPartsCountAsZero() {
        DeobfuscationHelper.sCurrentTbVersion = "22.13"
        assertTrue(DeobfuscationHelper.isTbSatisfyVersionRequirement("22.13.0.0"))
        assertFalse(DeobfuscationHelper.isTbSatisfyVersionRequirement("22.13.0.1"))
    }

    @Test
    fun nonNumericRequiredPartNeverSatisfies() {
        assertFalse(DeobfuscationHelper.isTbSatisfyVersionRequirement("22.13.1.beta"))
    }

    @Test
    fun nonNumericCurrentPartNeverSatisfies() {
        DeobfuscationHelper.sCurrentTbVersion = "22.13.1.1-rc"
        assertFalse(DeobfuscationHelper.isTbSatisfyVersionRequirement("22.13.1.0"))
    }

    @Test
    fun betweenIsInclusiveOnBothEnds() {
        assertTrue(DeobfuscationHelper.isTbBetweenVersionRequirement("12.53.1.0", "22.13.1.1"))
    }

    @Test
    fun betweenExcludesOutsideRange() {
        // 上界之上
        assertFalse(DeobfuscationHelper.isTbBetweenVersionRequirement("12.53.1.0", "22.12.9.9"))
        // 下界之下
        assertFalse(DeobfuscationHelper.isTbBetweenVersionRequirement("22.14.0.0", "22.20.0.0"))
    }
}
