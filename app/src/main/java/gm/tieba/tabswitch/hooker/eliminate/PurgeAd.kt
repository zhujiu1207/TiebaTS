package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker

// 强力去广告: 移植 ForbidAd4TieBa(目标 22.11.1.0)的屏蔽点, 覆盖原有净化未触及的广告位:
//   - 信息流广告卡片(templateKey 判定, 参照其 FeedAdHook)
//   - 帖子页列表广告(TypeAdapter.setData 中 AdvertAppInfo 类型判定, 参照其 PostAdHook)
//   - 帖子页广告网络请求层(PbPageRequestMessage 字段清洗 + addAdRequestMessage 禁用)
//   - Pb 渲染前广告插入/首楼推荐/掉落红包动画
//   - 吧页面推广弹窗与 proto 字段 / 商业推广跳转 / 游戏悬浮条
//   - 会员免广告策略伪装 + 广告开关查询拦截(参照其 StrategyAdHook)
//   - 首页搜索框轮播推广词 / 广告 SDK(穿山甲/快手/广点通)初始化阻断
// 全部 hook 点均为 try/catch 包裹的运行时结构解析, 失效时静默跳过, 不依赖 DexKit 规则
//
// 文件结构(2026-11-17 按状态域拆分, 各模块逐字保留原文注释):
//   PurgeAdFeedCards   信息流广告卡片 + 模板键诊断日志(seenTemplateKeys/线程池缓存)
//   PurgeAdPostPage    帖子页列表广告 + 网络请求层(首楼类/getType 方法/广告类型缓存)
//   PurgeAdPbInsert    Pb 渲染前广告插入/首楼推荐/掉落红包动画(无状态)
//   PurgeAdForum       吧页推广 + 游戏悬浮条 + 首页搜索框推广词(字段/搜索框缓存)
//   PurgeAdStrategy    广告策略层 + 广告 SDK 初始化阻断(无状态)
//   PurgeAdScoring     满意度打分卡(吧内热门核心吧友专属/帖子内评论区氛围打分,
//                      全局 NPS 悬浮卡的列表内嵌形态, 2026-11-27 新增)
//   PurgeAdKeys        宿主锚点常量集中地(版本漂移对照检索)
// 本类保留 hooker 入口、hook() 编排与 isAdKey 判定（isPostAdItem 及其两块缓存
// 随 PurgeAdPostPage 走，见该文件）。
class PurgeAd : XposedContext(), IHooker {

    override fun key(): String {
        return "purge_ad"
    }

    override fun hook() {
        PurgeAdFeedCards.blockFeedAdCards(::isAdKey)
        PurgeAdPostPage.blockPostPageAds()
        PurgeAdPostPage.blockPbAdRequest()
        PurgeAdPbInsert.blockPbEarlyAdInsert()
        PurgeAdPbInsert.blockPbFirstFloorRecommend()
        PurgeAdPbInsert.blockPbFallingAd()
        PurgeAdForum.blockForumPageAds()
        PurgeAdStrategy.blockAdStrategy()
        PurgeAdForum.blockSearchBoxPromoText()
        PurgeAdStrategy.blockAdSdkInit()
        PurgeAdScoring.blockFeedScoringCards()
        PurgeAdScoring.blockCommentScoringCard()
        PurgeAdScoring.blockPbScoringComponent()
    }

    // 模板键是否为广告卡片
    private fun isAdKey(key: String): Boolean =
        key.startsWith("ad_card_") || key.startsWith("fun_ad_card_") || key in PurgeAdKeys.AD_KEYS
}
