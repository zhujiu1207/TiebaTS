package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker

// 屏蔽首页悬赏贴, 移植自 ForbidAd4TieBa 的"屏蔽求助/悬赏帖"(custom_post_filter.help):
// 悬赏贴判定——
// 1) 模板键 feed_input_guide 直接命中;
// 2) feed_head 卡参数 card_type=="ugcBonusThread"(22.11.1 实测悬赏特征),
//    不使用旧的 is_special_thread 兜底规则(首页普通帖会误中, 整批误清会导致
//    推荐流闪空并报"网络不给力")。
// 两级拦截(共享脚手架见 Fs9Api.filterTemplateList):
// - 外层列表(common_card): 载荷内嵌 feed_head 命中参数则移除该帖(仅覆盖首页第一屏);
// - 内层分段列表(feed_head/title/abstract/pic/social_bar, 翻页追加后渲染必经
//   TemplateAdapter.setList): feed_head 参数命中则整列表置空, 该帖无内容可渲染。
// feed_head 参数取该元素全部 Map 实例字段(字段名混淆, 跨字段合并取首个非空值)。
class BountyPostFilter : XposedContext(), IHooker {

    override fun key(): String {
        return "purge_bounty_post"
    }

    override fun hook() {
        // 列表入口 hook 统一走 FeedListPipeline(单 hook 多阶段链), 此处只注册阶段。
        // innerList 标记该入口是否为内层分段列表(TemplateAdapter.setList):
        // 整列表置空只对内层列表合法(该帖已无内容可渲染), 外层列表命中悬赏键时
        // 仅移除条目本身——未知新卡型/共用入口混入 feed_head 键时不会整页清空
        // (与 ScorePostFilter 口径一致)。setList 同时被 FeedTemplateAdapter.P/R
        // 转调(外层二跳), 需 owner 结构判定纠偏, 见 Fs9Api.isOuterFeedAdapter
        FeedListPipeline.register { list, owner, entry ->
            Fs9Api.filterTemplateList(
                list, entry == "setList" && !Fs9Api.isOuterFeedAdapter(owner), spec
            )
        }
    }

    // ForbidAd4TieBa CustomPostFilterMatcher: HELP 模板键与参数常量
    private val HELP_TEMPLATE_KEY = "feed_input_guide"

    // 22.11.1 实测: 悬赏帖("悬赏xxx 互动得贴子")的 feed_head card_type
    private val BONUS_CARD_TYPE = "ugcBonusThread"

    private val spec = Fs9Api.TemplateFilterSpec(
        dropKeys = setOf(HELP_TEMPLATE_KEY),
        headPredicate = { head -> isBountyHead(head) },
        payloadPredicate = { payload -> Fs9Api.payloadHasHead(payload) { isBountyHead(it) } },
        // 原实现对任意非 head/命中键的外层卡都做载荷内 head 探测, 不限于 common_card
        payloadCheckAllOuter = true,
    )

    // feed_head 参数是否命中悬赏判定: 22.11.1 实测悬赏帖 card_type=="ugcBonusThread"
    private fun isBountyHead(head: Any): Boolean {
        for (field in Fs9Api.resolveMapFields(head.javaClass)) {
            val map = runCatching { field.get(head) as? Map<*, *> }.getOrNull() ?: continue
            val cardType = map["card_type"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            return cardType == BONUS_CARD_TYPE
        }
        return false
    }
}
