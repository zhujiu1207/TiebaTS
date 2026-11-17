package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker

// 屏蔽首页带货贴, 移植自 ForbidAd4TieBa 的"屏蔽带货帖"(custom_post_filter.goods):
// 22.11 首页推荐流为模板卡片体系, 带货内容有两种形态——
// 1) 独立商品卡, 模板键 feed_link_store(内层实现类 b() 等键来自
//    tbclient.ComponentFactory 的 feed_link_store/feed_landmine_goods 字段);
// 2) 普通帖(common_card 合成卡)内嵌"地雷商品"组件, 内层卡模板键
//    feed_landmine_goods(实测地雷商品实现类 b() 返回值)。
// 只删内层商品卡会出现"商品没了帖子还在", 故与 BountyPostFilter 相同的两级拦截
// (共享脚手架见 Fs9Api.filterTemplateList): 外层列表载荷内嵌商品卡命中则整帖移除;
// 内层分段列表商品卡命中且无外层合成卡则整列表置空。
// 列表入口与 PurgeVideo/HotPostFilter 相同(FeedListPipeline 收敛注册), 模板卡
// 数据项接口(22.12=nt9/fs9, 22.13=eu9): b()=卡片键, d()=载荷, 共享 Fs9Api 解析。
class GoodsPostFilter : XposedContext(), IHooker {

    override fun key(): String {
        return "purge_goods_post"
    }

    override fun hook() {
        // 列表入口 hook 统一走 FeedListPipeline(单 hook 多阶段链), 此处只注册阶段。
        // innerList 标记该入口是否为内层分段列表(TemplateAdapter.setList):
        // 整列表置空只对内层列表合法, 外层入口命中地雷商品键时仅移除条目本身——
        // 未知新卡型/共用入口混入 feed_landmine_goods 键时不会整页清空
        // (与 Bounty/Score/GamePromoPostFilter 口径一致)。
        // setList 同时被 FeedTemplateAdapter.P/R 转调(外层二跳),
        // 需 owner 结构判定纠偏, 见 Fs9Api.isOuterFeedAdapter
        FeedListPipeline.register { list, owner, entry ->
            Fs9Api.filterTemplateList(
                list, entry == "setList" && !Fs9Api.isOuterFeedAdapter(owner), spec
            )
        }
    }

    // ForbidAd4TieBa GOODS 模板键 feed_link_store + 内嵌地雷商品键
    private val GOODS_TEMPLATE_KEYS = setOf("feed_link_store", "feed_landmine_goods")

    // 内嵌商品组件键: 命中且列表无外层合成卡时才允许整列表置空
    private val INNER_GOODS_KEY = "feed_landmine_goods"

    private val spec = Fs9Api.TemplateFilterSpec(
        dropKeys = GOODS_TEMPLATE_KEYS,
        // 只有内嵌商品组件(地雷商品)才标记 innerBlocked; 独立商品卡直接移除即可,
        // 否则不含 common_card 的列表会被误整列表清空
        innerKeys = setOf(INNER_GOODS_KEY),
        payloadPredicate = { payload -> Fs9Api.payloadHasKey(payload, GOODS_TEMPLATE_KEYS) },
    )
}
