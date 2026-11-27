package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker

// 屏蔽首页直播贴, 移植自 ForbidAd4TieBa 的"屏蔽直播帖"(custom_post_filter.live):
// 直播贴无独立模板卡, 判定依据是合成卡载荷内 feed_head 的稳定类名
// com.baidu.tieba.feed.component.uistate.CardHeadUiState, feed_head 参数
// 存于该类 Map 字段(businessInfoMap)内, recom_type == "3", 命中则整帖移除。
// 列表入口统一走 FeedListPipeline(单 hook 多阶段链, 收编后覆盖 FeedTemplateAdapter
// L/P/R + TemplateAdapter.setList + HomeTemplateAdapter g0/j0 全部入口),
// 载荷取模板卡数据项接口(22.12=nt9/fs9, 22.13=eu9)的 d()。
class LivePostFilter : XposedContext(), IHooker {

    override fun key(): String {
        return "purge_live_post"
    }

    override fun hook() {
        // 列表入口 hook 统一走 FeedListPipeline(单 hook 多阶段链), 此处只注册阶段。
        // 此前未挂 HomeTemplateAdapter g0/j0, 收编后缓存恢复路径的漏过滤一并闭环
        FeedListPipeline.register { list, _, _ -> filterTemplateList(list) }
    }

    // feed_head 参数内 recom_type == "3" 即直播推荐
    private fun isLiveHead(head: Any): Boolean {
        for (field in Fs9Api.resolveMapFields(head.javaClass)) {
            val map = runCatching { field.get(head) as? Map<*, *> }.getOrNull() ?: continue
            val recomType = map["recom_type"]?.toString()?.trim() ?: continue
            if (recomType == "3") return true
        }
        return false
    }

    // 合成卡载荷内的内层卡是否含直播 feed_head(键扫描骨架共享于 Fs9Api.
    // filterByCardKeys, head 类双路定位复用 payloadHasHead)
    private fun payloadHasLiveHead(payload: Any): Boolean =
        Fs9Api.payloadHasHead(payload) { isLiveHead(it) }

    // 在适配器列表入口处过滤直播贴, 返回原列表表示无需修改。
    // 无独立模板键: 载荷含直播 head 即整帖移除, dropKeys 传空集表达。
    // payloadCheckAllOuter=true 保持原实现"完全不看键、对所有条目探测载荷"的
    // 口径(直播贴判定只依赖载荷内 feed_head, 键限定会把其它外壳合成卡漏掉)
    // 诊断日志按"本次实际发生丢弃"记录(骨架无命中时返回原列表引用)
    private fun filterTemplateList(list: List<*>?): List<*>? {
        val filtered = Fs9Api.filterByCardKeys(
            list, emptySet(), payloadCheckAllOuter = true
        ) { payload ->
            payloadHasLiveHead(payload)
        }
        if (filtered !== list && gm.tieba.tabswitch.util.Logger.isEnabled()) {
            gm.tieba.tabswitch.util.Logger.log("live_post dropped")
        }
        return filtered
    }
}
