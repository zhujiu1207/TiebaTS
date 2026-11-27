package gm.tieba.tabswitch.hooker.eliminate

// PurgeAd 拆分模块共享的宿主锚点常量（原 PurgeAd companion，逐字保留注释）。
// 各常量只被对应拆分模块引用，集中一处便于版本漂移时对照检索。
internal object PurgeAdKeys {

    // ForbidAd4TieBa FeedAdHook 的广告模板键集合
    internal val AD_KEYS = setOf(
        "video_ad", "feed_ad_video", "frs_empty_advert",
        "ad_card_head", "ad_card_title", "ad_card_single_pic", "ad_card_multi_pic",
        "ad_card_video", "ad_card_amount_download", "ad_card_interact",
        "commerce", "banner", "game"
    )

    // 广告/SDK 相关开关 key(SwitchManager.findType 拦截)
    internal val BLOCKED_SWITCH_KEYS = setOf(
        "ad_baichuan_open", "bear_wxb_download", "pref_key_fun_ad_sdk_enable",
        "platform_csj_init", "platform_gdt_init", "platform_ks_init",
        "platform_ubix_init", "12_30_fun_sdk_init_switch", "apsaras_switch"
    )

    internal const val TYPE_ADAPTER = "com.baidu.adp.widget.ListView.TypeAdapter"
    internal const val RECYCLER_TYPE_ADAPTER = "com.baidu.adp.widget.ListView.RecyclerViewTypeAdapter"
    internal const val BD_UNIQUE_ID = "com.baidu.adp.BdUniqueId"
    internal const val ADVERT_APP_INFO = "com.baidu.tbadk.core.data.AdvertAppInfo"
    internal const val PB_FIRST_FLOOR_RECOMMEND_DATA = "com.baidu.tieba.pb.data.PbFirstFloorRecommendData"

    internal const val PB_PAGE_REQUEST_MESSAGE = "com.baidu.tieba.pb.PbPageRequestMessage"
    internal const val PAGE_BROWSER_REQUEST_MESSAGE = "com.baidu.tieba.pb.pagebrowser.net.PageBrowserRequestMessage"
    // 注意 PbList 是包目录不是类(jadx 四版本布局一致: PbList.java 不存在,
    // DataReq.java 在 tbclient/PbList/ 包下, Builder 内嵌于 DataReq)——
    // 旧写法 "tbclient.PbList$DataReq$Builder" 的二进制名不存在, findClass 必抛
    // ClassNotFoundError 被消费点吞掉, 钩点全版本静默失效(2026-11-21 审查修正)
    internal const val PB_LIST_DATA_REQ_BUILDER = "tbclient.PbList.DataReq\$Builder"
    internal val PB_REQUEST_CLEAR_FIELDS = arrayOf(
        "adxBearBannerStr", "adxBearCommentStr", "adExternalBannerStr", "adExternalCommentStr"
    )

    internal const val PB_AD_INSERT_UTIL = "com.baidu.tieba.pb.pb.main.underlayer.PbAdapterManagerInsertUtilKt"
    internal const val PB_DATA_CLASS = "com.baidu.tieba.iic"
    internal const val PB_FRAGMENT_CLASS = "com.baidu.tieba.pb.pb.main.PbFragment"
    internal const val THREAD_DATA_CLASS = "com.baidu.tbadk.core.data.ThreadData"

    internal const val PB_LEGACY_HEADER_UTIL = "com.baidu.tieba.pb.legacy.header.LegacyHeaderBusinessKt"
    internal const val PB_POST_DATA_CLASS = "com.baidu.tieba.tbadkcore.data.PostData"

    internal const val PB_FALLING_VIEW = "com.baidu.tieba.pb.view.PbFallingView"
    internal const val PB_FALLING_SHOW = "C"

    internal const val FRS_DATA_RES_BUILDER = "tbclient.FrsPage.DataRes\$Builder"
    // FrsPage 同为包目录, BusinessPromot.java 是包内顶层类: 旧写法 "$" 形式类名
    // 不存在, 消费点的参数类型钉死防线曾无声丢失(靠唯一候选兜底巧合命中)
    internal const val FRS_BUSINESS_PROMOT = "tbclient.FrsPage.BusinessPromot"
    internal val FRS_POP_FIELDS = arrayOf(
        "bawutask_pop", "sprite_bubble_guide", "frsmask_pop_info", "enter_pop_info", "frs_rain_info"
    )
    internal const val FORUM_DIALOG_CONTROLLER = "com.baidu.tieba.forum.controller.ForumDialogController"
    internal const val GAME_FLOATING_BAR_CONTROLLER = "com.baidu.tieba.forum.controller.GameFloatingBarController"

    // 游戏悬浮条展示方法候选名(无参 void): 22.11 为 showFloatingBar,
    // 22.12 起 R8 混淆改名(对应原 showFloatingBar 的 s2, hide 为 l2);
    // 22.13 起 s2 被改为带参签名 s2(vh9/ei9), 原 show 语义轮换为 t2(22.14 实证:
    // t2() 体内 setVisibility(0)+alpha 即原 showFloatingBar)
    internal val GAME_FLOATING_BAR_SHOW_METHODS = arrayOf("showFloatingBar", "s2", "t2")

    internal const val FLOATING_BAR_VIEW = "com.baidu.tieba.feed.component.view.TbFloatingBar"
    internal const val BUSINESS_PROMOT_BIZ = "com.baidu.tieba.forum.hybrid.biz.BusinessPromotBiz"

    internal const val ACCOUNT_DATA = "com.baidu.tbadk.core.data.AccountData"
    internal const val SWITCH_MANAGER = "com.baidu.adp.lib.featureSwitch.SwitchManager"
    internal const val SPLASH_FORBID_HELPER = "com.baidu.tieba.ad.under.utils.SplashForbidAdHelperKt"
    internal const val CLOSE_AD_DATA = "com.baidu.tbadk.data.CloseAdData"

    internal const val SEARCH_BOX_VIEW = "com.baidu.tbadk.widget.TbSearchBoxView"
    internal const val SEARCH_BOX_OWNER = "com.baidu.tieba.homepage.personalize.PersonalizeHeaderViewController"
    internal const val BD_TYPE_RECYCLER_VIEW = "com.baidu.adp.widget.ListView.BdTypeRecyclerView"
    internal const val COORDINATOR_LAYOUT = "androidx.coordinatorlayout.widget.CoordinatorLayout"

    internal const val TTAD_SDK = "com.bytedance.sdk.openadsdk.TTAdSdk"
    internal const val KSAD_SDK = "com.kwad.sdk.api.KsAdSDK"
    internal const val GDT_SDK = "com.qq.e.comm.managers.GDTAdSdk"

    // ---------- 满意度打分卡(NPS 调研的列表内嵌形态, PurgeAdScoring 管辖) ----------

    // 模板卡键(卡片数据类 b() 恒定字面量, 未混淆):
    //   bawu_score_card  吧内热门"核心吧友专属"打分卡(22.15 qe9/kia/CardBawuScoreView,
    //                    经 LayoutFactory 分发键 bawu_feedback_card → ao9 解析; 22.11~22.14
    //                    同键异类 nb9/vc9/aga/lha, 键本身五版本一致)
    //   comment_scoring  帖子内"您认为当前评论区的氛围如何"打分卡(22.12 起存在:
    //                    22.12 x7d/l3d, 22.13 o9d/k3d, 22.14 vbd/r5d, 22.15 xcd/wcd,
    //                    经 CommentTemplateAdapter.submitList 灌入; 22.11 无此键)
    internal val SCORING_TEMPLATE_KEYS = setOf("bawu_score_card", "comment_scoring")

    // 合成卡键: 打分卡可能内嵌于外层合成卡载荷——字面量已并入 Fs9Api.
    // COMPOSITE_CARD_KEYS 单一事实源, 消费方(Fs9Api.filterByCardKeys 默认参)直接取用

    // 评论区打分卡视图(未混淆稳定类名, 22.12 起存在): PurgeAdScoring 通道3 视图级
    // 兜底目标。注意共享视图 ScoringComponentView(被图片查看器评分条复用)不在此列,
    // 全局 hook 会误伤, 见 PurgeAdScoring 文件头注释
    internal const val PB_COMMENT_SCORING_VIEW = "com.baidu.tieba.pb.pagebrowser.comment.scoring.PbCommentScoringView"
}
