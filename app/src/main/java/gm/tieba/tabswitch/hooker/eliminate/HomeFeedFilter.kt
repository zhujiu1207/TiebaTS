package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.util.Logger

class HomeFeedFilter : XposedContext(), IHooker {

    override fun key(): String {
        return "home_feed_filter"
    }

    override fun hook() {
        val hideHelp = getBoolean("purge_help_post")
        val hideReply = getBoolean("purge_reply_post")
        if (!hideHelp && !hideReply) return
        // 与 PurgeVideo 相同的列表入口: 首页模板卡适配器的数据入口 + setList。
        // 列表入口 hook 统一走 FeedListPipeline(单 hook 多阶段链), 此处只注册阶段
        FeedListPipeline.register { list, _, _ -> filterList(list, hideHelp, hideReply) }
    }

    // 一次性留痕: 参数接口解析失败时过滤整体失效(漂移场景), 静默会让
    // "求助/回复贴仍在推荐流"无从排查
    @Volatile
    private var loggedUnresolved = false

    // 拆卡参数 Map 后按 key 取值比较, 不对 Map.toString() 做 contains——那是无边界子串
    // 匹配, Map 内任意 value 恰好含该字面量时也会误判。
    // 参数 Map 读取走 CardParamsApi 共享通道(f(), 22.14=wt9, 22.15=vu9): 与 HotPostFilter/
    // FollowFilter 同源, 带返回类型校验+候选轮换+失败留痕+per-pass memo。
    // 此前直取混淆字段名 "c"(22.14 实证 wt9.c 即 f() 返回的同一 Map), 字段名一但
    // 随版本轮换即静默失效且无任何日志, 故收编进共享解析体系。
    // 求助贴: 宿主以 card_type=="question_good"/"question_feelyou" 判定求助贴家族
    // (FeedHeadExtensionKt.f/g), 客户端与服务端均无裸值 "question"——此处须按
    // "question" 前缀匹配才能命中家族值(旧实现对 toString 做 contains 实际就是
    // 靠前缀命中的); 回复贴: 宿主自身即精确比较 card_type=="commentForwardCard"(mr9.j)
    private fun filterList(list: List<*>?, hideHelp: Boolean, hideReply: Boolean): List<*>? {
        if (list == null || list.isEmpty()) return list
        if (!CardParamsApi.resolve()) {
            if (!loggedUnresolved) {
                loggedUnresolved = true
                Logger.log("HomeFeedFilter: card 参数接口解析失败(fs9/mr9 漂移?), 过滤失效跳过")
            }
            return list
        }
        // 主循环走 Fs9Api.filterCopy 共享骨架(复制式过滤, 无命中返回原引用零分配):
        // null 条目口径为保留(谓词返回 false), 与旧手写实现一致
        return Fs9Api.filterCopy(list) { item ->
            if (item == null || !CardParamsApi.isParamItem(item)) return@filterCopy false
            val info = CardParamsApi.fMap(item) ?: return@filterCopy false
            val cardType = info["card_type"] as? String ?: return@filterCopy false
            (hideHelp && cardType.startsWith("question")) ||
                (hideReply && cardType == "commentForwardCard")
        }
    }
}
