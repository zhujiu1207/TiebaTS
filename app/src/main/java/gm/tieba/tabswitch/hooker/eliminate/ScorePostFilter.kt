package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker

// 屏蔽首页打分贴("打分"标签+偶像/角色评分卡的帖子):
// 22.11 打分内容为 ComponentFactory 的 feed_score 组件(tbclient.FeedScoreComponent),
// 以内层分段卡形态挂在普通帖(common_card 合成卡)里, 分段数据类
// com.baidu.tieba.mc9(22.13=dd9, 22.14=id9) implements 卡片接口
// (22.12=nt9, 22.13=eu9, 22.14=pu9),
// b() 返回 "feed_score" (与地雷商品卡返回 "feed_landmine_goods" 同构)。
// 与 GoodsPostFilter 相同的两级拦截(共享脚手架见 Fs9Api.filterTemplateList):
// - 外层列表: 合成卡载荷内嵌打分分段卡命中则整帖移除;
// - 内层分段列表(feed_head/title/abstract/pic/score..., 渲染必经
//   TemplateAdapter.setList): 打分分段卡命中且该列表无外层合成卡则整列表
//   置空, 该帖无内容可渲染。整列表置空仅限内层入口: 外层入口出现打分键时
//   只移除该条目, 服务端将来下发独立打分卡/共用入口混入 score 键时不会整页清空。
// 列表入口与 PurgeVideo/GoodsPostFilter 相同(FeedListPipeline 收敛注册),
// 模板卡数据项接口(卡片键 b()/载荷 d(), 共享 Fs9Api 解析)。
class ScorePostFilter : XposedContext(), IHooker {

    override fun key(): String {
        return "purge_score_post"
    }

    override fun hook() {
        // 列表入口 hook 统一走 FeedListPipeline(单 hook 多阶段链), 此处只注册阶段。
        // innerList 标记该入口是否为内层分段列表(TemplateAdapter.setList):
        // 整列表置空只对内层列表合法(该帖已无内容可渲染), 外层列表命中打分键时
        // 仅移除条目本身, 避免未知形态的独立打分卡把整页清空。
        // setList 同时被 FeedTemplateAdapter.P/R 转调(外层二跳),
        // 需 owner 结构判定纠偏, 见 Fs9Api.isOuterFeedAdapter
        FeedListPipeline.register { list, owner, entry ->
            Fs9Api.filterTemplateList(
                list, entry == "setList" && !Fs9Api.isOuterFeedAdapter(owner), spec
            )
        }
    }

    // 打分组件分段键: ComponentFactory.feed_score / 打分卡 b()。
    // 22.12 起新增挂载态分段 feed_score_mount(CardScoreMountUiState.b()),
    // 实测打分卡走该键, 旧键保留兼容低版本
    private val SCORE_TEMPLATE_KEYS = setOf("feed_score", "feed_score_mount")

    private val spec = Fs9Api.TemplateFilterSpec(
        dropKeys = SCORE_TEMPLATE_KEYS,
        // 打分段均为内层分段组件, 命中即标记 innerBlocked
        innerKeys = SCORE_TEMPLATE_KEYS,
        payloadPredicate = { payload -> Fs9Api.payloadHasKey(payload, SCORE_TEMPLATE_KEYS) },
    )
}
