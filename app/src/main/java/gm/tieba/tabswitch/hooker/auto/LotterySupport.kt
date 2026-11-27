package gm.tieba.tabswitch.hooker.auto

import gm.tieba.tabswitch.bridge.XposedBridge
import android.util.Log

// 一键抽奖子系统的跨模块公共支撑件：IMSDK 锚点常量、诊断日志、实时消息条目、
// 单房间跨阶段状态。从 OneKeyLottery 原文搬移（逐字保留注释），作为各拆分模块
// （PoisonGuard/Ledger/HistoryFetch/ChainLearner）与主类的单一事实源。
// 全部 internal 同包可见：保持主类内既有非限定调用名不变，对支撑件零改动。

// IMSDK 类均未混淆，与贴吧版本解耦
internal const val LOTTERY_BIM_MANAGER = "com.baidu.android.imsdk.BIMManager"
internal const val LOTTERY_CHAT_MSG_MANAGER = "com.baidu.android.imsdk.chatmessage.ChatMsgManager"
internal const val LOTTERY_CHAT_MSG_MANAGER_IMPL =
    "com.baidu.android.imsdk.chatmessage.ChatMsgManagerImpl"
internal const val LOTTERY_MSG_CENTER_MODEL = "com.baidu.tieba.im.model.ImBaseMessageCenterModel"
internal const val LOTTERY_MSG_CENTER_FRAGMENT =
    "com.baidu.tieba.immessagecenter.msgtab.ui.frag.MsgCenterContainerFragment"
internal const val LOTTERY_GROUP_CHAT_REPO =
    "com.baidu.tieba.immessagecenter.chatgroup.grouppage.repo.GroupChatRepo"
internal const val LOTTERY_CHAT_ROOM_DETAIL =
    "com.baidu.tieba.immessagecenter.chatgroup.grouppage.repo.entity.ChatRoomDetail"
// IMSDK 消息模型类（未混淆、与贴吧版本解耦）
internal const val LOTTERY_CHAT_MSG_SDK = "com.baidu.android.imsdk.chatmessage.messages.ChatMsg"

// 群助手机器人默认名称/头像（uk 每个房间不同，运行时从房间数据获取；部分群机器人有自定义名称，
// 但仍用默认头像，头像哈希前缀可作为识别兜底）
internal const val LOTTERY_GROUP_ASSISTANT_NAME = "群助手"
internal const val LOTTERY_GROUP_ASSISTANT_PORTRAIT = "tb.1.d7085e15.HjS61CDiZJeUgbLmn6PDzg?t=1690529961"
internal const val LOTTERY_GROUP_ASSISTANT_PORTRAIT_HASH = "tb.1.d7085e15."

// 拉历史的 IMSDK 包装器单例类与条数。@我的群助手回复不走多播
// 旁路（实测多播里只有别人的消息），拉历史是核查阶段拿到自己回复的唯一来源；
// 曾据单次探测误判"恒返回空"——实际是回调常先回空包、数据随后才到。
// 类名随版本轮换: 12.74=cn6 / 12.63=f96 / 22.12=k57 / 22.13=v57 / 22.14=w57
// (22.15 核对 w57 未漂移, 仍为 ImTmpBridge$ImMemoryCacheManager 包装器)
// (i(Context,long,…,回调接口) 8 参过滤器即插即用, 新名在前)
internal val LOTTERY_FETCH_WRAPPERS = listOf(
    "com.baidu.tieba.w57", "com.baidu.tieba.v57", "com.baidu.tieba.k57",
    "com.baidu.tieba.cn6", "com.baidu.tieba.f96"
)
internal const val LOTTERY_FETCH_MESSAGE_COUNT = 60
internal const val LOTTERY_FETCH_LATCH_MILLIS = 3_000L

// 当日已领取登记的持久化文件：历史窗口只有最近 60 条，繁忙群几分钟就把早上的
// 回复挤出窗口，纯靠拉历史判重在同日重跑时必然漏；登记后跨贴吧重启仍然有效
internal const val LOTTERY_CLAIM_PREFS_NAME = "ts_lottery_prefs"

// 群助手"今日已领过"类回复的识别词。除中奖文案（含"福利"）外，重复领取的回复
// （"每个吧每天只有一次抽奖机会"）与已领取提示都不含"福利"，须一并覆盖；
// 注意不能只写"领取"——签到引导（"完成签到才能领取"）会被误判
internal val LOTTERY_CLAIM_REPLY_KEYS = listOf("福利", "抽奖机会", "已领取", "已领过", "已经领取")

// 刷毒账号昵称黑名单（子串匹配）：这些账号的消息在任何入口直接屏蔽（多播剔除、
// 聊天历史丢弃、房间预览置空）。对方改名/换号后把新昵称加进来即可
internal val LOTTERY_BLOCKED_SPAM_NICKS = listOf(
    "踩悔艺傲岩匆",
    "y6zd07submn",
    "日逼点下面图加我",
    "贴吧用户_aGVWbXP",
    "贴吧用户_Jb8yGG1"
)

// 毒消息的第二种形态：伪造未来时间戳（实测被刷的房间 latest 全部跑到约一个月后）。
// 列表按时间排序、预览按"消息时间>房间时间戳"推进，未来时间戳把毒消息钉死在
// "最新"位置、真消息全部排在它上面且预览永久停刷。超过该容限的时间视为伪造
internal const val LOTTERY_FUTURE_POISON_TOLERANCE_MILLIS = 10 * 60 * 1000L

// 昵称是否命中刷毒黑名单（子串匹配，空名不命中）
internal fun lotteryIsBlockedSpamNick(nick: String?): Boolean {
    if (nick.isNullOrEmpty()) return false
    return LOTTERY_BLOCKED_SPAM_NICKS.any { nick.contains(it) }
}

// 诊断日志：XposedBridge.log 在 LSPatch 下不进 logcat，同步打一份 logcat
// （过滤 TSLottery）；并写入模块日志文件——抽奖子系统行为复杂，用户反馈时
// 通常只提供模块日志，缺了这条链路就无法定位
internal fun lotteryTsLog(msg: String) {
    XposedBridge.log(msg)
    Log.d("TSLottery", msg)
    gm.tieba.tabswitch.util.Logger.log(msg)
}

// 实时消息条目（时间已归一化为毫秒）
internal class LotteryLiveMsg(
    val timeMs: Long,
    val fromUid: Long,
    val nick: String,
    val content: String,
    val atUks: List<String>,
    val atUids: List<Long>
)

// 单个房间的跨阶段状态：repo 从进房起保持存活，供后续核查/补发，全部结束后退房
internal class LotteryRoomTask(
    val roomId: Long,
    val roomName: String,
    val myName: String,
    val myBdUid: String,
    val myUk: String
) {
    var repo: Any? = null
    var uk: String = ""
    var botName: String = LOTTERY_GROUP_ASSISTANT_NAME
    var botPortrait: String = LOTTERY_GROUP_ASSISTANT_PORTRAIT
    var sentAt: Long = 0L
    var sent: Boolean = false
    // 发送应答：ok(...) / err(...) / miss（未收到回调）
    var sendAck: String = "miss"
    // 本轮进房内的发送尝试次数
    var attempts: Int = 0
    // 发送后等到了群助手回复（成功的直接证据；实测回复秒级到达）
    var replySeen: Boolean = false
    var confirmed: Boolean = false
    var resent: Boolean = false
    // 自身口令回显已收到（送达的直接证据）
    var echoed: Boolean = false
    // 失败原因（短语，用于汇总分类，如"未找到群助手"/"详情超时"/异常类名）
    var failReason: String = ""
    // 房间本身没有群助手（系统消息群/私聊等）：重试与补发轮必然再失败,
    // 置位后跳过, 省去每轮约 5s×N 的无效重试
    var noAssistant: Boolean = false
    // 确定性发送失败（uk→uid 换算失败 err(botUid=0) 等）：与瞬时失败不同,
    // 重试/补发重付完整进房+详情流程后必然同样失败, 置位后从重试与补发
    // 名单剔除, 结果仍计入最终汇总的失败项
    var fatalFail: Boolean = false
}

// 消息时间归一化为毫秒（>1e11 视为已是毫秒，否则按秒）
internal fun lotteryNormalizeMsgTime(raw: Long): Long {
    if (raw <= 0) return 0L
    return if (raw > 100_000_000_000L) raw else raw * 1000L
}

// 当前登录账号 uid 候选（免打扰/时间戳修复按账号隔离场景与频道列表共用）。
// uid 会拼进 "tb_group_chat_room_$uid" 的 SQL, 必须校验纯数字（非数字时
// rawQuery/UPDATE 必抛异常刷日志）——校验逻辑此前在 PoisonGuard 与
// OneKeyLottery 各持一份约 80% 逐字重复的实现, 收敛于此。
// 不含数据库目录扫描: 旧账号残留库只对免打扰/修复类场景有意义, 由调用方
// 按需自行补充（OneKeyLottery 的频道列表明确要求不做目录扫描）
internal fun lotteryVerifiedAccountUids(logTag: String): List<String> {
    val candidateUids = mutableListOf<String>()
    try {
        // getCurrentAccountId 可能返回 long, 按 Number 兼容取值(as? String 会得到 null)
        val accountId = gm.tieba.tabswitch.bridge.XposedHelpers.callStaticMethod(
            gm.tieba.tabswitch.XposedContext.findClass("com.baidu.tbadk.core.TbadkCoreApplication"),
            "getCurrentAccountId"
        )
        val accountIdStr = when (accountId) {
            is Number -> accountId.toLong().toString().takeIf { it != "0" }
            is String -> accountId.takeIf { it.isNotEmpty() }
            else -> null
        }
        if (accountIdStr?.matches(Regex("\\d+")) == true) candidateUids.add(accountIdStr)
    } catch (tr: Throwable) {
        lotteryTsLog("$logTag 获取账号失败 -- $tr")
    }
    try {
        gm.tieba.tabswitch.dao.Adp.account?.let {
            if (it.isNotEmpty() && it.matches(Regex("\\d+"))) candidateUids.add(it)
        }
    } catch (ignored: Throwable) {
    }
    return candidateUids.distinct()
}
