package gm.tieba.tabswitch.dao

// 反混淆规则库实体。历史上是 Room @Entity, 现为纯 data class: 规则库只有一张
// 单表, 为它拖进 room-runtime/androidx.sqlite/flatbuffers 整链(字节码 ~50KB,
// 占 dex 12%)不划算, 已改为 framework SQLiteOpenHelper 手写实现(见 AcRules)。
// 字段语义: matcher=特征描述符(toString 形态), class/method=反混淆定位结果
data class AcRule(
    val id: Int,
    val matcher: String,
    val clazz: String,
    val method: String
) {
    companion object {
        fun create(matcher: String, clazz: String, method: String) =
            AcRule(0, matcher, clazz, method)
    }
}
