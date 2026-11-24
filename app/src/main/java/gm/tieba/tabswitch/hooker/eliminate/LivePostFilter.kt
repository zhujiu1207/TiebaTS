package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import java.lang.reflect.Field

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

    // 模板卡数据项接口 (22.12=nt9/fs9, 22.13=eu9): d()=载荷; 与 PurgeVideo/HotPostFilter 相同(共享 Fs9Api)
    private fun resolvePayloadMethod(): Boolean = Fs9Api.resolve()

    // feed_head 参数类为 CardHeadUiState(类名稳定, Map字段名混淆), 其全部 Map 实例
    // 字段解析共享于 Fs9Api.resolveHeadState/resolveMapFields(此前本文件自持一份
    // 相同的 synchronized 双检实现)
    private fun resolveHeadState(): Class<*>? = Fs9Api.resolveHeadState()

    // feed_head 参数内 recom_type == "3" 即直播推荐
    private fun isLiveHead(head: Any): Boolean {
        for (field in Fs9Api.resolveMapFields(head.javaClass)) {
            val map = runCatching { field.get(head) as? Map<*, *> }.getOrNull() ?: continue
            val recomType = map["recom_type"]?.toString()?.trim() ?: continue
            if (recomType == "3") return true
        }
        return false
    }

    // 载荷类实例的 List 字段(含继承), 共享缓存见 Fs9Api.resolveListFields
    private fun resolveListFields(clazz: Class<*>): List<Field> =
        Fs9Api.resolveListFields(clazz)

    // 合成卡载荷内的内层卡是否含直播 feed_head
    private fun payloadHasLiveHead(payload: Any): Boolean {
        val headClass = resolveHeadState() ?: return false
        for (field in resolveListFields(payload.javaClass)) {
            val inner = runCatching { field.get(payload) as? List<*> }.getOrNull() ?: continue
            for (element in inner) {
                if (element == null) continue
                if (!headClass.isInstance(element)) continue
                if (isLiveHead(element)) return true
            }
        }
        return false
    }

    // 在适配器列表入口处过滤直播贴, 返回原列表表示无需修改
    private fun filterTemplateList(list: List<*>?): List<*>? {
        if (list == null || list.isEmpty()) return list
        if (!resolvePayloadMethod()) return list
        return Fs9Api.filterCopy(list) { item ->
            if (item == null) return@filterCopy false
            val payload = runCatching { Fs9Api.cardPayload(item) }.getOrNull()
            val drop = payload != null && payloadHasLiveHead(payload)
            if (drop) {
                // 仅在用户开启日志时记录, 不做常驻 logcat 输出(每丢弃一卡一条)
                if (gm.tieba.tabswitch.util.Logger.isEnabled()) {
                    gm.tieba.tabswitch.util.Logger.log("live_post dropped")
                }
            }
            drop
        }
    }
}
