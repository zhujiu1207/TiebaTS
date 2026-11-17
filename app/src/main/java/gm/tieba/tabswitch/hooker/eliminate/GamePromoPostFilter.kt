package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import org.json.JSONObject

// 屏蔽首页游戏推广贴, 移植自 ForbidAd4TieBa 的"屏蔽游戏/购买推广帖"
// (custom_post_filter.game_booking):
// 游戏推广贴判定有两条路径——
// 1) 模板键 feed_mount_book 直接命中;
// 2) 合成卡载荷内 feed_head 卡含非空 button_name(直接取或存于 game_ext JSON 串内),
//    命中则整帖移除。
// feed_head 卡定位与 ForbidAd4TieBa 一致: 内层卡模板键 "feed_head"(卡片键 b());
// 同时兼容 TiebaTS 既有做法(CardHeadUiState 实例, 类名稳定)。feed_head 参数
// 取该元素全部 Map 实例字段(字段名混淆, 逐一扫描)。
// 两级拦截(共享脚手架见 Fs9Api.filterTemplateList), 列表入口与
// PurgeVideo/HotPostFilter 相同(FeedListPipeline 收敛注册)。
class GamePromoPostFilter : XposedContext(), IHooker {

    override fun key(): String {
        return "purge_game_promo_post"
    }

    override fun hook() {
        // 列表入口 hook 统一走 FeedListPipeline(单 hook 多阶段链), 此处只注册阶段。
        // innerList 门控整列表置空(与 Score/BountyPostFilter 口径一致):
        // 外层入口命中推广键时仅移除条目, 防未知形态把整页清空。
        // setList 同时被 FeedTemplateAdapter.P/R 转调(外层二跳),
        // 需 owner 结构判定纠偏, 见 Fs9Api.isOuterFeedAdapter
        FeedListPipeline.register { list, owner, entry ->
            Fs9Api.filterTemplateList(
                list, entry == "setList" && !Fs9Api.isOuterFeedAdapter(owner), spec
            )
        }
    }

    // ForbidAd4TieBa CustomPostFilterMatcher: GAME_BOOKING 模板键与参数键
    private val GAME_BOOKING_TEMPLATE_KEY = "feed_mount_book"
    private val BUTTON_NAME_KEY = "button_name"
    private val GAME_EXT_KEY = "game_ext"

    private val spec = Fs9Api.TemplateFilterSpec(
        dropKeys = setOf(GAME_BOOKING_TEMPLATE_KEY),
        headPredicate = { head -> isGamePromoHead(head) },
        payloadPredicate = { payload -> Fs9Api.payloadHasHead(payload) { isGamePromoHead(it) } },
        // 原实现对任意非 head/命中键的外层卡都做载荷内 head 探测, 不限于 common_card
        payloadCheckAllOuter = true,
    )

    // button_name 非空(直接取), 或 game_ext JSON 串内 button_name 非空。
    // JSON 解析结果按 Map 实例做 per-pass memo(机制同 Fs9Api.PassMemo): 管线一轮内
    // 同一载荷 Map 会被重复传入, 每次 JSONObject 解析都是 O(串长) 的小分配;
    // 解析结果对同一 Map 实例在一代内恒定(只读判定), 含 null 负结果一并缓存
    private class GameExtMemo {
        var generation = -1L
        val buttonNames = java.util.IdentityHashMap<Any, String?>()

        fun maybeReset(current: Long) {
            if (generation != current) {
                generation = current
                buttonNames.clear()
            }
        }
    }

    private val sGameExtMemo = object : ThreadLocal<GameExtMemo>() {
        override fun initialValue(): GameExtMemo = GameExtMemo()
    }

    private fun findPromotionButtonName(map: Map<*, *>): String? {
        val memo = sGameExtMemo.get()!!.also { it.maybeReset(Fs9Api.currentPassGeneration()) }
        if (memo.buttonNames.containsKey(map)) return memo.buttonNames[map]
        val direct = map[BUTTON_NAME_KEY]?.toString()?.trim()
        val result = if (!direct.isNullOrEmpty()) {
            direct
        } else {
            val gameExt = map[GAME_EXT_KEY]?.toString()?.trim()
            if (gameExt == null) null else runCatching {
                JSONObject(gameExt).optString(BUTTON_NAME_KEY).trim().takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
        memo.buttonNames[map] = result
        return result
    }

    // feed_head 参数是否含游戏推广按钮(button_name 存于 head 全部 Map 实例字段内)
    private fun isGamePromoHead(head: Any): Boolean {
        for (field in Fs9Api.resolveMapFields(head.javaClass)) {
            val map = runCatching { field.get(head) as? Map<*, *> }.getOrNull() ?: continue
            if (findPromotionButtonName(map) != null) return true
        }
        return false
    }
}
