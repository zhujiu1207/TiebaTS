package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.bridge.XposedHelpers

// proto Builder 列表过滤的公共骨架(收编自 ContentFilter/UserFilter/
// FrsPageFilter 六处逐字同构实现): 读取 Builder 的列表字段 → 逐条目判定移除 →
// 命中才写回新列表。无命中/字段缺失一律原样不动; 复制式过滤对不可变入参安全
// (原地 removeIf 会抛异常击穿宿主)。
internal fun filterProtoBuilderList(builder: Any, listField: String, isDrop: (Any?) -> Boolean) {
    val list = XposedHelpers.getObjectField(builder, listField) as? List<*> ?: return
    val filtered = Fs9Api.filterCopy(list, isDrop)
    if (filtered.size != list.size) {
        XposedHelpers.setObjectField(builder, listField, filtered)
    }
}

// 吧页 feed 卡的 business_info KV 扫描骨架(FrsPageFilter 关键词 / UserFilter
// 作者编号两处逐字同构): feed_list 每条目取 feed.business_info, 对每个 FeedKV
// 的 key/value 调用方判定, 命中即整帖移除。
// key/value 为 proto String 对象字段可能为 null: 取不到即放行(fail-open),
// 与各调用点原判空语义一致。
internal fun filterBusinessInfoFeedList(
    pageData: Any,
    valueDrops: (key: String, value: String) -> Boolean
) {
    val list = XposedHelpers.getObjectField(pageData, "feed_list") as? List<*> ?: return
    val filtered = Fs9Api.filterCopy(list) { feedItem ->
        if (feedItem == null) return@filterCopy false
        runCatching {
            val feed = XposedHelpers.getObjectField(feedItem, "feed") ?: return@runCatching false
            val businessInfo = XposedHelpers.getObjectField(feed, "business_info") as? List<*>
            businessInfo?.any { feedKV ->
                if (feedKV == null) return@any false
                val key = XposedHelpers.getObjectField(feedKV, "key")?.toString()
                    ?: return@any false
                val value = XposedHelpers.getObjectField(feedKV, "value")?.toString()
                    ?: return@any false
                valueDrops(key, value)
            } ?: false
        }.getOrDefault(false)
    }
    if (filtered.size != list.size) {
        XposedHelpers.setObjectField(pageData, "feed_list", filtered)
    }
}
