package gm.tieba.tabswitch.hooker.auto

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers

// 毒消息防护子系统：多播入口剔除、转换器收方向过滤的判定函数、房间信息实体
// 时间戳双向钳制、被毒房间库修复。从 OneKeyLottery 原文搬移（逐字保留注释）。
//
// 毒消息屏蔽（抽奖失效的独立成因）：有人向群聊刷两类毒消息——
// ① 客户端转换器处理不了的（msgType=10000 且 notify_cmd 不在白名单 {102,109,110,21}、
//    或未知 type），转换器抛异常后该频道整条消息流水线卡死——聊天页停刷、
//    群助手回复也到不了；
// ② 伪造未来时间戳的普通文字/图片（实测把房间 latest 毒到约一个月后），列表按时间
//    排序把它们钉死在"最新"位置，真消息全部排在下面、房间库 timestamp 被毒停刷。
internal object LotteryPoisonGuard {

    // 毒消息拦截日志聚合窗口
    private const val POISON_LOG_WINDOW_MS = 60_000L

    // 群聊系统消息（msgType=10000）的 notify_cmd 白名单，与客户端转换器（22.11=fkb.e）
    // 的合法值保持一致：之外的系统消息会让转换器抛"非法状态"，把频道整条消息流水线
    // 打断至卡死（外挂刷的毒消息，表现为聊天页停刷、底部停在几天前、抽奖收不到回复）
    private val SYS_NOTIFY_CMD_WHITELIST = listOf(102, 109, 110, 21)

    // 毒消息判定（仅聊天室 category=4，私聊等其他类型不动；仅多播入口调用）：
    // 1) 发送者命中刷毒黑名单；
    // 2) 伪造未来时间戳——把毒消息钉死在列表"最新"位置、真消息全部排它上面、房间预览停刷；
    // 3) msgType=10000 且 notify_cmd 不在客户端白名单——转换器抛"非法状态"打断整条流水线
    internal fun isPoisonMsg(pojo: Any): Boolean {
        val category = runCatching { XposedHelpers.callMethod(pojo, "getCategory") as? Number }
            .getOrNull()?.toInt()
        if (category != null && category != 4) return false
        if (runCatching { lotteryIsBlockedSpamNick(XposedHelpers.callMethod(pojo, "getNickName") as? String) }
                .getOrDefault(false)
        ) {
            return true
        }
        if (isFutureDated(pojo)) return true
        val type = runCatching { XposedHelpers.callMethod(pojo, "getMsgType") as? Number }
            .getOrNull()?.toInt() ?: return false
        if (type != 10000) return false
        val cmd = runCatching { XposedHelpers.callMethod(pojo, "getNotifyCmd") as? Number }
            .getOrNull()?.toInt() ?: return false
        return cmd !in SYS_NOTIFY_CMD_WHITELIST
    }

    // 消息时间是否伪造在未来（超过容限即视为毒，时钟正常偏移远小于该值）
    internal fun isFutureDated(pojo: Any): Boolean {
        val raw = runCatching { XposedHelpers.callMethod(pojo, "getMsgTime") as? Long }
            .getOrNull() ?: return false
        val ms = lotteryNormalizeMsgTime(raw)
        if (ms <= 0) return false
        return ms > System.currentTimeMillis() + LOTTERY_FUTURE_POISON_TOLERANCE_MILLIS
    }

    // 把越界的未来时间钳到当下，保持原值的刻度（秒/毫秒，msgId 为毫秒级微秒混用，
    // 按 >1e11 即毫秒刻度的既有约定归一化判断）；未越界原样返回
    internal fun clampFutureTime(raw: Long): Long {
        val ms = lotteryNormalizeMsgTime(raw)
        if (ms <= 0) return raw
        if (ms <= System.currentTimeMillis() + LOTTERY_FUTURE_POISON_TOLERANCE_MILLIS) return raw
        return if (raw > 100_000_000_000L) System.currentTimeMillis()
        else System.currentTimeMillis() / 1000L
    }

    // 房间信息实体的时间戳钳制钩子：getter 兜住已加载的内存态（排序/门槛/展示统一
    // 恢复正常），setter 阻止服务端房间列表同步把毒时间写回实体与房间库。
    // 只钳时间戳（timestamp/msgTime），绝不碰 msgId——服务端消息 id 是 16 位微秒级，
    // 是"清除未读"上报给服务端的已读游标（cmd 1003551 的 chatroom_new_msg），按毫秒
    // 解读 16 位 id 会判成"未来"而改写成 13 位当前毫秒，游标缩小 1000 倍后服务端
    // 判定所有消息都在游标之后，未读永远清不掉且错误随清理/同步自我延续（4.8.28 实测）
    internal fun installRoomTimeClamps() {
        val clampResult = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val v = (param.result as? Number)?.toLong() ?: return
                val c = clampFutureTime(v)
                if (c != v) param.result = c
            }
        }
        val clampSetLong = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val v = (param.args.getOrNull(0) as? Number)?.toLong() ?: return
                param.args[0] = clampFutureTime(v)
            }
        }
        val clampSetString = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val s = param.args.getOrNull(0) as? String ?: return
                val v = s.toLongOrNull() ?: return
                val c = clampFutureTime(v)
                if (c != v) param.args[0] = c.toString()
            }
        }
        runCatching {
            val pojo = findClass("com.baidu.tieba.im.db.pojo.GroupChatRoomPojo")
            runCatching { XposedBridge.hookAllMethods(pojo, "getTimestamp", clampResult) }
                .onSuccess { lotteryTsLog("PoisonGuard: GroupChatRoomPojo.getTimestamp installed(" + it.size + " methods)") }
                .onFailure { lotteryTsLog("PoisonGuard: GroupChatRoomPojo.getTimestamp failed: " + it) }
            runCatching { XposedBridge.hookAllMethods(pojo, "setTimestamp", clampSetLong) }
                .onSuccess { lotteryTsLog("PoisonGuard: GroupChatRoomPojo.setTimestamp installed(" + it.size + " methods)") }
                .onFailure { lotteryTsLog("PoisonGuard: GroupChatRoomPojo.setTimestamp failed: " + it) }
        }.onFailure { lotteryTsLog("PoisonGuard: GroupChatRoomPojo unavailable: " + it) }
        runCatching {
            val newMsg = findClass("com.baidu.tieba.immessagecenter.chatgroup.data.ChatNewMessage")
            runCatching { XposedBridge.hookAllMethods(newMsg, "getMsgTime", clampResult) }
            runCatching { XposedBridge.hookAllMethods(newMsg, "setMsgTime", clampSetString) }
        }
    }

    // 多播分发入口的毒消息拦截：从分发改写参数剔除（模块自身记账在 collectLiveMsg
    // 已完成，不受影响）。实测毒消息以每秒级持续到达，剔除后客户端转换器不再反复抛异常
    internal fun blockPoisonMsgs(param: XC_MethodHook.MethodHookParam) {
        runCatching {
            for (i in param.args.indices) {
                val list = param.args[i] as? List<*> ?: continue
                if (list.isEmpty()) continue
                val kept = ArrayList<Any?>(list.size)
                var blocked = 0
                for (pojo in list) {
                    if (pojo != null && isPoisonMsg(pojo)) {
                        blocked++
                    } else {
                        kept.add(pojo)
                    }
                }
                if (blocked > 0) {
                    param.args[i] = kept
                    countPoisonBlockedAndFlushIfDue(blocked)
                }
            }
        }
    }

    // 毒消息拦截日志按 60s 窗口聚合, 多播分发与转换器收方向(历史/同步)两条路径共用:
    // 毒消息每秒级持续到达, 历史/同步路径还按批次拉取(单批至多 60 条)且
    // waitAssistantReply 每 ~4s 拉一次历史, 逐条 tsLog(每次都 SAF 落盘)会产生
    // 持续 CPU/IO 负载并稀释有效信息。flush 用 CAS 认领: 并发线程用旧窗口起点
    // 重复判定时不二次打印, 也不会把刚开启的新窗口立即清零
    internal fun countPoisonBlockedAndFlushIfDue(count: Int) {
        val now = System.currentTimeMillis()
        sPoisonWindowStartAt.compareAndSet(0L, now)
        sPoisonBlockedInWindow.addAndGet(count.toLong())
        val windowStart = sPoisonWindowStartAt.get()
        if (windowStart != 0L && now - windowStart >= POISON_LOG_WINDOW_MS &&
            sPoisonWindowStartAt.compareAndSet(windowStart, 0L)
        ) {
            val total = sPoisonBlockedInWindow.getAndSet(0L)
            lotteryTsLog("一键抽奖 近60s累计拦截${total}条毒消息（含多播/历史/同步路径）")
        }
    }

    // 毒消息拦截日志聚合窗口状态: notifyLiveChatMsg 的多播回调可能来自 IMSDK 的
    // 多个线程, 普通可变 long 并发读写会丢计数(32 位平台写入还可撕裂), 用原子类型
    private val sPoisonBlockedInWindow = java.util.concurrent.atomic.AtomicLong(0L)
    private val sPoisonWindowStartAt = java.util.concurrent.atomic.AtomicLong(0L)

    // 毒消息摘要（诊断日志用，逐字段 runCatching 防止摘要本身再抛）
    internal fun describeSdkMsg(sdkMsg: Any?): String {
        if (sdkMsg == null) return "null"
        val type = runCatching { XposedHelpers.callMethod(sdkMsg, "getMsgType") }.getOrNull()
        val cmd = runCatching { XposedHelpers.callMethod(sdkMsg, "getNotifyCmd") }.getOrNull()
        val nick = runCatching { XposedHelpers.callMethod(sdkMsg, "getNickName") as? String }.getOrNull()
        val content = runCatching { XposedHelpers.callMethod(sdkMsg, "getMsgContent") as? String }.getOrNull()
        return "type=$type cmd=$cmd nick=$nick content=${content?.take(40)}"
    }

    // 房间库时间戳修复节流（页面每次重建都会走到，60s 内不重复执行）
    private val mLastRoomRepairAt = java.util.concurrent.atomic.AtomicLong(0)

    // 修复被毒消息污染的房间库：tb_group_chat_room 的 timestamp 取自
    // "最新一条消息"，毒消息伪造的未来时间戳会把整行毒到一个月后（实测如此），此后
    // 预览推进的"消息时间>房间时间戳"门槛把全部真消息拒之门外。清零被毒行的 timestamp，
    // 房间信息随下一次同步按真实消息重建；latest_msg_id（已读游标）绝不清零，
    // 配合多播入口的毒消息拦截不再复毒
    internal fun repairPoisonedRoomTimestamps(context: android.content.Context) {
        val now = System.currentTimeMillis()
        // 节流用 getAndUpdate 原子认领: 判断由主线程派生的线程体执行, 先读后写的
        // check-then-act 会被两次快速页面重建双双通过(与宿主并发写同一库, 第二个
        // 连接拿不到库锁静默失败), 原子认领后竞争失败方直接退出
        val last = mLastRoomRepairAt.getAndUpdate { if (now - it < 60_000L) it else now }
        if (now - last < 60_000L) return
        val cutoff = now + LOTTERY_FUTURE_POISON_TOLERANCE_MILLIS
        for (uid in candidateAccountUids(context)) {
            val dbFile = context.getDatabasePath("$uid.db")
            if (!dbFile.exists()) continue
            runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                ).use { db ->
                    // 只清 timestamp：latest_msg_id 是"清除未读"上报服务端的已读游标（16 位微秒
                    // 级消息 id），清零会让服务端判定全部消息未读，游标一旦写坏随清理/同步自我延续
                    db.execSQL(
                        "UPDATE tb_group_chat_room_$uid SET timestamp = 0 " +
                            "WHERE timestamp > $cutoff"
                    )
                }
            }
        }
    }

    // 账号 uid 候选：当前账号 + Adp（共享取值与纯数字校验收编于
    // lotteryVerifiedAccountUids）+ 数据库目录扫描（免打扰/时间戳修复等
    // 按账号隔离场景需要覆盖旧账号残留库）
    private fun candidateAccountUids(context: android.content.Context): List<String> {
        val candidateUids = lotteryVerifiedAccountUids("一键抽奖").toMutableList()
        val dbDir = context.getDatabasePath("x").parentFile
        dbDir?.listFiles()?.forEach { f ->
            val name = f.name.removeSuffix(".db")
            if (f.name.matches(Regex("\\d+\\.db"))) candidateUids.add(name)
        }
        return candidateUids.distinct()
    }
}
