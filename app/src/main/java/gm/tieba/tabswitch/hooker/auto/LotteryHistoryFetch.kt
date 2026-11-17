package gm.tieba.tabswitch.hooker.auto

import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.XposedContext.Companion.sClassLoader
import gm.tieba.tabswitch.bridge.XposedHelpers

// 历史消息拉取子系统：IMSDK 包装器结构定位、(实例,方法)缓存、回调形态探测。
// 从 OneKeyLottery 原文搬移（逐字保留注释）。
internal object LotteryHistoryFetch {

    // 拉历史命中缓存的 (包装器实例, 方法)，避免每次都重新枚举候选
    @Volatile
    private var fetchCached: Pair<Any, java.lang.reflect.Method>? = null

    // 拉取回调形态探测日志只记一次
    private val mFetchProbeLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    // 拉取房间最近消息（进房后才可拉取）。
    // 结构定位：包装器（12.74=cn6.a / 12.63=f96.a 单例）上"8 参数、第 1 参为 long、末参为回调接口"的方法；
    // 命中的 (实例, 方法) 缓存复用。回调不固定参数位，扫描全部参数取第一个非空集合作为消息数据。
    // count 可自定义（毒预览修复用大窗口回溯真实消息），默认 FETCH_MESSAGE_COUNT。
    //
    // 缓存口径：回调"确认命中"（接口被调到且参数含集合，空集合也算）即视为机制可用并缓存
    // (实例, 方法)。空集合是宿主正常路径——22.12 的 l57.i / 22.13 的 w57.i（v57.a() 返回，
    // 回调接口轮换为单方法的 j67）都同步带回一个可能为空的静态 TreeSet（响应回调一次拉取
    // 一次完整快照），不是签名漂移；若空响应也清缓存重探，安静房间每次拉取都要重付全候选
    // 枚举 × 3s 超时（多次调用点叠加后单间可达 30s+，把整轮推向 WakeLock 上限）。只认非空
    // 才缓存的另一面才是曾记录的"误判恒返回空"陷阱的反例：这里缓存的只是"哪个方法在响应"，
    // 数据晚到仍会在后续拉取中返回。
    internal fun fetchRecentMessages(
        context: android.content.Context,
        roomId: Long,
        count: Int = LOTTERY_FETCH_MESSAGE_COUNT
    ): Collection<*>? {
        fetchCached?.let { (instance, method) ->
            val (result, confirmed) = tryFetch(instance, method, context, roomId, count, probe = false)
            if (result != null) return result
            if (confirmed) return null
            fetchCached = null
        }
        // 探测顺序上优先等待能带回数据的候选；确认命中但空的候选先记下，
        // 全部落空时兜底缓存首个确认项，避免下一轮拉取重新枚举
        var emptyConfirmed: Pair<Any, java.lang.reflect.Method>? = null
        for (wrapperName in LOTTERY_FETCH_WRAPPERS) {
            val instance = runCatching {
                XposedHelpers.callStaticMethod(findClass(wrapperName), "a")
            }.getOrNull() ?: continue
            val methods = instance.javaClass.methods.filter {
                it.parameterTypes.size == 8 && it.parameterTypes[1] == Long::class.javaPrimitiveType &&
                    it.parameterTypes[7].isInterface
            }
            for (h in methods) {
                val (result, confirmed) = tryFetch(instance, h, context, roomId, count, probe = true)
                if (result != null) {
                    fetchCached = instance to h
                    return result
                }
                if (confirmed && emptyConfirmed == null) emptyConfirmed = instance to h
            }
        }
        if (emptyConfirmed != null) fetchCached = emptyConfirmed
        return null
    }

    // 返回 (消息集合或 null, 回调是否确认命中)。空集合时集合结果为 null 但 confirmed=true，
    // 调用方据 confirmed 决定是否保留缓存；集合结果与回调放行的可见性经 latch 建立
    private fun tryFetch(
        instance: Any, h: java.lang.reflect.Method,
        context: android.content.Context, roomId: Long, count: Int, probe: Boolean
    ): Pair<Collection<*>?, Boolean> {
        val latch = java.util.concurrent.CountDownLatch(1)
        val holder = arrayOfNulls<Any>(1)
        val confirmed = arrayOf(false)
        val callbackClass = h.parameterTypes[7]
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            sClassLoader, arrayOf(callbackClass)
        ) { _, method, args ->
            if (holder[0] == null && args != null) {
                val collection = args.firstOrNull { it is Collection<*> } as? Collection<*>
                if (collection != null) {
                    // 空集合也放行 latch：响应回调单次调用即本次拉取的完整结果，
                    // 继续干等只会白付超时（数据晚到走的是后续拉取，不是同一回调再触发）
                    if (collection.isNotEmpty()) holder[0] = collection
                    confirmed[0] = true
                    latch.countDown()
                }
                if (holder[0] == null && probe && !mFetchProbeLogged.getAndSet(true)) {
                    val shapes = args.joinToString(",") { a -> a?.javaClass?.name ?: "null" }
                    lotteryTsLog("一键抽奖($roomId) 拉取回调未含集合: ${method.name}($shapes)")
                }
            }
            // 基本类型返回值返回 null 会在宿主调用点拆箱 NPE,
            // 按方法返回类型派发安全零值(void/引用类型仍为 null)
            gm.tieba.tabswitch.XposedContext.safeConstantFor(method.returnType, 0, false)
        }
        val invoked = runCatching {
            h.isAccessible = true
            h.invoke(instance, context, roomId, 0L, count, true, true, null, listener)
        }.isSuccess
        if (!invoked) return null to false
        val reached = latch.await(LOTTERY_FETCH_LATCH_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
        // 超时即放弃: 迟到回调对 holder 的写入与本线程的读取无 happens-before,
        // 可能读到半初始化引用; countDown 成功的路径经 latch 建立可见性
        val result = if (reached) holder[0] as? Collection<*> else null
        return result to (reached && confirmed[0])
    }

    // 从最近消息里找"群助手"（含自定义名称机器人）的发言，取其 uid 与显示名（触发参数需与真实机器人一致）
    internal fun fetchGroupAssistantInfo(
        context: android.content.Context, roomId: Long
    ): Pair<Long, String>? {
        val messages = fetchRecentMessages(context, roomId) ?: return null
        for (m in messages) {
            m ?: continue
            val nick = runCatching { XposedHelpers.callMethod(m, "getNickName") as? String }.getOrNull()
            if (nick?.contains(LOTTERY_GROUP_ASSISTANT_NAME) != true) continue
            val uid = runCatching { XposedHelpers.callMethod(m, "getFromUser") as? Long }.getOrNull()
            if (uid != null) return uid to nick
        }
        return null
    }
}
