package gm.tieba.tabswitch.hooker.auto

import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XC_MethodHook.XC_MethodReplacement
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers

// 自适应转换链学习子系统：旁听 TbTextMsg 构造从调用栈识别转换器类，挂钩其双向
// 转换方法；接收方向替换为带毒消息屏蔽的实现，发送方向只做链路学习。
// 从 OneKeyLottery 原文搬移（逐字保留注释）。
internal object LotteryChainLearner {

    // 自适应转换链识别失败的最大重试次数（每次重试都抓调用栈+扫描方法，成本高）
    private const val MAX_LEARN_ATTEMPTS = 5

    // —— 22.11+ 自适应转换链（流量自学习）——
    // 混淆类名随版本漂移（12.63=sp9…/12.74=dea…，22.11 全部失效），不再硬编码：
    // vea 文本转换器与 wea 标记器从收/发消息流量自动捕获（同一消息对收发共用同一 vea 实例，
    // 收消息持续发生故无需手动触发）；发送包装器从 repo 字段按方法形态定位（12.74 中
    // 即构造器里 this.f=cn6.b() 的 dn6 实例）。旧版硬编码链在 doSend 中保留为回退
    @Volatile
    private var mConvVe: Any? = null
    @Volatile
    private var mConvMarker: Any? = null

    // 学习时定位到的发送方向转换方法（可能声明在转换器基类上，invoke 时按实例虚分派）
    @Volatile
    private var mConvSendMethod: java.lang.reflect.Method? = null
    @Volatile
    private var mConvHooked = false
    @Volatile
    private var mChainLearned = false

    // 转换链识别失败重试计数（见 hook() 中 TbTextMsg 构造 hook 的上限保护）
    private val mLearnAttempts = java.util.concurrent.atomic.AtomicInteger(0)

    // 链路是否已学习成功（供主类开跑前等待就绪）
    internal val isChainLearned: Boolean get() = mChainLearned

    // 自适应转换链学习：旁听 TbTextMsg 构造（收/发消息必经），从客户端调用栈识别
    // vea 转换器类（同时具备 (wea,TbBaseMsg子类)->ChatMsg 与 (wea,ChatMsg子类)->TbBaseMsg
    // 两形态），挂钩其转换方法；下一次收/发流量即捕获 converter(this) 与 marker(arg0)。
    // 22.11 起混淆类名漂移（dea/bfa/nea 全部失效），不再硬编码，详见 doSend
    internal fun install() {
        runCatching {
            val tbMsgClass = findClass("com.baidu.tieba.im.lib.socket.msg.TbTextMsg")
            XposedBridge.hookAllConstructors(tbMsgClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (mConvHooked || mChainLearned) return
                    // 识别失败(版本漂移导致栈里找不到转换器)时最多重试 MAX_LEARN_ATTEMPTS 次:
                    // 每次学习都要抓调用栈+扫描方法, 无上限会让每条 IM 消息构造都空转一次
                    if (mLearnAttempts.incrementAndGet() > MAX_LEARN_ATTEMPTS) return
                    runCatching { learnConverterClasses() }
                        .onFailure { lotteryTsLog("一键抽奖 转换链学习异常: $it") }
                }
            })
        }.onFailure { lotteryTsLog("一键抽奖 hook(转换链学习)注册失败: $it") }
    }

    // 从当前调用栈（TbTextMsg 构造处）识别 vea 转换器类，挂钩其双向转换方法：
    // 发送方向只做链路学习（供抽奖发送使用）；接收方向替换为带毒消息屏蔽的实现——
    // 客户端转换器对未知类型/未知 notify_cmd 的消息直接抛异常，多播、进房同步、
    // 历史加载全走这同一个转换器，一个毒消息即可让频道卡死（收不到任何新消息）。
    // 转换异常（含毒消息判定命中）时直接返回 null 丢弃该条（既有行为，审查轮仅
    // 修正注释——旧注释"以占位文本消息顶替"描述的实现从未存在）: 批次内其余
    // 条目照常转换，该条不产生任何数据。宿主对 null 转换结果的容忍度未经专项
    // 实证（毒消息样本触发路径较罕见），若模拟器复现毒消息场景异常需回查此处；
    // 不构造占位消息，避免伪造条目混入列表
    private fun learnConverterClasses() {
        val tbBaseMsgClass = findClass("com.baidu.tieba.im.lib.socket.msg.TbBaseMsg")
        val chatMsgSdkClass = findClass(LOTTERY_CHAT_MSG_SDK)
        val frames = Thread.currentThread().stackTrace
            .map { it.className }
            .filter { it.startsWith("com.baidu.tieba") && !it.startsWith("com.baidu.tieba.im.lib.socket") }
        for (clsName in frames.distinct()) {
            val clazz = runCatching { findClass(clsName) }.getOrNull() ?: continue
            val sendM = clazz.declaredMethods.firstOrNull {
                !it.isSynthetic && it.parameterTypes.size == 2 &&
                    tbBaseMsgClass.isAssignableFrom(it.parameterTypes[1]) &&
                    chatMsgSdkClass.isAssignableFrom(it.returnType)
            } ?: continue
            val recvMs = clazz.declaredMethods.filter {
                !it.isSynthetic && it.parameterTypes.size == 2 &&
                    chatMsgSdkClass.isAssignableFrom(it.parameterTypes[1]) &&
                    tbBaseMsgClass.isAssignableFrom(it.returnType)
            }
            if (recvMs.isEmpty()) continue
            mConvSendMethod = sendM
            mConvHooked = true
            lotteryTsLog(
                "一键抽奖 自适应转换链：识别到 vea=$clsName send=${sendM.name} " +
                    "recv=${recvMs.joinToString(".") { it.name }}，等待消息流量捕获实例"
            )
            var hookedAny = false
            runCatching {
                XposedBridge.hookMethod(sendM, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        learnChainFrom(param)
                    }
                }).also { hookedAny = true }
            }
            for (m in recvMs) {
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: MethodHookParam): Any? {
                            learnChainFrom(param)
                            val sdkMsg = param.args.getOrNull(1)
                            val result = try {
                                XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                            } catch (tr: Throwable) {
                                XposedBridge.log(
                                    "一键抽奖 毒消息已屏蔽: ${tr.javaClass.simpleName}:${tr.message?.take(60)} " +
                                        "msg=${runCatching { LotteryPoisonGuard.describeSdkMsg(sdkMsg) }.getOrNull()}"
                                )
                                return null
                            }
                            // 转换成功但属于刷毒账号或伪造了未来时间戳的毒消息（多播入口
                            // 拦不到的进房同步/历史加载路径）同样丢弃——占位会带着毒消息的
                            // 高位 msgId 继续钉在列表"最新"位置，丢弃才是真屏蔽
                            if (sdkMsg != null && (runCatching {
                                    lotteryIsBlockedSpamNick(XposedHelpers.callMethod(sdkMsg, "getNickName") as? String)
                                }.getOrDefault(false) ||
                                    runCatching { LotteryPoisonGuard.isFutureDated(sdkMsg) }.getOrDefault(false))
                            ) {
                                // 历史加载/进房同步单批可达 60 条且周期性重拉,
                                // 逐条 tsLog 会刷盘挤占 Logger 异步队列, 走共用聚合窗口
                                LotteryPoisonGuard.countPoisonBlockedAndFlushIfDue(1)
                                return null
                            }
                            return result
                        }
                    }).also { hookedAny = true }
                }
            }
            // 全部挂钩失败时回退 mConvHooked，让下一次 TbTextMsg 构造还能重试学习链
            if (!hookedAny) mConvHooked = false
            return
        }
    }

    // 从收/发转换流量捕获 vea 实例与 wea 标记（同一消息对收发共用同一 vea 实例）
    private fun learnChainFrom(param: XC_MethodHook.MethodHookParam) {
        if (mChainLearned) return
        val conv = param.thisObject ?: return
        val marker = param.args.getOrNull(0) ?: return
        mConvVe = conv
        mConvMarker = marker
        mChainLearned = true
        lotteryTsLog(
            "一键抽奖 自适应转换链学习成功: vea=${conv.javaClass.name} wea=${marker.javaClass.name}"
        )
    }

    // 供 doSend 使用的自适应转换链调用：走学习产物（捕获的转换器实例+方法）生成 ChatMsg。
    // 返回 null 表示学习链不可用/调用失败，调用方回退具名/硬编码链。
    // 发送必须用群聊 marker（okb.a）：收消息路径用的是私信 pkb.a，自学习若从
    // 收流量捕获会拿到错误 marker（fkb.b 对 strategy 有非空校验，错误 marker 不抛但语义不对）
    internal fun convertViaLearnedChain(
        tbMsg: Any,
        tbBaseMsgClass: Class<*>,
        chatMsgSdkClass: Class<*>,
        groupMarker: Any?
    ): Any? {
        if (!mChainLearned) return null
        val conv = mConvVe
        if (conv == null || mConvMarker == null) {
            lotteryTsLog("一键抽奖 自适应转换链实例缺失(conv=${conv != null})，走旧版回退链")
            return null
        }
        // 学习时缓存的方法（可能声明在基类，invoke 按实例虚分派）优先，再补一个形态重找的候选
        val candidates = ArrayList<java.lang.reflect.Method>()
        mConvSendMethod?.let { candidates.add(it) }
        val shapeMatch = conv.javaClass.methods.firstOrNull { m ->
            !m.isSynthetic && m.parameterTypes.size == 2 &&
                tbBaseMsgClass.isAssignableFrom(m.parameterTypes[1]) &&
                chatMsgSdkClass.isAssignableFrom(m.returnType)
        }
        if (shapeMatch != null && candidates.none { it === shapeMatch }) candidates.add(shapeMatch)
        var chatMsg: Any? = null
        for (bMethod in candidates) {
            val marker = if (groupMarker != null &&
                bMethod.parameterTypes[0].isInstance(groupMarker)
            ) groupMarker else mConvMarker
            try {
                bMethod.isAccessible = true
                chatMsg = bMethod.invoke(conv, marker, tbMsg)
            } catch (tr: Throwable) {
                // InvocationTargetException 只是被反射包裹，真正的失败原因在 cause 里
                val cause = (tr as? java.lang.reflect.InvocationTargetException)?.cause ?: tr
                lotteryTsLog(
                    "一键抽奖 自适应转换链 ${conv.javaClass.name}.${bMethod.name} 异常: " +
                        "${cause.javaClass.name}: ${cause.message}"
                )
                lotteryTsLog(android.util.Log.getStackTraceString(cause))
            }
            if (chatMsg != null) break
        }
        if (chatMsg == null) {
            lotteryTsLog("一键抽奖 自适应转换链调用失败，走旧版回退链")
        }
        return chatMsg
    }
}
