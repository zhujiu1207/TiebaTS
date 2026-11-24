package gm.tieba.tabswitch.hooker.auto

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XC_MethodHook.XC_MethodReplacement
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.dao.Adp
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.util.fixAlertDialogWidth
import gm.tieba.tabswitch.util.getCurrentActivity
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.widget.TbToast
import gm.tieba.tabswitch.widget.TbToast.Companion.showTbToast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * 一键抽奖：向消息中心的全部群聊频道发送领福利口令并确认群助手的响应。
 *
 * 四步台账流程（实测：发送真正成功时群助手 3 秒内必回；个别发送会被机器人侧静默吞掉，
 * 回调 ok 也不到达——必须以"收到回复"为成功准绳，无回复立即重发）：
 * 1. 准备阶段：获取频道列表并登记房间总数（全程"总数 vs 成功数"核查的台账基准）；
 * 2. 发送阶段：逐间进房发口令（发送间隔 ≥ MIN_SEND_GAP_MILLIS 防风控），发送后在
 *    REPLY_WAIT_MILLIS 窗口内轮询旁路+拉历史等群助手回复，无回复立即重发，每房间
 *    最多 MAX_SEND_ATTEMPTS 次（仅在通道可见时重试，防冷启动全盲误判）；
 * 3. 核查阶段：对照房间总数与成功频道数（口径见 isSentOk：群助手已回复 / 自身回显），
 *    排除已成功者得到遗漏名单；
 * 4. 补发阶段：仅对遗漏名单重新进房补发——重发前先复核回显/回复等送达证据防双份消息，
 *    确无证据才真正重发；补发后重新核查，最多 TOPUP_MAX_ROUNDS 轮（无进展即停）。
 * 收尾静置 REPLY_SETTLE_MILLIS 后对未确认房间再查一遍回复，不做任何发送；
 * 进度条按各阶段预估耗时加权匀速推进，全程 0-100 无停针无跳变。
 *
 * 运行全程持有屏幕Dim锁 + 高性能WiFi锁：锁屏/切后台导致的网络受限是
 * "发送调用成功但消息静默丢失"的主要来源，模拟器前台运行从未复现该问题。
 *
 * 毒消息屏蔽（抽奖失效的独立成因）：有人向群聊刷两类毒消息——
 * ① 客户端转换器处理不了的（msgType=10000 且 notify_cmd 不在白名单 {102,109,110,21}、
 *    或未知 type），转换器抛异常后该频道整条消息流水线卡死——聊天页停刷、
 *    群助手回复也到不了；
 * ② 伪造未来时间戳的普通文字/图片（实测把房间 latest 毒到约一个月后），列表按时间
 *    排序把它们钉死在"最新"位置，真消息全部排在下面、房间库 timestamp 被毒停刷。
 * 防护分层（不清洗消息中心的房间预览，预览显示什么交给客户端自身）：
 * - 多播入口 notifyLiveChatMsg 改写参数直接剔除两类毒消息（刷毒账号黑名单一并提供）；
 * - 转换器收方向替换为带捕获的实现，转换异常或时间戳伪造/黑名单消息丢弃
 *   （多播/进房同步/历史加载/库加载全走同一转换器，一处兜底处处生效）；
 * - GroupChatRoomPojo/ChatNewMessage 的时间戳读写双向钳制，防服务端同步反复回毒；
 * - 消息页每次打开及抽奖开跑时清零被毒房间行的 timestamp/latest_msg_id 让房间信息
 *   重建。被毒房间的旧预览随真实消息到达后自然恢复正常。
 *
 * 文件结构（2026-11-17 按状态域拆分，详见 README 同名章节）：毒消息防护在
 * LotteryPoisonGuard、日台账在 LotteryLedger、历史拉取在 LotteryHistoryFetch、
 * 转换链自学习在 LotteryChainLearner，公共锚点常量/消息条目/房间任务在
 * LotterySupport；本类保留 hooker 入口、四步台账流程编排与 IM 发送管线。
 * 本类大量按形态定位宿主混淆方法，混淆名轮换结论沉淀在各定位点注释，
 * 22.14 的 GroupChatRepo 进房/详情形态漂移见 prepareAndSend 内 h0/c0 两处注释。
 */
class OneKeyLottery : XposedContext(), IHooker {

    companion object {
        // 领取今日福利的口令，@提及须为真实 at_user_list（服务器据此触发群助手）
        private const val LOTTERY_COMMAND = " /领取今日福利"

        // IMSDK 锚点/群助手识别/拉取包装器/黑名单等公共常量已拆至 LotterySupport.kt
        // （同包 internal，引用点改为 LOTTERY_ 前缀名；此处仅保留发送管线私有常量）
        private const val BUTTON_TAG = "ts_one_key_lottery"

        // 任意两次实际发送（含补发）的最小间隔——防风控底线，勿调小；
        // 在此之上叠加随机抖动（0~SEND_GAP_JITTER），相邻发送呈 3~5 秒不规则的类人节奏，
        // 固定等间隔（如精确的 2.002s）本身就是机器特征
        private const val MIN_SEND_GAP_MILLIS = 3000L
        private const val SEND_GAP_JITTER_MILLIS = 2000L
        private const val DETAIL_TIMEOUT_MILLIS = 12000L

        // 发送回调等待：o() 的 listener 应答通常几百毫秒内到达
        private const val SEND_ACK_WAIT_MILLIS = 1200L

        // 单次发送后的回复等待窗口：实测群助手回复秒级到达（成功发送 3 秒内必有回音），
        // 超窗视为本次发送未被机器人接收，立即重试（复刻手动"多点几次才有回复"）
        private const val REPLY_WAIT_MILLIS = 10_000L
        private const val REPLY_POLL_GAP_MILLIS = 2_000L

        // 每轮进房对同一房间的最大发送尝试次数（含首次；防风控底线由发送间隔保障）
        private const val MAX_SEND_ATTEMPTS = 3

        // 连续 N 个房间用尽重试仍零回复（发送 ok、通道正常）→ 判定服务端限流/压制
        // （同日多轮连发后群助手会静默）。熔断后每房间只发一次、跳过补发，快速如实收尾
        private const val SILENT_STREAK_BREAKER = 3

        // 收尾前的静置时长：回复秒级到达，静置仅为兜底极少数迟到回复（必经阶段，同时是
        // 进度条上与其他阶段同斜率的一段匀速推进，每秒 1 格）
        private const val REPLY_SETTLE_MILLIS = 30_000L

        // 核查阶段逐间的间隔节流
        private const val SWEEP_FETCH_GAP_MILLIS = 300L

        // 第三步核查后对未成功频道补发的最大轮数；每轮补发后重新核查，仅在有进展时继续下一轮
        private const val TOPUP_MAX_ROUNDS = 2

        // WakeLock 单次窗口/续期间隔/全程硬上限（续期机制见 renewWakeLock）
        private const val WAKELOCK_TIMEOUT_MILLIS = 20 * 60 * 1000L
        private const val WAKELOCK_RENEW_GAP_MILLIS = 5 * 60_000L
        private const val WAKELOCK_DEADLINE_MILLIS = 60 * 60_000L

        // 冷启动后长连接未就绪的窗口内旁路全盲（收不到回显与回复，也无法区分"未送达"
        // 与"通道未就绪"）。开跑前先等旁路捕获到第一条消息，最长等这么多
        private const val BYPASS_WAIT_MILLIS = 90_000L

    }

    private val mSending = AtomicBoolean(false)
    // 确认框弹出期间防双击叠加: mSending 要到用户点"确认"后的 startLottery 才置位,
    // 弹窗动画期间的快速双击会叠出两个模态框
    private val mClickPending = AtomicBoolean(false)

    // 实时消息旁路：ChatMsgManagerImpl 的多播分发会把本账号全部群聊的每条消息
    // （含群助手回复与自身消息回显）送到这里，回复确认/送达判定不依赖拉历史接口
    private val mLotteryLiveMsgs = ConcurrentHashMap<Long, ConcurrentLinkedQueue<LotteryLiveMsg>>()

    // 旁路累计捕获条数（>0 即长连接/通道已就绪的证据）
    private val mLotteryLiveMsgCount = java.util.concurrent.atomic.AtomicLong(0)

    // 旁路实时捕获的群助手发言（roomId -> uid to 显示名），机器人列表找不到时的免网络兜底
    private val mLiveAssistant = ConcurrentHashMap<Long, Pair<Long, String>>()

    // 当前账号短标识（BDUSS 哈希，与台账键的账号段同源，见 activeAccountId）：
    // main() 据此检测账号切换并重置全部按账号缓存的内存态
    private var mActiveAccountKey: String? = null

    // 回显学到的本账号 IM uid（与百度 uid/uk 是不同标识体系），跨房间共享，
    // 学到后按它精确识别自己的消息/回显
    @Volatile
    private var mMyImUid = 0L

    // 聊天页加载时实时捕获的群聊频道（roomId -> 群名），用于过滤频道库中的失效房间
    private val mCapturedRooms = ConcurrentHashMap<Long, String>()

    // 按钮与进度条引用（页面重建后会重新创建）
    private var mButtonRef = java.lang.ref.WeakReference<TextView>(null)
    private var mProgressRef = java.lang.ref.WeakReference<android.graphics.drawable.ClipDrawable>(null)

    // 最近一次发送异常（类名+消息），用于失败诊断
    @Volatile
    private var mSendError = ""

    // 最近一次实际发送时刻，用于在 doSend 内统一保障最小发送间隔
    @Volatile
    private var mLastSendAt = 0L

    // 服务端校验回学的本账号消息 uk（atBdUk 体系）：getBdUKFromBdUid 转换出的 uk 与消息
    // at 条目里的 uk 不同源，认得以服务端换算回验为准（详见 learnMyUk）
    @Volatile
    private var mMyUk = ""

    // 已被服务端换算否决的 uk 候选（避免每间房重复换算与刷日志）
    private val mRejectedUks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // 进度条时间驱动：按"计划总时长"线性推进（阶段边界与每 10 间重估剩余时长，校正斜率），
    // 97% 封顶防"假完成"；实际完成后收尾段匀速补满到 100。任何情况不停针、不跳变
    @Volatile
    private var mBarPlanStart = 0L
    @Volatile
    private var mBarPlanTotal = 1L
    @Volatile
    private var mBarPercent = 0
    @Volatile
    private var mBarTickerRun = false

    // WakeLock 续期：20 分钟超时上限 < 弱网/限流下的最坏总时长（单间安静房间的探测等待
    // 与防风控间隔叠加后可超），锁过期恰好重新引入"屏幕可熄、后台网络受限→发送静默丢失"。
    // 进度条时间线程每 5 分钟重置一次 20 分钟窗口（非引用计数锁重复 acquire 即重排超时）；
    // 续期有 60 分钟硬上限，防流程死挂时无限制常亮
    @Volatile
    private var mWakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var mWakeLockRenewAt = 0L
    @Volatile
    private var mWakeLockDeadline = 0L

    private fun renewWakeLock() {
        val lock = mWakeLock ?: return
        runCatching {
            // 非引用计数 + 已持有时 acquire(timeout) 只重排超时, 不叠加引用。
            // isHeld 判定与 acquire 之间若流程 finally 已释放(置空 + release),
            // 这里的重新 acquire 会让 Dim 锁在抽奖结束后再持有一个 20 分钟窗口,
            // 故 acquire 前后各比对一次 mWakeLock === lock, 命中释放竞态立即补 release
            if (lock.isHeld && mWakeLock === lock) {
                lock.acquire(WAKELOCK_TIMEOUT_MILLIS)
                if (mWakeLock !== lock && lock.isHeld) lock.release()
            }
        }
    }

    override fun key(): String {
        return "one_key_lottery"
    }

    override fun hook() {
        // 聊天页容器创建后，在页面上加"一键抽奖"进度条按钮。
        // 各挂钩独立防护：单一类名随版本漂移时不连累其余挂钩失效
        runCatching {
            hookAfterMethod(
                LOTTERY_MSG_CENTER_FRAGMENT,
                "onCreateView",
                LayoutInflater::class.java,
                ViewGroup::class.java,
                Bundle::class.java
            ) { param ->
                val rootView = param.result as? ViewGroup ?: return@hookAfterMethod
                addLotteryButton(rootView)
            }
        }.onFailure { lotteryTsLog("一键抽奖 hook(消息页按钮)注册失败: $it") }

        // 捕获消息中心的群聊列表数据（群助手所在的签到群即在此列表中）
        runCatching {
            val modelClass = findClass(LOTTERY_MSG_CENTER_MODEL)
            // for 而非 forEach: 外层 forEach 与内层 list.forEach 产生同名 label,
            // 内层 return@forEach 编译歧义(警告), 语义上是"跳过当前 pojo"
            for (name in listOf("setData", "updateGroupchatList")) {
                XposedBridge.hookAllMethods(modelClass, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 回调内反射(宿主 pojo getter)在版本漂移时会抛 NoSuchMethodError:
                        // 桥接层虽会吞掉不至崩宿主, 但捕获静默失效并刷 LSPosed 日志,
                        // 就地包裹并记入模块日志
                        // 显式 label: 外层注册 runCatching 与内层包裹 runCatching 同名,
                        // return@runCatching 编译歧义(警告)
                        runCatching parse@{
                            val list = param.args.getOrNull(0) as? List<*> ?: return@parse
                            list.forEach { pojo ->
                                pojo ?: return@forEach
                                // TYPE_GROUP_MSG = 1
                                val type = XposedHelpers.callMethod(pojo, "getType") as? Int
                                    ?: return@forEach
                                if (type != 1) return@forEach
                                val roomId = XposedHelpers.callMethod(pojo, "getRoomId") as? Long
                                    ?: return@forEach
                                if (roomId <= 0) return@forEach
                                val name2 = XposedHelpers.callMethod(pojo, "getGroup_name") as? String
                                mCapturedRooms[roomId] = name2 ?: roomId.toString()
                            }
                        }.onFailure {
                            lotteryTsLog("一键抽奖 群聊列表捕获失败: $it")
                        }
                    }
                })
            }
        }.onFailure { lotteryTsLog("一键抽奖 hook(群聊列表捕获)注册失败: $it") }

        // 旁听 IMSDK 群聊消息多播分发（未混淆、跨版本稳定）。
        // 同时拦截毒消息（见 isPoisonMsg）：从分发改写参数剔除，客户端链路不再见到，
        // 多播批次不再被转换异常反复打断、伪造时间戳的消息不再进入列表与房间预览；
        // 模块自身记账（回复确认/送达判定）只看过滤后的消息
        runCatching {
            XposedBridge.hookAllMethods(
                findClass(LOTTERY_CHAT_MSG_MANAGER_IMPL), "notifyLiveChatMsg", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        LotteryPoisonGuard.blockPoisonMsgs(param)
                        // 标量参数直接跳过: notifyLiveChatMsg(int,long,List,boolean)
                        // (22.14 实证) 每条多播会为 3 个标量参数各走一遍必失败的
                        // getCategory 反射, 高频路径纯浪费; List 与其余引用类型
                        // (单个 pojo 形态的重载)照常收集
                        param.args.forEach { arg ->
                            when (arg) {
                                null, is Number, is Boolean, is String -> {}
                                else -> collectLotteryLiveMsg(arg)
                            }
                        }
                    }
                }
            )
        }.onFailure { lotteryTsLog("一键抽奖 hook(毒消息多播拦截)注册失败: $it") }

        // 房间信息实体时间戳钳制：房间列表/预览由 GroupChatRoomPojo（本地房间表）与
        // ChatNewMessage（服务端房间列表携带的"最新消息"）驱动，毒消息把它们毒到未来
        // 一个月后，排序钉顶、预览推进的"消息时间>房间时间戳"门槛把全部真消息拒之
        // 门外。在读写两个方向钳制越界值（getter 兜住已加载的内存态，setter 阻止服务
        // 端同步反复回毒），下一条真消息到达即可顶掉毒消息的预览。类均在具名包内，与
        // ChatRoomDetail 同等稳定；旧版本不存在时静默跳过
        runCatching { LotteryPoisonGuard.installRoomTimeClamps() }
            .onFailure { lotteryTsLog("一键抽奖 hook(时间戳钳制)注册失败: $it") }

        // 调试旁听钩子已随审查移除(2026-11-21): 每次发送打一条无开关的 Log.d,
        // 违反日志约定; 发送行为本身有 sendAck/lotteryTsLog 全链路留痕, 无需旁听

        // 自适应转换链学习注册已拆至 LotteryChainLearner.install()（识别/挂钩/毒过滤同源搬移）
        LotteryChainLearner.install()
    }

    @SuppressLint("SetTextI18n")
    private fun addLotteryButton(rootView: ViewGroup) {
        if (rootView.findViewWithTag<View>(BUTTON_TAG) != null) return
        runOnUiThread {
            if (rootView.findViewWithTag<View>(BUTTON_TAG) != null) return@runOnUiThread
            val density = rootView.resources.displayMetrics.density
            val dip = { value: Float -> (value * density).toInt() }

            // 按钮即进度条：灰色轨道 + 蓝色填充（LayerDrawable 叠加 ClipDrawable），
            // 低进度时轨道仍然完整可见，不会因填充为 0 而失去按钮外观
            val track = GradientDrawable().apply {
                setColor(0x88000000.toInt())
                cornerRadius = dip(18f).toFloat()
            }
            val fill = GradientDrawable().apply {
                setColor(0xFF4A6CF7.toInt())
                cornerRadius = dip(18f).toFloat()
            }
            val progressClip = android.graphics.drawable.ClipDrawable(
                fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL
            ).apply { level = 10000 }
            val buttonBackground = android.graphics.drawable.LayerDrawable(arrayOf(track, progressClip))

            val button = TextView(rootView.context).apply {
                tag = BUTTON_TAG
                text = "一键抽奖"
                textSize = 13f
                setTextColor(Color.WHITE)
                background = buttonBackground
                setPadding(dip(14f), dip(7f), dip(14f), dip(7f))
                elevation = dip(4f).toFloat()
                // 半透明按压反馈
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> view.alpha = 0.7f
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> view.alpha = 1f
                    }
                    false
                }
            }

            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.BOTTOM
                rightMargin = dip(16f)
                bottomMargin = dip(56f)
            }

            rootView.addView(button, params)
            mButtonRef = java.lang.ref.WeakReference(button)
            mProgressRef = java.lang.ref.WeakReference(progressClip)
            button.setOnClickListener { onLotteryClick() }
            // 消息页每次打开顺手修复被毒消息污染的房间时间戳（60s 节流）
            thread(name = "TSRoomRepair") {
                runCatching { LotteryPoisonGuard.repairPoisonedRoomTimestamps(rootView.context.applicationContext) }
            }
        }
    }

    // 按钮进度提示：运行时按钮变为进度条（蓝色填充）并显示百分比，结束恢复
    private fun updateProgress(percent: Int, text: String) {
        runOnUiThread {
            mProgressRef.get()?.level = percent.coerceIn(0, 100) * 100
            mButtonRef.get()?.text = text
        }
    }

    // 进度条时间线程：每 400ms 按"计划总时长"线性推进一格，97% 封顶（防实际未完成时假到 100）；
    // 单调不回退——阶段重估拉长计划时，进度只停不降（此前重估会使百分比从 97 掉回 92）
    private fun startBarTicker(totalMillis: Long) {
        mBarPlanStart = System.currentTimeMillis()
        mBarPlanTotal = totalMillis.coerceAtLeast(10_000L)
        mBarPercent = 0
        mBarTickerRun = true
        thread(name = "TSBar") {
            while (mBarTickerRun) {
                Thread.sleep(400)
                if (!mBarTickerRun) break
                // WakeLock 续期挂在全程运行的时间线程上: 期间隔续期重排超时窗口,
                // 硬上限后不再续期(死挂流程最多再持有一个窗口就自然过期)
                val now = System.currentTimeMillis()
                if (now - mWakeLockRenewAt >= WAKELOCK_RENEW_GAP_MILLIS) {
                    mWakeLockRenewAt = now
                    if (now < mWakeLockDeadline) renewWakeLock()
                }
                val elapsed = System.currentTimeMillis() - mBarPlanStart
                val percent = (elapsed * 100 / mBarPlanTotal).toInt().coerceIn(0, 97)
                val shown = maxOf(percent, mBarPercent)
                if (shown != mBarPercent) {
                    mBarPercent = shown
                    updateProgress(shown, "$shown%")
                }
            }
        }
    }

    // 阶段边界/每 10 间重估：按已流逝时间 + 新的剩余预估重排计划，校正后续斜率
    private fun replanBar(remainingMillis: Long) {
        val elapsed = (System.currentTimeMillis() - mBarPlanStart).coerceAtLeast(1000L)
        mBarPlanTotal = elapsed + remainingMillis.coerceAtLeast(1000L)
    }

    // 收尾：停表并从当前格匀速补满到 100（约 1 秒），末尾绝不跳变
    private fun stopBarTickerFillTo100() {
        if (!mBarTickerRun) return
        mBarTickerRun = false
        var p = mBarPercent.coerceIn(0, 99)
        while (p < 100) {
            Thread.sleep(60)
            p = minOf(100, p + 2)
            updateProgress(p, "$p%")
        }
    }

    private fun onLotteryClick() {
        if (mSending.get()) {
            showTbToast("抽奖正在进行中，请稍候", TbToast.LENGTH_SHORT)
            return
        }
        if (!mClickPending.compareAndSet(false, true)) return
        // 开跑前确认：抽奖奖励以完成为签到前提，防误点直接开跑；
        // 拿不到前台 Activity（无处依附）时保持原行为直接开跑
        val activity = runCatching { getCurrentActivity() }.getOrNull()
        if (activity == null || activity.isFinishing) {
            mClickPending.set(false)
            startLottery()
            return
        }
        AlertDialog.Builder(activity, getDialogTheme(activity))
            .setMessage("一键抽奖需完成签到才能领取奖励\n是否已完成签到？")
            .setPositiveButton("确认") { _, _ -> startLottery() }
            .setNegativeButton("取消", null)
            .setOnCancelListener { mClickPending.set(false) }
            .create()
            .apply {
                show()
                fixAlertDialogWidth(this)
                // 弹窗文字居中
                findViewById<TextView>(android.R.id.message)?.gravity = Gravity.CENTER
            }
    }

    // @Suppress("DEPRECATION"): SCREEN_DIM_WAKE_LOCK/WIFI_MODE_FULL_HIGH_PERF 在
    // API 28/29 起 deprecated 且建议的新 API 均不满足本场景——FLAG_KEEP_SCREEN_ON
    // 仅窗口可见时有效(切后台失效), WIFI_MODE_FULL_LOW_LATENCY 常量 API 29 才有
    // (本项目目标设备 Android 9/API 28 无此常量)。旧常量在全部目标 API 上行为正常
    @Suppress("DEPRECATION")
    private fun startLottery() {
        if (!mSending.compareAndSet(false, true)) return
        // 开跑即复位点击占位: 否则本轮结束后 mClickPending 残留 true, 下轮点击被挡死
        mClickPending.set(false)
        updateProgress(0, "准备中")
        thread(name = "TSLottery") {
            // 全程持锁：锁屏/切后台的网络受限会令发送静默丢失（手机上"约一半群未收到"的主因）
            var wakeLock: PowerManager.WakeLock? = null
            var wifiLock: WifiManager.WifiLock? = null
            try {
                val context = getContext().applicationContext
                runCatching {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    // 不用 ACQUIRE_CAUSES_WAKEUP: 它只在 acquire 瞬间点亮屏幕,
                    // 而点击按钮时屏幕必然已亮, 该标志只带来"误触后兜里常亮"的副作用
                    wakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_DIM_WAKE_LOCK,
                        "TiebaTS:lottery"
                    )
                    // 非引用计数: 供 renewWakeLock 重复 acquire 重排超时, 最终单次 release 即彻底释放
                    wakeLock?.setReferenceCounted(false)
                    // 上限覆盖三阶段的典型时长（50 群发送 + 3 轮复核 ≈ 10 分钟）;
                    // 弱网下超过窗口由时间线程续期（见 renewWakeLock）
                    wakeLock?.acquire(WAKELOCK_TIMEOUT_MILLIS)
                    mWakeLock = wakeLock
                    mWakeLockRenewAt = System.currentTimeMillis()
                    mWakeLockDeadline = mWakeLockRenewAt + WAKELOCK_DEADLINE_MILLIS
                    val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifiLock = wm.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TiebaTS:lotteryWifi"
                    ).also { it.acquire() }
                }
                // 22.11+ 自适应转换链依赖消息流量学习（收/发消息触发 TbTextMsg 构造）；
                // 开跑前给 10s 窗口，未就绪不阻塞——doSend 会先试自学习链、失败自动回退硬编码链
                if (!LotteryChainLearner.isChainLearned) {
                    lotteryTsLog("一键抽奖 自适应转换链未就绪，等待消息流量（最多10s）")
                    val learnDeadline = System.currentTimeMillis() + 10_000L
                    while (!LotteryChainLearner.isChainLearned && System.currentTimeMillis() < learnDeadline) {
                        Thread.sleep(500)
                    }
                    XposedBridge.log(
                        if (LotteryChainLearner.isChainLearned) "一键抽奖 自适应转换链就绪"
                        else "一键抽奖 自适应转换链未学习到，走硬编码回退"
                    )
                }
                // 台账日快照：本轮全程（含跨午夜）沿用同一日期口径（快照逻辑见 LotteryLedger.beginRun）
                LotteryLedger.beginRun()
                val result = main(context)
                runOnUiThread { showTbToast(result, TbToast.LENGTH_LONG) }
            } catch (tr: Throwable) {
                lotteryTsLog("一键抽奖出现错误 -- $tr")
                XposedBridge.log(tr)
                runOnUiThread { showTbToast("一键抽奖失败：" + tr.message, TbToast.LENGTH_LONG) }
            } finally {
                LotteryLedger.endRun()
                mWakeLock = null
                runCatching { wifiLock?.release() }
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                mSending.set(false)
                updateProgress(100, "一键抽奖")
            }
        }
    }

    // 从多播分发参数中提取群聊消息（参数可能是单条对象或集合；只收 category=4 的聊天室消息，
    // 避免私聊等其他类型的 contacter 撞 roomId）
    private fun collectLotteryLiveMsg(arg: Any?) {
        val pojos = when (arg) {
            null -> return
            is List<*> -> arg
            else -> listOf(arg)
        }
        pojos.forEach { pojo ->
            extractLotteryLiveMsg(pojo)?.let { appendLotteryLiveMsg(it.first, it.second) }
        }
    }

    // 反射提取单条消息：返回 (roomId, LotteryLiveMsg)
    private fun extractLotteryLiveMsg(pojo: Any?): Pair<Long, LotteryLiveMsg>? {
        pojo ?: return null
        return runCatching {
            val category = XposedHelpers.callMethod(pojo, "getCategory") as? Int ?: return null
            if (category != 4) return null
            val roomId = XposedHelpers.callMethod(pojo, "getContacter") as? Long ?: return null
            if (roomId <= 0) return null
            val timeMs = lotteryNormalizeMsgTime(
                XposedHelpers.callMethod(pojo, "getMsgTime") as? Long ?: return null
            )
            val fromUid = runCatching {
                XposedHelpers.callMethod(pojo, "getFromUser") as? Long
            }.getOrNull() ?: 0L
            val nick = XposedHelpers.callMethod(pojo, "getNickName") as? String ?: ""
            val content = XposedHelpers.callMethod(pojo, "getMsgContent") as? String ?: ""
            // at 条目逐条并行收集（两列表同长同序）。getter 按候选顺序试探——条目类随版本不同：
            // 12.74 无 getAtBdUk; 22.12 的条目是纯公开字段 POJO(getter 全部消失), 落到字段回退;
            // 数字 ID 在 22.12 可经 ChatMsg.getAtUsers()(mAtuks) 直接取得。字符串只收 ID（uk 系），
            // 昵称一律不入比对集
            val atUks = ArrayList<String>()
            val atUids = ArrayList<Long>()
            val atRaw = runCatching {
                XposedHelpers.callMethod(pojo, "getAtUserList") as? List<*>
            }.getOrNull()
            atRaw?.forEach { at ->
                atUks.add(if (at == null) "" else firstIdString(at))
                atUids.add(if (at == null) 0L else firstIdLong(at))
            }
            if (atUids.all { it <= 0L }) {
                // 22.12 数字 ID 兜底: getAtUsers() 返回 List<Long>（uk 数值形态）
                runCatching {
                    XposedHelpers.callMethod(pojo, "getAtUsers") as? List<*>
                }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { users ->
                    atUids.clear()
                    users.forEach { v ->
                        atUids.add((v as? Number)?.toLong() ?: 0L)
                    }
                    // 保持两列表同长同序
                    while (atUids.size > atUks.size) atUks.add("")
                    while (atUks.size > atUids.size) atUids.add(0L)
                }
            }
            roomId to LotteryLiveMsg(timeMs, fromUid, nick, content, atUks, atUids)
        }.getOrNull()
    }

    // 按 roomId 追加实时消息，并裁剪超长/过期条目（保留 2 小时）。
    // 同时累计通道活性计数、记录群助手身份（免网络兜底用）
    private fun appendLotteryLiveMsg(roomId: Long, msg: LotteryLiveMsg) {
        mLotteryLiveMsgCount.incrementAndGet()
        if (msg.fromUid > 0L && msg.nick.contains(LOTTERY_GROUP_ASSISTANT_NAME)) {
            mLiveAssistant[roomId] = msg.fromUid to msg.nick
        }
        val queue = mLotteryLiveMsgs.getOrPut(roomId) { ConcurrentLinkedQueue() }
        queue.add(msg)
        while (queue.size > 120) queue.poll()
        val expireAt = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        while (queue.peek()?.let { it.timeMs < expireAt } == true) queue.poll()
        // 不移除"已清空"的一级 key: isEmpty 判断与 remove(key, value) 之间非原子,
        // 并发线程经 getOrPut 拿到同一 queue 写入新消息后, 本次 remove 仍会成功执行,
        // 把刚写入的消息连同 queue 整体丢弃。空 queue 驻留有界(每条目 ~几十字节,
        // 房间数受用户访问行为约束), 2 小时过期裁剪已在上完成
    }

    // 取房间指定时刻之后的实时消息快照
    private fun liveMsgsSince(roomId: Long, sinceMs: Long): List<LotteryLiveMsg> {
        val queue = mLotteryLiveMsgs[roomId] ?: return emptyList()
        return queue.filter { it.timeMs >= sinceMs }
    }

    private fun main(context: android.content.Context): String {
        // 账号切换防护：持久化台账键自带账号段（见 daySetKey），内存态在此显式重置——
        // mMyImUid/mMyUk 是旧账号的身份标识，mRejectedUks 是旧账号的 uk 校验黑名单，
        // mLotteryLiveMsgs/mLiveAssistant/mCapturedRooms/mLotteryLiveMsgCount 是旧账号会话的旁路缓存；
        // 跨账号沿用会污染回复/回显/群助手判定。mSendError/mSuppressed/mSilentRoomStreak
        // 是单轮运行态，由下方既有逻辑重置，不在此列
        val accountKey = LotteryLedger.activeAccountId()
        if (mActiveAccountKey != accountKey) {
            if (mActiveAccountKey != null) {
                lotteryTsLog("一键抽奖 账号已切换（$mActiveAccountKey → $accountKey），重置内存态")
            }
            mMyImUid = 0L
            mMyUk = ""
            mRejectedUks.clear()
            mLotteryLiveMsgs.clear()
            mLiveAssistant.clear()
            mCapturedRooms.clear()
            mLotteryLiveMsgCount.set(0)
            mActiveAccountKey = accountKey
        }
        val rooms = getGroupRooms(context)
        if (rooms.isEmpty()) return "未获取到频道列表，请先打开消息页的聊天列表再重试"
        // 前置 UX 守卫: 活动不再贯穿全程持有(每房间现取, 见 prepareAndSend)
        if (runCatching { gm.tieba.tabswitch.util.getCurrentActivity() }.getOrNull() == null) {
            return "未获取到当前页面，请停留在贴吧内重试"
        }

        mSendError = ""
        mSuppressed = false
        mSilentRoomStreak = 0

        // 开跑前等实时旁路就绪：冷启动后长连接未通的窗口里旁路全盲（收不到回显与回复），
        // 送达/回复判定全部失据。繁忙群在链路就绪后秒级就有消息进来
        updateProgress(0, "准备中")
        if (mLotteryLiveMsgCount.get() == 0L) {
            val bypassDeadline = System.currentTimeMillis() + BYPASS_WAIT_MILLIS
            while (mLotteryLiveMsgCount.get() == 0L && System.currentTimeMillis() < bypassDeadline) {
                Thread.sleep(1000)
            }
            XposedBridge.log(
                "一键抽奖 实时旁路就绪=${mLotteryLiveMsgCount.get() > 0}，已捕获${mLotteryLiveMsgCount.get()}条"
            )
        }

        val myName = runCatching {
            XposedHelpers.callStaticMethod(
                findClass("com.baidu.tbadk.core.TbadkCoreApplication"), "getCurrentAccountNameShow"
            ) as? String
        }.getOrNull() ?: ""
        val myBdUid = runCatching {
            XposedHelpers.callStaticMethod(
                findClass("com.baidu.android.imsdk.account.AccountManager"), "getUid", context
            ) as? String
        }.getOrNull() ?: ""
        // IM 标识多体系并存：百度 uid（个人资料页的 ID 数字）、字符串 uk、数字 IM uid。
        // getUK 在 12.74 上返回数字（dex 签名 (Context)J），按数字取到即回显/消息 fromUser
        // 体系的本账号数字 uid；字符串 uk 拿不到（或本来就该转换）时由百度 uid 转换
        val ukRaw = runCatching {
            XposedHelpers.callStaticMethod(
                findClass("com.baidu.android.imsdk.account.AccountManager"), "getUK", context
            )
        }.getOrNull()
        if (ukRaw is Number && mMyImUid == 0L && ukRaw.toLong() > 0L) mMyImUid = ukRaw.toLong()
        val myUk = (ukRaw as? String) ?: myBdUid.takeIf { it.isNotEmpty() }?.let { bdUid ->
            runCatching {
                XposedHelpers.callStaticMethod(
                    findClass(LOTTERY_BIM_MANAGER), "getBdUKFromBdUid", bdUid
                ) as? String
            }.getOrNull()
        } ?: ""

        val tasks = rooms.map { LotteryRoomTask(it.first, it.second, myName, myBdUid, myUk) }
        // 第一步（准备阶段）：获取并登记房间总数，作为全程"总数 vs 成功数"核查的台账基准
        val totalCount = tasks.size
        XposedBridge.log(
            "一键抽奖开始：房间总数=$totalCount, myName=$myName, myBdUid=$myBdUid, myUk=$myUk, myImUid=$mMyImUid"
        )
        XposedBridge.log(
            "一键抽奖 准备阶段频道清单($totalCount): ${tasks.joinToString("、") { it.roomName }}"
        )
        if (myUk.isNotEmpty()) {
            lotteryTsLog("一键抽奖 uk自检: getBdUidFromBdUK($myUk)→'${bdUidFromUk(myUk)}'（应为$myBdUid）")
        }
        // 进度条时间线程上线：初始计划 = 发送+核查+静置+复核 的预估总时长；
        // 之后各阶段边界/每 10 间/补发轮次重估剩余时长，校正斜率；97% 封顶，完成时收尾匀速补满
        startBarTicker(
            tasks.size * 8000L + tasks.size * 1800L + REPLY_SETTLE_MILLIS + tasks.size * 1200L
        )
        try {
            // 第二步（发送阶段）：逐间进房发送口令（进度由时间线程驱动）
            tasks.forEachIndexed { index, task ->
                if (prepareAndSend(task)) {
                    val what = when {
                        task.confirmed && !task.sent -> "今日已领取，跳过发送"
                        task.sent && task.replySeen -> "已发送并获回复（第${task.attempts}次尝试）"
                        task.sent -> "已发送 ack=${task.sendAck}（未获回复）"
                        else -> "发送未完成（${task.failReason.ifEmpty { "ack=${task.sendAck}" }}）"
                    }
                    lotteryTsLog("一键抽奖(${index + 1}/$totalCount) ${task.roomName}: $what")
                } else {
                    destroyRepo(task)
                    lotteryTsLog("一键抽奖(${index + 1}/${tasks.size}) ${task.roomName}: 发送失败")
                }
                if ((index + 1) % 10 == 0 || index == tasks.size - 1) {
                    replanBar(
                        (tasks.size - index - 1) * 8000L + tasks.size * 1800L +
                            REPLY_SETTLE_MILLIS + tasks.size * 1200L
                    )
                }
            }

            // 阶段一收尾：对没能发出的房间（详情超时/进房失败等）原样重试一轮。
            // 无群助手（系统消息群/私聊等）与确定性失败（err(botUid=0)）的房间
            // 重试必然再失败, 不再列入; 补发只针对真正没发出去的房间；已发出的
            // 房间绝不重发
            val failedOnce = tasks.filter {
                !it.sent && !it.confirmed && it.attempts < MAX_SEND_ATTEMPTS &&
                    !it.noAssistant && !it.fatalFail
            }
            var retriedOk = 0
            if (failedOnce.isNotEmpty() && !mSuppressed) {
                lotteryTsLog("一键抽奖 阶段一重试：${failedOnce.size} 个未发出的房间")
                replanBar(
                    failedOnce.size * 7000L + tasks.size * 1800L + REPLY_SETTLE_MILLIS +
                        tasks.size * 1200L
                )
                failedOnce.forEach { task ->
                    if (prepareAndSend(task)) {
                        retriedOk++
                        val what = if (!task.sent) "今日已领取，跳过" else "已发送 ack=${task.sendAck}"
                        lotteryTsLog("一键抽奖 重试 ${task.roomName}: $what")
                    } else {
                        destroyRepo(task)
                        lotteryTsLog("一键抽奖 重试 ${task.roomName}: 仍失败（${task.failReason}）")
                    }
                }
            }

            // 第二步收尾：登记"成功发送频道"台账（口径见 isSentOk）
            val sentOkChannels = LinkedHashMap<Long, String>()
            fun recordSentOk() {
                tasks.forEach { task -> if (isSentOk(task)) sentOkChannels[task.roomId] = task.roomName }
            }
            recordSentOk()
            XposedBridge.log(
                "一键抽奖 发送完成：成功发送${sentOkChannels.size}/$totalCount 个频道" +
                    (if (retriedOk > 0) "（重试成功$retriedOk 个）" else "")
            )

            // 送达/回复证据收集：逐间核查。有回复即确认；仅在拿到未送达证据时补发一次
            tasks.forEachIndexed { index, task ->
                sweepRoom(task, allowResend = true)
                if ((index + 1) % 10 == 0 || index == tasks.size - 1) {
                    replanBar(
                        (tasks.size - index - 1) * 1800L + REPLY_SETTLE_MILLIS + tasks.size * 1200L
                    )
                }
                if (index < tasks.size - 1) Thread.sleep(SWEEP_FETCH_GAP_MILLIS)
            }

            // 第三步（核查阶段）：对照房间总数与成功频道数，排除已成功者得到遗漏名单
            // （无群助手与确定性失败的房间从遗漏名单剔除: 它们永远不可能成功,
            // 不进补发轮）
            recordSentOk()
            var missing = tasks.filter {
                !sentOkChannels.containsKey(it.roomId) && !it.noAssistant && !it.fatalFail
            }
            XposedBridge.log(
                "一键抽奖 核查：房间总数=$totalCount，成功发送=${sentOkChannels.size}，未成功=${missing.size}" +
                    (if (missing.isNotEmpty()) "（${missing.joinToString("、") { it.roomName }}）" else "")
            )

            // 第四步（补发阶段）：仅对未成功频道补发（prepareAndSend 内先复核送达证据防双发），
            // 每轮补发后重新核查，无进展即停
            var topUpRound = 0
            while (missing.isNotEmpty() && topUpRound < TOPUP_MAX_ROUNDS && !mSuppressed) {
                topUpRound++
                XposedBridge.log(
                    "一键抽奖 补发第${topUpRound}轮：${missing.size} 个频道" +
                        "（${missing.joinToString("、") { it.roomName }}）"
                )
                replanBar(missing.size * 13000L + REPLY_SETTLE_MILLIS + tasks.size * 1200L)
                var roundOk = 0
                missing.forEach { task ->
                    destroyRepo(task) // 丢弃旧 repo，补发重新走完整进房流程
                    if (prepareAndSend(task)) {
                        sweepRoom(task, allowResend = false)
                        if (isSentOk(task)) roundOk++
                        val what = when {
                            task.confirmed && !task.sent -> "今日已领取，跳过"
                            task.replySeen -> "已发送并获回复（第${task.attempts}次尝试）"
                            task.sent -> "已发送 ack=${task.sendAck}（未获回复）"
                            else -> "发送未完成（${task.failReason.ifEmpty { "ack=${task.sendAck}" }}）"
                        }
                        lotteryTsLog("一键抽奖 补发 ${task.roomName}: $what")
                    } else {
                        destroyRepo(task)
                        lotteryTsLog("一键抽奖 补发 ${task.roomName}: 仍失败（${task.failReason}）")
                    }
                }
                recordSentOk()
                missing = tasks.filter {
                    !sentOkChannels.containsKey(it.roomId) && !it.noAssistant && !it.fatalFail
                }
                XposedBridge.log(
                    "一键抽奖 补发第${topUpRound}轮后核查：成功发送${sentOkChannels.size}/$totalCount" +
                        (if (missing.isNotEmpty())
                            "，仍未成功${missing.size}（${missing.joinToString("、") { it.roomName }}）"
                        else "，全部频道已覆盖")
                )
                if (roundOk == 0 && missing.isNotEmpty()) {
                    lotteryTsLog("一键抽奖 补发第${topUpRound}轮无进展，停止补发")
                    break
                }
            }

            // 收尾：静置 REPLY_SETTLE_MILLIS（给群助手的延迟回复留到达时间，必经）后逐间复核
            repeat((REPLY_SETTLE_MILLIS / 1000).toInt()) { Thread.sleep(1000) }
            val pending = tasks.filter { it.sent && !it.confirmed }
            replanBar(pending.size * 1500L + 1500L)
            pending.forEach { sweepRoom(it, allowResend = false) }
        } finally {
            tasks.forEach(::destroyRepo)
            stopBarTickerFillTo100()
        }

        // 汇总（核查口径）：成功 = 群助手已响应（含当日已领取跳过）或口令已确认送达（回调 ok / 收到回显）。
        // 无群助手的房间（系统消息群/私聊等）不是抽奖目标, 不计入弹窗汇总
        val validTasks = tasks.filter { !it.noAssistant }
        val success = validTasks.count { isSentOk(it) }
        val failedTasks = validTasks.filter { !isSentOk(it) }
        val unconfirmed = validTasks.count { it.sent && !it.confirmed }
        val summary = buildString {
            append("抽奖完成：共${validTasks.size}个频道，成功$success 个")
            if (failedTasks.isNotEmpty()) {
                val names = failedTasks.take(3).joinToString("、") { it.roomName }
                append("，失败${failedTasks.size} 个（$names）")
                val reasons = failedTasks.groupingBy { it.failReason.ifEmpty { "异常" } }
                    .eachCount().entries.joinToString(" ") { "${it.key}×${it.value}" }
                append("，原因:$reasons")
            } else if (mSendError.isNotEmpty()) append(" 首个错误:$mSendError")
            if (unconfirmed > 0) {
                append("，其中$unconfirmed 个群助手回复待确认")
                if (mLotteryLiveMsgCount.get() == 0L) append("（实时通道未连接，无法确认回复）")
            }
        }
        lotteryTsLog("一键抽奖结束：$summary")
        return summary
    }

    // 群聊频道列表，来源与优先级：
    // 1. 当前账号 Room 库: tb_group_chat_room_<uid> + tb_message_center
    //    - 只查当前账号的库: 目录扫描会把已退出账号的残留库当候选, 其残留房间会顶掉
    //      当前账号的真实列表(实测旧账号库的 2 个房间让整轮抽奖只跑 2 个群)
    //    - 房间表 delete_time 需兼容 NULL(22.11 无默认值, 新行可能不写 0)
    //    - 22.11 房间表常为空, 消息中心列表落在同库 tb_message_center
    //      (gid/group_name/is_friend=0 为群聊), 是当前账号真实群列表的可靠来源
    // 2. 聊天页实时捕获的列表数据
    // 3. IMSDK 会话库
    // 多路按 roomId 去重合并; 失效/私聊/系统条目会在发送前因
    // "未找到群助手/详情超时"安全跳过, 不会误发
    private fun getGroupRooms(context: android.content.Context): List<Pair<Long, String>> {
        val merged = LinkedHashMap<Long, String>()

        for (uid in currentAccountUids()) {
            val dbFile = context.getDatabasePath("$uid.db")
            if (!dbFile.exists()) continue
            try {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                ).use { db ->
                    // 先修复被毒消息污染的时间戳，再取频道列表，避免台账基准带上被毒房间
                    // （只清 timestamp，latest_msg_id 是已读游标，见 LotteryPoisonGuard.repairPoisonedRoomTimestamps）
                    runCatching {
                        val cutoff = System.currentTimeMillis() + LOTTERY_FUTURE_POISON_TOLERANCE_MILLIS
                        db.execSQL(
                            "UPDATE tb_group_chat_room_$uid SET timestamp = 0 " +
                                "WHERE timestamp > $cutoff"
                        )
                    }
                    runCatching {
                        db.rawQuery(
                            "SELECT room_id, name FROM tb_group_chat_room_$uid " +
                                "WHERE delete_time IS NULL OR delete_time = 0",
                            null
                        ).use { cursor ->
                            while (cursor.moveToNext()) {
                                val roomId = cursor.getLong(0)
                                if (roomId > 0) {
                                    merged.putIfAbsent(roomId, cursor.getString(1) ?: roomId.toString())
                                }
                            }
                        }
                    }
                    runCatching {
                        db.rawQuery(
                            "SELECT gid, group_name, group_name_show FROM tb_message_center " +
                                "WHERE (is_delete IS NULL OR is_delete = 0) " +
                                "AND (is_friend = 0 OR is_friend IS NULL)",
                            null
                        ).use { cursor ->
                            while (cursor.moveToNext()) {
                                val roomId = cursor.getString(0)?.toLongOrNull() ?: continue
                                if (roomId > 0) {
                                    merged.putIfAbsent(
                                        roomId,
                                        cursor.getString(1) ?: cursor.getString(2) ?: roomId.toString()
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (tr: Throwable) {
                lotteryTsLog("一键抽奖读取频道库失败($uid) -- $tr")
            }
        }
        val dbCount = merged.size

        val capRooms = mCapturedRooms.map { it.key to it.value }.sortedBy { it.first }
        var imRooms: List<Pair<Long, String>> = emptyList()
        try {
            val sessions = XposedHelpers.callStaticMethod(
                findClass(LOTTERY_CHAT_MSG_MANAGER), "getGroupSession", context
            ) as? List<*>
            if (sessions != null) {
                imRooms = sessions.mapNotNull { session ->
                    session ?: return@mapNotNull null
                    val roomId = XposedHelpers.callMethod(session, "getContacter") as? Long
                        ?: return@mapNotNull null
                    if (roomId <= 0) return@mapNotNull null
                    val name = XposedHelpers.callMethod(session, "getName") as? String ?: roomId.toString()
                    roomId to name
                }.distinctBy { it.first }
            }
        } catch (tr: Throwable) {
            lotteryTsLog("一键抽奖读取IMSDK会话失败 -- $tr")
        }

        (capRooms + imRooms).forEach { (roomId, name) ->
            if (roomId > 0) merged.putIfAbsent(roomId, name)
        }
        lotteryTsLog(
            "一键抽奖 频道发现: 库$dbCount + 实时${capRooms.size} + IMSDK${imRooms.size}" +
                " → 去重后${merged.size}"
        )
        if (merged.isEmpty()) return emptyList()
        return merged.map { it.key to it.value }
    }

    // 当前账号 uid 候选（仅当前登录账号, 不做目录扫描——旧账号残留库会污染频道列表;
    // LotteryPoisonGuard.candidateAccountUids 的目录扫描仅供免打扰/时间戳修复等按账号隔离的场景使用）
    private fun currentAccountUids(): List<String> {
        val candidateUids = mutableListOf<String>()
        try {
            // getCurrentAccountId 返回 long, 需按 Number 兼容取值(强转 String 会得到 null)
            val accountId = XposedHelpers.callStaticMethod(
                findClass("com.baidu.tbadk.core.TbadkCoreApplication"), "getCurrentAccountId"
            )
            val accountIdStr = when (accountId) {
                is Number -> accountId.toLong().toString().takeIf { it != "0" }
                is String -> accountId.takeIf { it.isNotEmpty() }
                else -> null
            }
            // uid 会拼进 tb_group_chat_room_$uid 的 SQL, 与 candidateAccountUids
            // 对齐做纯数字校验(非数字时 rawQuery/UPDATE 必抛异常刷日志)
            accountIdStr?.let { if (it.matches(Regex("\\d+"))) candidateUids.add(it) }
        } catch (tr: Throwable) {
            lotteryTsLog("一键抽奖获取账号失败 -- $tr")
        }
        try {
            Adp.account?.let { if (it.isNotEmpty() && it.matches(Regex("\\d+"))) candidateUids.add(it) }
        } catch (ignored: Throwable) {
        }
        lotteryTsLog("一键抽奖 当前账号候选: $candidateUids")
        return candidateUids.distinct()
    }
    // 阶段一：进房 -> 定位群助手 -> 立即发送口令（不等回复，repo 留给后续阶段）。
    // 复刻手动长按头像 @群助手 的发送；发送节奏（距上一次实际发送 ≥ MIN_SEND_GAP_MILLIS）由 doSend 保障。
    // 每房间现取当前前台 Activity: 此前贯穿全程持有 Activity 引用, 长流程(最长20分钟)
    // 中 Activity 销毁后仍被钉在内存且 getPageContext 对已销毁实例执行
    private fun prepareAndSend(task: LotteryRoomTask): Boolean {
        val repoClass = findClass(LOTTERY_GROUP_CHAT_REPO)
        return try {
            val appCtx = getContext().applicationContext
            // 当日已领取台账先行判定：台账内房间无需进房（省去进房/详情各数秒的开销，
            // 重跑时绝大多数房间在此直接跳过）
            if (LotteryLedger.daySetLoad(appCtx, "claimed").contains(task.roomId.toString())) {
                task.confirmed = true
                lotteryTsLog("一键抽奖(${task.roomId}) 台账已领取，直跳")
                return true
            }
            // 活动自身持有真正的 TbPageContext（BdPageContextSupport.getPageContext）
            val activity = runCatching { getCurrentActivity() }.getOrNull()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                task.failReason = "无前台页面"
                return false
            }
            val pageContext = XposedHelpers.callMethod(activity, "getPageContext")
                ?: throw IllegalStateException("getPageContext is null")
            // 重试路径会在旧 repo 未销毁时再次进入本函数: 直接覆盖 task.repo 会把
            // 持有 TbPageContext(内含 Activity)与 Timer 的旧实例丢给 GC, 最终
            // tasks.forEach(::destroyRepo) 只清得到最后一个。覆盖前先销毁旧实例
            destroyRepo(task)
            val repo = repoClass.getConstructor(
                findClass("com.baidu.tbadk.TbPageContext"), Long::class.javaPrimitiveType!!
            ).newInstance(pageContext, task.roomId)
            task.repo = repo

            // 拉取房间详情（结构定位：签名 (FastRequest$b, ?, int) 的方法）。
            // 22.14 形态实证(2026-11-17)：形状过滤唯一命中 d0(FastRequest.b<ChatRoomDetail>, d67, int)，
            // 详情路径无影响；下方 name=="h0" 的回退分支在 22.12/22.13/22.14 均无声明（死分支，
            // 仅防未来形状过滤失效时无路径可用），勿据"22.14 用 h0"的旧注释修复
            val h0 = repoClass.declaredMethods.firstOrNull {
                it.parameterTypes.size == 3 && it.parameterTypes[2] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[0].name.endsWith("FastRequest\$b")
            } ?: repoClass.declaredMethods.firstOrNull {
                it.name == "h0" && it.parameterTypes.size == 3
            } ?: throw NoSuchMethodError("GroupChatRepo.h0(detail)")
            h0.isAccessible = true
            h0.invoke(repo, null, null, 0)

            // 房间详情字段按类型定位（不依赖混淆字段名）；首次超时重试一次（详情接口偶发限流）
            var detail: Any? = null
            val deadline = System.currentTimeMillis() + DETAIL_TIMEOUT_MILLIS
            while (System.currentTimeMillis() < deadline) {
                detail = runCatching {
                    gm.tieba.tabswitch.util.getObjectField(repo, LOTTERY_CHAT_ROOM_DETAIL)
                }.getOrNull() ?: runCatching { XposedHelpers.getObjectField(repo, "h") }.getOrNull()
                if (detail != null) break
                Thread.sleep(200)
            }
            if (detail == null) {
                lotteryTsLog("一键抽奖(${task.roomId}) 房间详情超时，重试一次")
                h0.invoke(repo, null, null, 0)
                val retryDeadline = System.currentTimeMillis() + DETAIL_TIMEOUT_MILLIS / 2
                while (System.currentTimeMillis() < retryDeadline) {
                    detail = runCatching {
                        gm.tieba.tabswitch.util.getObjectField(repo, LOTTERY_CHAT_ROOM_DETAIL)
                    }.getOrNull() ?: runCatching { XposedHelpers.getObjectField(repo, "h") }.getOrNull()
                    if (detail != null) break
                    Thread.sleep(200)
                }
            }
            if (detail == null) {
                task.failReason = "详情超时"
                if (mSendError.isEmpty()) mSendError = "房间详情获取超时"
                return false
            }

            // 进房：结构定位 (d67回调, FastRequest$b, int)；发送前必须 enterChatRoom。
            // 22.11-22.14 四版本反编译核实(2026-11-21 修正 2026-11-17 的误记): 该形状
            // 过滤在 22.12-22.14 **唯一命中进房方法本体**——22.14 为 V(d67, FastRequest$b, int)
            // (GroupChatRepo.java:1687, 体内 f.h/f.e/f.n 调 w57 进房链, 回调 t 即进房监听),
            // 22.13/22.12 为 V(c67/r57, FastRequest$b, int), 22.11 为 U(k57, FastRequest$b, int),
            // 形状四版本稳定。此前注释称"22.14 无命中、真身疑似 p0"系核对时漏看 V:
            // p0(FastRequest$b, int) 是 j 门控的详情重拉转发(GroupChatRepo.java:1987),
            // 按它定位会让"进房"退化为一次空转的详情刷新——勿改回。
            // 残余风险(待上机确认): invoke(repo,null,null,0) 传 null 回调, 进房成功回调
            // t.a 首行 this.a.a(...) 理论 NPE, 22.11 起结构一致且实测可用(异常被 IMSDK
            // 回调分发吞掉); 若上机暴露问题, 可用 java.lang.reflect.Proxy 生成 d67 真实回调
            val c0 = repoClass.declaredMethods.firstOrNull {
                it.parameterTypes.size == 3 && it.parameterTypes[2] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1].name.endsWith("FastRequest\$b")
            } ?: repoClass.declaredMethods.firstOrNull {
                it.name == "c0" && it.parameterTypes.size == 3
            } ?: throw NoSuchMethodError("GroupChatRepo.enter")
            c0.isAccessible = true
            c0.invoke(repo, null, null, 0)
            // 进房后停留随机时长再操作，避免固定节奏
            Thread.sleep(1000 + Random.nextLong(1500))

            // 定位本房间的群助手 uk：优先从机器人列表找（自定义名称的机器人仍用默认头像），
            // 找不到则从最近消息里找群助手发言兜底，并取回其显示名作为触发参数
            runCatching {
                val botList = XposedHelpers.callMethod(
                    XposedHelpers.callMethod(detail, "getBots"), "getBotList"
                ) as? List<*>
                // 命中即停：多个匹配时不让后面的匹配项(或空 uk)覆盖已取得的合法 uk
                botList?.let { bots ->
                    for (bot in bots) {
                        bot ?: continue
                        val user = XposedHelpers.callMethod(bot, "getUser") ?: continue
                        val n = XposedHelpers.callMethod(user, "getName") as? String ?: ""
                        val ns = XposedHelpers.callMethod(user, "getNameShow") as? String ?: ""
                        val portrait = XposedHelpers.callMethod(user, "getPortrait") as? String ?: ""
                        if (n.contains(LOTTERY_GROUP_ASSISTANT_NAME) || ns.contains(LOTTERY_GROUP_ASSISTANT_NAME) ||
                            portrait.contains(LOTTERY_GROUP_ASSISTANT_PORTRAIT_HASH)
                        ) {
                            val uk = XposedHelpers.callMethod(user, "getUk") as? String ?: ""
                            if (uk.isNotEmpty()) {
                                task.uk = uk
                                task.botName = ns.ifEmpty { n }
                                task.botPortrait = portrait
                                break
                            }
                        }
                    }
                }
            }
            if (task.uk.isEmpty()) {
                // 旁路里本房间群助手的实时发言（免网络）：签到群回复频繁，运行期间通常已捕获
                mLiveAssistant[task.roomId]?.let { (assistantUid, assistantNick) ->
                    task.uk = XposedHelpers.callStaticMethod(
                        findClass(LOTTERY_BIM_MANAGER), "getBdUKFromBdUid", assistantUid.toString()
                    ) as? String ?: ""
                    if (task.uk.isNotEmpty()) task.botName = assistantNick
                }
            }
            if (task.uk.isEmpty()) {
                // 历史里群助手的最近发言兜底（拉取偶发为空，仅作末位候选）
                val assistant = LotteryHistoryFetch.fetchGroupAssistantInfo(activity.applicationContext, task.roomId)
                if (assistant != null) {
                    task.uk = XposedHelpers.callStaticMethod(
                        findClass(LOTTERY_BIM_MANAGER), "getBdUKFromBdUid", assistant.first.toString()
                    ) as? String ?: ""
                    if (task.uk.isNotEmpty()) task.botName = assistant.second
                }
            }
            if (task.uk.isEmpty()) {
                task.failReason = "未找到群助手"
                // 房间属性而非瞬时故障: 标记后重试/补发轮不再碰这个房间
                task.noAssistant = true
                if (mSendError.isEmpty()) mSendError = "未找到本房间群助手"
                return false
            }

            // 同日登记判定（SharedPreferences 持久化，跨贴吧重启有效）：
            // 已确认领取 → 跳过；此前已发出（回复未确认）→ 只核查不重发；
            // 两者皆无 = 此前没发出去过的房间，才会真正发送
            val todayStart = LotteryLedger.todayStartMillis()
            val todayMsgs = liveMsgsSince(task.roomId, todayStart).toMutableList()
            LotteryHistoryFetch.fetchRecentMessages(appCtx, task.roomId)?.forEach { pojo ->
                extractLotteryLiveMsg(pojo)?.takeIf {
                    it.first == task.roomId && it.second.timeMs >= todayStart
                }?.let { todayMsgs.add(it.second) }
            }
            if (LotteryLedger.daySetLoad(appCtx, "claimed").contains(task.roomId.toString()) ||
                hasLotteryReply(todayMsgs, task)
            ) {
                LotteryLedger.daySetAdd(appCtx, "claimed", task.roomId)
                task.confirmed = true
                lotteryTsLog("一键抽奖(${task.roomId}) 今日群助手已响应过，跳过发送")
                return true
            }
            if (LotteryLedger.daySetLoad(appCtx, "sent").contains(task.roomId.toString())) {
                // 补发例外通道：发送后始终未等到群助手回复（无回显、未确认、无回复证据）
                // 才允许重发。覆盖两种情形：本次运行内发送未送达（sentAt>0），以及当日早前
                // 已发送且刚经历史核查确认无回复（ack=ok(今日已发)）——实测回复秒级到达，
                // 长时间无回复即未送达；重发最坏得到"已领过"回复，反而坐实送达。
                // 其余情形（刚跳过的已领取房间等）绝不重发
                val undeliveredThisRun = task.sent && !task.echoed && !task.confirmed &&
                    !task.replySeen &&
                    (task.sentAt > 0 || task.sendAck == "ok(今日已发)")
                if (!undeliveredThisRun) {
                    task.sent = true
                    // 只核查当日消息，避免昨日群助手的 @我 回复被误判为今日已确认
                    val hist = LotteryHistoryFetch.fetchRecentMessages(appCtx, task.roomId)
                        ?.mapNotNull { extractLotteryLiveMsg(it) }
                        ?.filter { it.first == task.roomId && it.second.timeMs >= todayStart }
                        ?.map { it.second }
                        .orEmpty()
                    if (hasLotteryReplyAtMe(hist, task)) {
                        task.confirmed = true
                        LotteryLedger.daySetAdd(appCtx, "claimed", task.roomId)
                        lotteryTsLog("一键抽奖(${task.roomId}) 今日早前已发送，核查已确认（未重发）")
                    } else {
                        task.sendAck = "ok(今日已发)"
                        lotteryTsLog("一键抽奖(${task.roomId}) 今日早前已发送，仅核查（未重发）")
                    }
                    return true
                }
                // 重发前复核送达证据：拿到群助手回复或自身回显即实际已送达，不重发
                val replyAtMe = hasLotteryReplyAtMe(todayMsgs, task)
                if (replyAtMe || hasOwnEcho(todayMsgs)) {
                    task.sent = true
                    if (replyAtMe) {
                        task.confirmed = true
                        LotteryLedger.daySetAdd(appCtx, "claimed", task.roomId)
                    } else {
                        task.echoed = true
                        task.sendAck = "ok(回显确认)"
                    }
                    XposedBridge.log(
                        "一键抽奖(${task.roomId}) 补发前复核已送达" +
                            "（${if (replyAtMe) "群助手回复" else "自身回显"}），不重发"
                    )
                    return true
                }
                // 核查判定未送达：移出当日已发登记，落到下方真正补发（发送成功后会重新登记）
                LotteryLedger.daySetRemove(appCtx, "sent", task.roomId)
                lotteryTsLog("一键抽奖(${task.roomId}) 核查判定未送达(ack=${task.sendAck})，执行补发")
            }

            sendWithReplyWait(appCtx, repo, task)
            true
        } catch (tr: Throwable) {
            task.failReason = tr.javaClass.simpleName
            if (mSendError.isEmpty()) mSendError = tr.javaClass.simpleName + ":" + tr.message
            Log.getStackTraceString(tr).let { XposedBridge.log(it) }
            false
        }
    }

    // 核查口径的"成功发送频道"：群助手已回复（本轮发送后等到，或当日已领取跳过），
    // 或收到自身回显（送达直接证据）。回调 ok 但始终无回复的房间按实测口径视为
    // 未送达（发送有时会被机器人侧静默吞掉，复刻手动"多点几次"才是可靠送达）
    private fun isSentOk(task: LotteryRoomTask): Boolean {
        return task.confirmed || task.replySeen || task.echoed
    }

    // 核查/复核：旁路（别人的消息、通道活性）+ 拉最近历史（@我的回复只在这里）合并判定。
    // 阶段内补发仅限发送回调明确报错（ack=err）的房间——"没等到回复"不作为补发依据
    // （回复本就延迟数分钟、自身回显不走多播，据此补发会打在已成功送达的房间上；
    // 没发出的房间由发送收尾重试与第三步核查后的补发轮负责）
    private fun sweepRoom(task: LotteryRoomTask, allowResend: Boolean) {
        if (!task.sent || task.confirmed) return
        val live = liveMsgsSince(task.roomId, task.sentAt - 10_000L)
        val history = LotteryHistoryFetch.fetchRecentMessages(getContext().applicationContext, task.roomId)
            ?.mapNotNull { extractLotteryLiveMsg(it) }
            ?.filter { it.first == task.roomId }
            ?.map { it.second }
            .orEmpty()
        // 历史是最近 60 条的原始快照, 不带时间窗: 昨日自己的口令回显在安静房间
        // 极易存活在窗口内, 直接合并会让 hasOwnEcho 把昨日回显误判成今日送达
        // (echoed=true → isSentOk 判成功 → 房间被排除出补发名单, 当日漏领)。
        // 与 liveMsgsSince/hasLotteryReplyAtMe 的窗口口径对齐: 按发送时刻前 10s 过滤
        val sinceMs = if (task.sentAt > 0) task.sentAt - 10_000L else LotteryLedger.todayStartMillis()
        val msgs = if (history.isEmpty()) live
        else (live + history).filter { it.timeMs >= sinceMs }.distinctBy { it.timeMs to it.content }
        if (!task.echoed && hasOwnEcho(msgs)) task.echoed = true
        if (hasLotteryReplyAtMe(msgs, task)) {
            task.confirmed = true
            LotteryLedger.daySetAdd(getContext().applicationContext, "claimed", task.roomId)
            lotteryTsLog("一键抽奖(${task.roomId})${if (task.resent) " 补发后" else ""} 已收到群助手回复")
            return
        }
        if (allowResend && task.sendAck.startsWith("err")) {
            val repo = task.repo
            if (repo != null) {
                lotteryTsLog("一键抽奖(${task.roomId}) 发送回调报错(ack=${task.sendAck})，补发一次")
                task.sendAck = try {
                    doSend(context = getContext().applicationContext, repo = repo, roomId = task.roomId,
                        uk = task.uk, botName = task.botName, botPortrait = task.botPortrait)
                } catch (tr: Throwable) {
                    if (mSendError.isEmpty()) mSendError = tr.javaClass.simpleName + ":" + tr.message
                    "err(${tr.javaClass.simpleName})"
                }
                task.resent = true
            }
        }
    }

    private fun destroyRepo(task: LotteryRoomTask) {
        val repo = task.repo ?: return
        task.repo = null
        runCatching {
            findClass(LOTTERY_GROUP_CHAT_REPO).declaredMethods.firstOrNull { it.name == "onDestroy" }?.let {
                it.isAccessible = true; it.invoke(repo)
            }
        }
    }

    // 服务端限流熔断：连续房间零回复时置位，后续房间只发一次、跳过补发（每轮运行重置）
    @Volatile
    private var mSuppressed = false

    // 连续零回复房间计数
    private var mSilentRoomStreak = 0

    // 发送并等待群助手回复：实测回复秒级到达（成功发送 3 秒内必有回音），超窗视为本次
    // 发送未被机器人接收，立即重试——复刻手动"多点几次才有回复"的行为。
    // 回调报错与无回复都重试，最多 MAX_SEND_ATTEMPTS 次；仅当通道可见（旁路有流量或
    // 拉历史可用）才重试，避免冷启动全盲时把已送达误判成未送达而盲目重发；
    // 服务端限流熔断（连续房间零回复）后只发一次，避免整轮空烧
    private fun sendWithReplyWait(appCtx: android.content.Context, repo: Any, task: LotteryRoomTask) {
        val maxAttempts = if (mSuppressed) 1 else MAX_SEND_ATTEMPTS
        var attempt = 0
        var roomGotReply = false
        while (attempt < maxAttempts) {
            attempt++
            task.attempts = attempt
            task.sentAt = System.currentTimeMillis()
            task.sendAck = doSend(appCtx, repo, task.roomId, task.uk, task.botName, task.botPortrait)
            if (task.sendAck.startsWith("err")) {
                lotteryTsLog("一键抽奖(${task.roomId}) 第${attempt}次发送失败 ack=${task.sendAck}")
                // uk 换算失败是确定性的, 重试只会空烧重试次数: 置位 fatalFail 后
                // 该房间从阶段一重试与补发轮名单剔除(不重付进房+详情流程),
                // 结果仍计入最终汇总的失败项
                if (task.sendAck == "err(botUid=0)") {
                    task.fatalFail = true
                    break
                }
                if (!channelAlive(task)) break
                continue
            }
            task.sent = true
            LotteryLedger.daySetAdd(appCtx, "sent", task.roomId)
            if (waitAssistantReply(appCtx, task)) {
                task.replySeen = true
                task.confirmed = true
                roomGotReply = true
                LotteryLedger.daySetAdd(appCtx, "claimed", task.roomId)
                lotteryTsLog("一键抽奖(${task.roomId}) 第${attempt}次发送后已收到群助手回复")
                break
            }
            if (attempt < maxAttempts) {
                // 守卫要约束"重试"本身而不只是日志：通道全盲时停止重发，避免盲发+误触熔断
                if (!channelAlive(task)) break
                XposedBridge.log(
                    "一键抽奖(${task.roomId}) 第${attempt}次发送后 ${REPLY_WAIT_MILLIS / 1000}s 未收到回复，重试"
                )
            }
        }
        if (roomGotReply) {
            mSilentRoomStreak = 0
        } else if (task.sent) {
            mSilentRoomStreak++
            if (!mSuppressed && mSilentRoomStreak >= SILENT_STREAK_BREAKER) {
                mSuppressed = true
                XposedBridge.log(
                    "一键抽奖 连续${mSilentRoomStreak}个房间发送后零回复，判定服务端限流，" +
                        "后续每房间只发一次、跳过补发"
                )
            }
        }
    }

    // 回复等待窗口：旁路实时消息 + 进房拉历史合并判定，轮询至窗口关闭
    private fun waitAssistantReply(appCtx: android.content.Context, task: LotteryRoomTask): Boolean {
        val deadline = System.currentTimeMillis() + REPLY_WAIT_MILLIS
        var doFetch = true
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(REPLY_POLL_GAP_MILLIS)
            val msgs = liveMsgsSince(task.roomId, task.sentAt - 10_000L).toMutableList()
            if (doFetch) {
                LotteryHistoryFetch.fetchRecentMessages(appCtx, task.roomId)?.mapNotNull { extractLotteryLiveMsg(it) }
                    ?.filter { it.first == task.roomId }?.map { it.second }?.let { msgs.addAll(it) }
            }
            doFetch = !doFetch
            if (hasLotteryReplyAtMe(msgs, task)) return true
        }
        return false
    }

    // 通道活性：旁路有流量或拉历史可用，才允许自动重试（防全盲误判引发整轮重发）
    private fun channelAlive(task: LotteryRoomTask): Boolean {
        if (mLotteryLiveMsgCount.get() > 0) return true
        return LotteryHistoryFetch.fetchRecentMessages(getContext().applicationContext, task.roomId) != null
    }

    // 单次发送：TbTextMsg(+at_user_list+robot_params) -> repo 父类补全 -> 转换器(自适应学习) -> 包装器(形态定位)。
    // 返回发送应答描述：ok(...) / err(...) / miss（挂真实 listener 拿应答，发送失败不再不可见）
    private fun doSend(
        context: android.content.Context, repo: Any, roomId: Long,
        uk: String, botName: String, botPortrait: String
    ): String {
        // 防风控底线：距上一次实际发送不足（最小间隔+随机抖动）则补足等待
        val gapLeft = mLastSendAt + MIN_SEND_GAP_MILLIS +
            Random.nextLong(SEND_GAP_JITTER_MILLIS) - System.currentTimeMillis()
        if (gapLeft > 0) Thread.sleep(gapLeft)

        val botUidStr = XposedHelpers.callStaticMethod(
            findClass(LOTTERY_BIM_MANAGER), "getBdUidFromBdUK", uk
        ) as? String ?: "0"
        // 换算结果可能为空串/非数字，parseLong 需兜底，否则该房间发送直接 NumberFormatException
        val botUid = botUidStr.toLongOrNull() ?: 0L
        // uk 换算失败时 at 条目 uid=0, 服务端不会触发群助手——照发只会空烧满
        // MAX_SEND_ATTEMPTS 重试+补发轮且无线索, 直接按失败分类交回上层
        if (botUid == 0L) {
            lotteryTsLog("一键抽奖($roomId) uk 换算 botUid 失败(uk=$uk), 跳过发送")
            return "err(botUid=0)"
        }

        // 走客户端自己的生产链路：ck9.A 补全字段（含气泡等）→ 转换器生成 SDK 消息 → f96.o 发送
        val tbMsgClass = findClass("com.baidu.tieba.im.lib.socket.msg.TbTextMsg")
        val tbMsg = XposedHelpers.callStaticMethod(tbMsgClass, "create", "@$botName$LOTTERY_COMMAND")
        val atTypeUser = XposedHelpers.getStaticObjectField(
            findClass("com.baidu.tieba.im.lib.socket.msg.TbAtUserInfo\$AtType"), "USER"
        )
        val tbAtInfo = XposedHelpers.callStaticMethod(
            findClass("com.baidu.tieba.im.lib.socket.msg.TbAtUserInfo"), "create",
            atTypeUser, botUid, botName, botPortrait, 0
        )
        XposedHelpers.callMethod(tbMsg, "addAtUserInfo", tbAtInfo)
        // 机器人触发参数（生产发送必带，缺了群助手不响应）
        val robotParams = java.util.HashMap<String, Any>()
        robotParams["scene"] = "tieba_group_chat"
        robotParams["type"] = "10004"
        XposedHelpers.callMethod(tbMsg, "setRobotParams", robotParams)

        // repo.z -> ck9.A：补 sessionId/msgId/msgKey/发送者/气泡 等全部字段
        // （结构定位：沿父类链找唯一参数为 TbBaseMsg 的非合成方法，12.63=ck9 / 12.74=c8a 均适用）
        val tbBaseMsgClass = findClass("com.baidu.tieba.im.lib.socket.msg.TbBaseMsg")
        var zMethod: java.lang.reflect.Method? = null
        var clazz: Class<*>? = repo.javaClass
        while (clazz != null && zMethod == null) {
            zMethod = clazz.declaredMethods.firstOrNull {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == tbBaseMsgClass && !it.isSynthetic
            }
            clazz = clazz.superclass
        }
        zMethod ?: throw NoSuchMethodError("repo.send(TbBaseMsg)")
        zMethod.isAccessible = true
        zMethod.invoke(repo, tbMsg)

        // 转换器：三级回退。
        // ① 22.11 具名链（vea/wea/bfa 的漂移名）：注册表 ekb.c.b(TbTextMsg)→lkb 转换器，
        //    群聊 marker=okb.a，转换为 fkb.b(xkb,TbBaseMsg)。注册表按消息类型精确查实例，
        //    且不经 clb.a 包装（它 catch 后返回 null 吞异常），失败原因直接可见——首选。
        // ② 流量自学习链（未来版本混淆名漂移后的兜底）。局限：22.11 注册表按消息类型各持
        //    一个转换器实例（lkb=文本、ikb=系统消息…），自学习从流量捕获到的实例类型取决于
        //    首次流量，跨类型复用会 ClassCastException——由 marker 兼容性检查与异常捕获兜住，
        //    失败后落到 ③
        // ③ 12.x 旧版硬编码链（dea/sp9 系列）
        val chatMsgSdkClass = findClass(LOTTERY_CHAT_MSG_SDK)
        var chatMsg: Any? = null
        val groupMarker = runCatching {
            XposedHelpers.getStaticObjectField(findClass("com.baidu.tieba.okb"), "a")
        }.getOrNull()
        if (chatMsg == null) {
            runCatching {
                val registry = XposedHelpers.getStaticObjectField(findClass("com.baidu.tieba.ekb"), "c")
                    ?: error("ekb.c 注册表为 null")
                val wkb = XposedHelpers.callMethod(registry, "b", tbMsg.javaClass)
                    ?: error("ekb 注册表未命中 ${tbMsg.javaClass.name}")
                // groupMarker 为 null 的唯一原因是外层同一调用已失败(okb.a 反射抛错,
                // 字段查找是确定性的, 重复调用必然同样失败)——此前"内层重试一次"是
                // 死回退, 改为显式报错, 失败原因在日志里可读
                val marker = groupMarker ?: error("群聊 marker(okb.a)反射失败, 22.11 转换链不可用")
                chatMsg = XposedHelpers.callMethod(wkb, "b", marker, tbMsg)
                if (chatMsg != null) {
                    lotteryTsLog("一键抽奖($roomId) 22.11转换链成功: wkb=${wkb.javaClass.name}")
                }
            }.onFailure {
                lotteryTsLog("一键抽奖($roomId) 22.11转换链异常: ${it.javaClass.name}: ${it.message}")
            }
        }
        if (chatMsg == null) {
            // 自学习链（未来版本混淆名漂移后的兜底）：实例/方法已由 LotteryChainLearner
            // 从流量捕获；未学习到时返回 null 直接落旧版回退链
            chatMsg = LotteryChainLearner.convertViaLearnedChain(
                tbMsg, tbBaseMsgClass, chatMsgSdkClass, groupMarker
            )
            if (chatMsg != null) {
                lotteryTsLog("一键抽奖($roomId) 自适应转换链成功")
            }
        }
        if (chatMsg == null) {
            // 旧版硬编码回退（12.63=sp9/qq9/cq9/f96，12.74=dea/bfa/nea/cn6）
            for ((registryName, callerName, markerField) in listOf(
                Triple("com.baidu.tieba.dea", "com.baidu.tieba.bfa", "com.baidu.tieba.nea"),
                Triple("com.baidu.tieba.sp9", "com.baidu.tieba.qq9", "com.baidu.tieba.cq9")
            )) {
                runCatching {
                    val registry = XposedHelpers.getStaticObjectField(findClass(registryName), "c")
                    val converter = XposedHelpers.callMethod(registry, "b", tbMsg.javaClass)
                    val marker = XposedHelpers.getStaticObjectField(findClass(markerField), "a")
                    chatMsg = XposedHelpers.callStaticMethod(findClass(callerName), "a", converter, marker, tbMsg)
                }
                if (chatMsg != null) break
            }
        }
        chatMsg ?: throw NoSuchMethodError("msg converter")

        // 发送：包装器方法形态 (Context, long, ChatMsg, listener)，自带安全参数。
        // 22.11+ 优先从 repo 字段按形态定位包装器实例（12.74 中即构造器里 this.f=cn6.b()
        // 的 dn6 实例，混淆名漂移后实例仍挂在 repo 上）；失败回退旧版硬编码类名
        val ack = SendAck()
        var invoked = false
        runCatching {
            // 沿父类链收集 repo 的全部字段值，筛出持有 (Context,long,ChatMsg,listener) 方法的对象
            val wrappers = ArrayList<Pair<Any, java.lang.reflect.Method>>()
            val seen = java.util.IdentityHashMap<Any, Boolean>()
            var fc: Class<*>? = repo.javaClass
            while (fc != null) {
                for (f in fc.declaredFields) {
                    val w = runCatching { f.isAccessible = true; f.get(repo) }.getOrNull() ?: continue
                    if (w.javaClass.name.startsWith("java.") || seen.put(w, true) == true) continue
                    for (m in w.javaClass.methods) {
                        if (m.parameterTypes.size == 4 &&
                            m.parameterTypes[0].isAssignableFrom(context.javaClass) &&
                            m.parameterTypes[1] == Long::class.javaPrimitiveType &&
                            m.parameterTypes[2].isAssignableFrom(chatMsgSdkClass) &&
                            m.parameterTypes[3].isInterface
                        ) {
                            wrappers.add(w to m)
                        }
                    }
                }
                fc = fc.superclass
            }
            for ((w, m) in wrappers) {
                runCatching {
                    val listenerType = m.parameterTypes[3]
                    val listener = java.lang.reflect.Proxy.newProxyInstance(
                        sClassLoader, arrayOf(listenerType)
                    ) { _, method, args ->
                        ack.onCallback(method.name, args)
                        // 回调接口若含基本类型返回值的方法, 返回 null 会在宿主调用点
                        // 拆箱 NPE, 按方法返回类型派发安全零值(void/引用类型仍为 null)
                        safeConstantFor(method.returnType, 0, false)
                    }
                    m.invoke(w, context, roomId, chatMsg, listener)
                    mLastSendAt = System.currentTimeMillis()
                    invoked = true
                    lotteryTsLog("一键抽奖($roomId) 自适应包装器发送: ${w.javaClass.name}.${m.name}")
                }
                if (invoked) break
            }
        }
        if (!invoked) {
            // 具名发送包装器（按版本候选）: 22.11=d57.getInstance().p(Context,long,ChatMsg,回调)
            // （旧 cn6.b().o 的漂移名；u57 是接口，Proxy 挂真实 listener 拿应答）;
            // 22.12 发送主路径为 k57.a() 返回的 l57.p, 22.13 轮换为 v57.a()→v57.p,
            // 22.14 v57 被宿主回收复用（视频工具服务）, 轮换为 w57.a()→w57.p
            // (同 4 参形态, 新名在前)
            for (senderName in listOf(
                "com.baidu.tieba.w57", "com.baidu.tieba.v57",
                "com.baidu.tieba.k57", "com.baidu.tieba.d57"
            )) {
                if (invoked) break
                runCatching {
                    val sender = XposedHelpers.callStaticMethod(findClass(senderName), "a")
                        ?: error("$senderName.a() 为 null")
                    val p = sender.javaClass.methods.firstOrNull {
                        it.name == "p" && it.parameterTypes.size == 4 &&
                            chatMsgSdkClass.isAssignableFrom(it.parameterTypes[2])
                    } ?: throw NoSuchMethodError("$senderName.p")
                    val listenerType = p.parameterTypes[3]
                    val listener = if (listenerType.isInterface) {
                        java.lang.reflect.Proxy.newProxyInstance(
                            sClassLoader, arrayOf(listenerType)
                        ) { _, method, args ->
                            ack.onCallback(method.name, args)
                            // 基本类型返回值返回 null 会在宿主调用点拆箱 NPE,
                            // 按方法返回类型派发安全零值(void/引用类型仍为 null)
                            safeConstantFor(method.returnType, 0, false)
                        }
                    } else null
                    p.invoke(sender, context, roomId, chatMsg, listener)
                    mLastSendAt = System.currentTimeMillis()
                    invoked = true
                    lotteryTsLog("一键抽奖($roomId) 具名包装器发送: $senderName.p")
                }.onFailure {
                    lotteryTsLog("一键抽奖($roomId) $senderName.p 发送异常: ${it.javaClass.name}: ${it.message}")
                }
            }
        }
        if (!invoked) {
            for (wrapperName in listOf("com.baidu.tieba.cn6", "com.baidu.tieba.f96")) {
                runCatching {
                    val g96 = XposedHelpers.callStaticMethod(findClass(wrapperName), "b")
                    val o = g96.javaClass.methods.firstOrNull { it.name == "o" && it.parameterTypes.size == 4 }
                        ?: throw NoSuchMethodError("g96.o")
                    val listenerType = o.parameterTypes[3]
                    val listener = if (listenerType != null && listenerType.isInterface) {
                        java.lang.reflect.Proxy.newProxyInstance(
                            sClassLoader, arrayOf(listenerType)
                        ) { _, method, args ->
                            ack.onCallback(method.name, args)
                            // 基本类型返回值返回 null 会在宿主调用点拆箱 NPE,
                            // 按方法返回类型派发安全零值(void/引用类型仍为 null)
                            safeConstantFor(method.returnType, 0, false)
                        }
                    } else null
                    o.invoke(g96, context, roomId, chatMsg, listener)
                    mLastSendAt = System.currentTimeMillis()
                    invoked = true
                }
                if (invoked) break
            }
        }
        ack.await(SEND_ACK_WAIT_MILLIS)
        val result = ack.describe()
        if (result.startsWith("err")) {
            lotteryTsLog("一键抽奖($roomId) 发送回调报错: $result")
        } else {
            lotteryTsLog("一键抽奖($roomId) 发送回调: $result")
        }
        return result
    }

    // 发送应答收集：listener 的首个回调视为应答；回调参数里首个数字按结果码解读（0/200 视为成功）
    private class SendAck {
        private val latch = java.util.concurrent.CountDownLatch(1)

        @Volatile
        private var summary: String? = null

        fun onCallback(methodName: String, args: Array<Any?>?) {
            if (summary != null) return
            var code: Long? = null
            var text = ""
            args?.forEach { a ->
                when (a) {
                    is Number -> if (code == null) code = a.toLong()
                    is String -> if (text.isEmpty()) text = a
                    else -> {}
                }
            }
            val ok = code == null || code == 0L || code == 200L
            summary = (if (ok) "ok" else "err") + "(" + methodName + ":" + (code ?: "-") +
                (if (text.isNotEmpty()) ",$text" else "") + ")"
            latch.countDown()
        }

        fun await(millis: Long) {
            latch.await(millis, TimeUnit.MILLISECONDS)
        }

        fun describe(): String {
            return summary ?: "miss"
        }
    }

    // 发送后的响应确认，三级识别：
    // 1) ID：at 条目 uk/数字 uid 精确比对（含服务端校验回学的 uk）；
    // 2) 服务端校验回学：@我昵称的候选 uk 经 getBdUidFromBdUK 换算回本账号 uid 才认；
    // 3) 兜底：仅认群助手名义消息里"@完整昵称（后随空白/结尾）"的点名并打日志——
    //    昵称可重名，但配合完整 @ 边界误判概率极低，且只影响"确认"，不影响补发判定。
    // 在 @我 判定之上必须叠加领取响应词过滤（与 hasLotteryReply 同一套 LOTTERY_CLAIM_REPLY_KEYS）：
    // 任意 @我的助手回复（如签到引导"完成签到才能领取"）不得据此确认领取或登记台账
    private fun hasLotteryReplyAtMe(msgs: List<LotteryLiveMsg>, task: LotteryRoomTask): Boolean {
        if (mMyUk.isEmpty()) learnMyUk(msgs, task)
        // 重跑场景 sentAt 可能为 0(当日早前已发送分支)，此时按当日零点起算，避免把昨日消息算进窗口
        val sinceMs = if (task.sentAt > 0) task.sentAt - 10_000L else LotteryLedger.todayStartMillis()
        for (m in msgs) {
            if (m.timeMs < sinceMs) continue
            if (LOTTERY_CLAIM_REPLY_KEYS.none { m.content.contains(it) }) continue
            // ID 命中必须以群助手名义为前提，否则任何群成员 @我 都会被误判为"已领取"
            if (isAssistantNick(m, task) && atContainsMe(m, task)) return true
            if (isAssistantNick(m, task) && mentionsMe(m.content, task.myName)) {
                lotteryTsLog("一键抽奖(${task.roomId}) ID未命中，昵称兜底确认: atUks=${m.atUks}")
                return true
            }
        }
        return false
    }

    // 当天是否已领取过：群助手回复按 ID 点名本账号，且内容命中任一领取响应词。
    // 关键词区分抽奖响应与签到引导（"完成签到才能领取"不含这些词），避免误判为已领取
    private fun hasLotteryReply(msgs: List<LotteryLiveMsg>, task: LotteryRoomTask): Boolean {
        if (mMyUk.isEmpty()) learnMyUk(msgs, task)
        val sinceMs = LotteryLedger.todayStartMillis()
        for (m in msgs) {
            if (m.timeMs < sinceMs) continue
            if (LOTTERY_CLAIM_REPLY_KEYS.none { m.content.contains(it) }) continue
            // 只认群助手名义消息，防止普通群成员消息误判
            if (isAssistantNick(m, task) && atContainsMe(m, task)) return true
            if (isAssistantNick(m, task) && mentionsMe(m.content, task.myName)) return true
        }
        return false
    }

    // 内容是否以完整"@昵称"形式点名本账号：@ 紧跟昵称，其后必须是空白或结尾。
    // Pattern 按 myName 缓存: 轮询周期内每房间多条消息重复判定, 同一昵称
    // 反复现场编译 Pattern 纯属浪费(运行期内 task.myName 恒定)
    @Volatile
    private var sMentionPattern: Pair<String, Regex>? = null

    private fun mentionsMe(content: String, myName: String): Boolean {
        if (myName.isEmpty()) return false
        val cached = sMentionPattern
        val pattern = if (cached != null && cached.first == myName) cached.second else {
            Regex("@${Regex.escape(myName)}(?=\\s|$)").also { sMentionPattern = myName to it }
        }
        return pattern.containsMatchIn(content)
    }

    // 回学本账号在消息 at 条目里的真实 uk。昵称只用来"筛选候选回复"（@我 的消息），
    // 认定与否全看服务端换算：候选 uk 经 getBdUidFromBdUK 换算回本账号百度 uid 才予认定——
    // 同昵称的其他人换算出的 uid 不同，过不了这道校验。已否决的候选不再重复换算
    private fun learnMyUk(msgs: List<LotteryLiveMsg>, task: LotteryRoomTask) {
        if (task.myName.isEmpty() || task.myBdUid.isEmpty()) return
        for (m in msgs) {
            if (!isAssistantNick(m, task)) continue
            if (!m.content.contains(task.myName)) continue
            for (candidate in m.atUks) {
                if (candidate.isEmpty() || candidate == task.myUk || !mRejectedUks.add(candidate)) continue
                val uid = bdUidFromUk(candidate)
                if (uid == task.myBdUid) {
                    mMyUk = candidate
                    lotteryTsLog("一键抽奖 服务端校验回学本账号uk=$candidate（getBdUidFromBdUK→$uid）")
                    return
                }
                lotteryTsLog("一键抽奖 uk候选校验不符: $candidate → '$uid' ≠ ${task.myBdUid}")
            }
        }
    }

    // 消息发送者是否群助手名义：默认名"群助手"，或本房间已识别的机器人显示名（自定义名机器人）
    private fun isAssistantNick(m: LotteryLiveMsg, task: LotteryRoomTask): Boolean {
        if (m.nick.contains(LOTTERY_GROUP_ASSISTANT_NAME)) return true
        return task.botName.isNotEmpty() && task.botName != LOTTERY_GROUP_ASSISTANT_NAME && m.nick == task.botName
    }

    // uk→百度 uid 换算（doSend 给机器人 at 条目取 uid 用的同一接口，离线可用）
    private fun bdUidFromUk(uk: String): String {
        val v = runCatching {
            XposedHelpers.callStaticMethod(findClass(LOTTERY_BIM_MANAGER), "getBdUidFromBdUK", uk)
        }.getOrNull()
        return when (v) {
            is Number -> v.toLong().toString()
            is String -> v
            else -> ""
        }
    }

    // 本账号口令的回显（发送后数秒内经多播路径回来）：仅按数字 uid 精确比对，
    // 不用昵称（昵称可重名）。数字 uid 来源：getUK 数字返回、uk 命中的 at 条目回学。
    // uid 尚未拿到时放弃回显判定（宁缺勿错，补发另有"该房间有流量"证据门槛兜底）
    private fun hasOwnEcho(msgs: List<LotteryLiveMsg>): Boolean {
        if (mMyImUid == 0L) return false
        return msgs.any { it.fromUid == mMyImUid && it.content.contains("领取今日福利") }
    }

    // at 列表包含本账号即视为被点名；uk（转换所得 + 服务端校验回学）与数字 uid 都比对
    private fun atContainsMe(m: LotteryLiveMsg, task: LotteryRoomTask): Boolean {
        val myBdUidNum = task.myBdUid.toLongOrNull()
        if (mMyImUid != 0L && m.atUids.contains(mMyImUid)) return true
        if (myBdUidNum != null && m.atUids.contains(myBdUidNum)) return true
        if (m.atUks.isEmpty()) return false
        val myIds = hashSetOf<String>()
        if (task.myUk.isNotEmpty()) myIds.add(task.myUk)
        if (mMyUk.isNotEmpty()) myIds.add(mMyUk)
        if (task.myBdUid.isNotEmpty()) myIds.add(task.myBdUid)
        if (myIds.isEmpty()) return false
        return m.atUks.any { it.isNotEmpty() && it in myIds }
    }

    // at 条目上的 ID 字符串（uk 系），按候选 getter 顺序试探;
    // 22.12 条目为纯公开字段 POJO（无任何 getter）, getter 全空后回退读公开字段
    private fun firstIdString(at: Any): String {
        for (g in listOf("getAtBdUk", "getUk", "getBdUk")) {
            val v = runCatching { XposedHelpers.callMethod(at, g) as? String }.getOrNull()
            if (!v.isNullOrEmpty()) return v
        }
        for (f in listOf("atBdUk", "uk", "bdUk")) {
            val v = runCatching { XposedHelpers.getObjectField(at, f) as? String }.getOrNull()
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }

    // at 条目上的数字 ID，按候选 getter 顺序试探（getAtUid/getUid/getBdUid）;
    // 22.12 无数字 ID getter/字段, 由 ChatMsg.getAtUsers() 在调用侧兜底
    private fun firstIdLong(at: Any): Long {
        for (g in listOf("getAtUid", "getUid", "getBdUid", "getUk")) {
            val v = runCatching { XposedHelpers.callMethod(at, g) as? Number }.getOrNull()
            if (v != null && v.toLong() > 0L) return v.toLong()
        }
        return 0L
    }
}
