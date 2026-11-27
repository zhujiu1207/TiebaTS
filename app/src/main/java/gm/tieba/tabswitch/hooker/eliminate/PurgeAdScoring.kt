package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.XposedContext.Companion.hookBeforeMethod
import gm.tieba.tabswitch.util.Logger

// 满意度打分卡屏蔽(NPS 调研的列表内嵌形态)。从 Purge 的全局 NPS 悬浮卡 hook
// 分裂出来: GlobalNpsCardManager.K 只掐"悬浮挂载态"(底部悬浮的 AgileCard 卡),
// 服务端另把同款打分卡作为模板卡直接下发进信息流列表——两条内嵌通道旧实现
// 均未覆盖, 22.15 用户实测"帖子内评论区氛围打分卡(图1)与吧内热门核心吧友专属
// (图2)漏拦"。本模块封三条渲染通道, 全部按未混淆的稳定键/类名定位:
//
//   1. 信息流模板卡(首页/吧内热门): 键 bawu_score_card(22.15 qe9/kia/
//      CardBawuScoreView, LayoutFactory 分发键 bawu_feedback_card 经 gm9 表
//      → ao9 解析; 22.11~22.14 同键异类), 走 FeedListPipeline 按键过滤,
//      合成卡(common_card/stagger_card)载荷内嵌时整帖移除(同 feed_banner 口径);
//   2. 帖子内评论区模板卡: 键 comment_scoring(22.12 起, 22.15 xcd/wcd/
//      PbCommentScoringView), 经 CommentTemplateAdapter.submitList(i9().d1())
//      灌入楼层列表。CommentTemplateAdapter 是 FeedTemplateAdapter 的兄弟类
//      (均 extends TemplateAdapter 但互不为子类, Fs9Api.isOuterFeedAdapter
//      判不出它), FeedListPipeline 的 L/P/R 入口收不到该列表——独立挂
//      submitList(List, Runnable) 唯一 void(List,Runnable) 实例方法;
//   3. 评论区打分卡视图兜底: PbCommentScoringView.k(xcd) 是 22.12+ 评论区打分卡
//      的视图绑定入口(uu9 工厂 b() 转调), 按形状定位"唯一单参 void 实例方法 k"。
//      类 22.11 不存在, findClass 失败静默跳过。
//      注意不 hook 共享视图 ScoringComponentView.setData: 该视图全宿主三消费者
//      (PB 列表 und / 评论区 PbCommentScoringView / 图片查看器评分条 StarBottomView,
//      ImageViewerStarBottomController.b 在 scoringData 非空时展示"给图片评分"输入条),
//      全局 GONE 会误伤图片查看器评分条, 故视图级兜底只落评论区专用的
//      PbCommentScoringView; PB 老栈打分卡由通道1/2 的列表过滤覆盖
//      (und 的数据点 TYPE_PB_SCORING_COMPONENT 条目来自同一批打分调研数据,
//      服务端对该帖下发 comment_scoring 键时才渲染)。
//
// 各通道逐点隔离, 单通道失效只降级该通道; 键/类名均为宿主未混淆字面量,
// 漂移风险仅在宿主结构性重写打分卡时出现(届时需重新反编译核对)。
internal object PurgeAdScoring {

    // ---------- 通道1: 信息流模板卡(首页/吧内热门) ----------

    internal fun blockFeedScoringCards() {
        FeedListPipeline.register { list, _, _ -> filterScoringFeedList(list) }
    }

    // 合成卡载荷内的内层卡是否含打分卡(骨架见 Fs9Api.filterByCardKeys,
    // 键扫描收编为 Fs9Api.payloadHasKey 共享实现)
    private fun payloadHasScoringCard(payload: Any): Boolean =
        Fs9Api.payloadHasKey(payload, PurgeAdKeys.SCORING_TEMPLATE_KEYS)

    // 列表入口处过滤打分模板卡; 独立卡移除条目, 合成卡内嵌移除整帖。
    // 骨架共享于 Fs9Api.filterByCardKeys(原手写主循环已收编); 合成卡键取
    // Fs9Api.COMPOSITE_CARD_KEYS 单点(原 SCORING_COMPOSITE_KEYS 字面量已并入)
    private fun filterScoringFeedList(list: List<*>?): List<*>? =
        Fs9Api.filterByCardKeys(list, PurgeAdKeys.SCORING_TEMPLATE_KEYS) { payload ->
            payloadHasScoringCard(payload)
        }

    // ---------- 通道2: 帖子内评论区模板卡(CommentTemplateAdapter) ----------

    internal fun blockCommentScoringCard() {
        try {
            val adapterClass = findClass("com.baidu.tieba.pb.pagebrowser.comment.ui.CommentTemplateAdapter")
            val listType = java.util.List::class.java
            val runnableType = java.lang.Runnable::class.java
            // submitList(List, Runnable) 是该类唯一的 (List, Runnable) void 实例方法
            // (22.11/22.15 形状一致; 泛型擦除后参数为 List/Runnable)。非唯一时跳过
            // 防误伤(该类方法面小, 误伤概率低但存在, 保守处理)
            val candidates = adapterClass.declaredMethods.filter { md ->
                !java.lang.reflect.Modifier.isStatic(md.modifiers) &&
                    md.returnType == Void.TYPE &&
                    md.parameterTypes.size == 2 &&
                    md.parameterTypes[0] == listType &&
                    md.parameterTypes[1] == runnableType
            }
            if (candidates.size != 1) {
                logMiss("CommentTemplateAdapter submitList 候选 ${candidates.size} 个(预期 1), 跳过")
                return
            }
            hookBeforeMethod(candidates[0]) { param ->
                param.args[0] = filterScoringFeedList(param.args[0] as? List<*>)
            }
        } catch (ignored: Throwable) {
        }
    }

    // ---------- 通道3: 评论区打分卡视图兜底(PbCommentScoringView) ----------

    internal fun blockPbScoringComponent() {
        // PbCommentScoringView.k(xcd) 是 22.12+ 评论区打分卡的视图绑定入口
        // (uu9 工厂 b() 转调), 单参 xcd 类型随版本混淆轮换, 按形状定位
        // "唯一名为 k 的单参 void 实例方法"。该视图全宿主仅评论区打分卡使用
        // (ScoringComponentView 另有图片查看器评分条消费者, 不在本兜底范围,
        // 见文件头注释), 22.11 无此类, findClass 失败静默跳过
        try {
            val viewClass = findClass(PurgeAdKeys.PB_COMMENT_SCORING_VIEW)
            val candidates = viewClass.declaredMethods.filter { md ->
                !java.lang.reflect.Modifier.isStatic(md.modifiers) &&
                    md.returnType == Void.TYPE &&
                    md.parameterTypes.size == 1 &&
                    md.name == "k"
            }
            if (candidates.size == 1) {
                hookBeforeMethod(candidates[0]) { param ->
                    val view = param.thisObject as? android.view.View ?: return@hookBeforeMethod
                    param.result = null
                    view.visibility = android.view.View.GONE
                }
            }
        } catch (ignored: Throwable) {
        }
        // 22.15 实证通道2对评论区打分卡生效后, k() 不会被走到(列表灌入前条目已被
        // 移除); 本通道保留作防漂移兜底: 列表入口若因宿主重构失效, 卡片仍无法渲染
    }

    // 漂移留痕: 通道2候选不唯一属意外形态(类方法面稳定), 用户反馈无从排查时
    // 需要这一行定位; 走模块日志守日志开关约定
    private fun logMiss(msg: String) {
        Logger.log("[打分卡诊断] $msg")
    }
}
