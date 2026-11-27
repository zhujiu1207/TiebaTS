package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.BuildConfig
import gm.tieba.tabswitch.util.Logger
import gm.tieba.tabswitch.util.findFirstMethodByExactReturnType
import java.lang.reflect.Method

// 信息流广告卡片 + 模板键诊断日志。从 PurgeAd 原文搬移（逐字保留注释），
// 模板键判定与广告键集合（AD_KEYS）由主文件 PurgeAd 提供。

// 信息流广告卡片拆分模块：FeedListPipeline 阶段注册 + 模板键过滤
internal object PurgeAdFeedCards {

    // 诊断日志拆分模块（与过滤同源搬移，避免循环依赖）
    // 排查"新广告卡未被屏蔽"用: 日志开关开启(或 debug 构建)时, 在信息流列表入口
    // 记录首次出现的模板键(键 + 类名 + 载荷摘要), 新广告卡进入视野即留痕, 不必再
    // 反编译定位。每键每次进程仅记一条, 日志开关关闭时零开销短路(不查集合不拼串)。
    //
    // 载荷摘要必须放到后台线程算: item.toString() 是任意卡片对象的实现, 可能遍历
    // 大字段/拼长串, 而本方法跑在信息流列表构造路径(主线程)上, 每张新卡一次。
    // 实测日志里 [模板键诊断] 有 326 行、多数来自主线程 —— 那本身就是卡顿源。
    // 这里只保留"判重 + 抓类名"两件廉价操作, 摘要交给单线程池异步生成。
    private val seenTemplateKeys: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    // 单线程 + 有界队列: 诊断日志是尽力而为, 积压时直接丢弃, 绝不让它反压主线程
    private val templateKeyLogger by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(64),
            { r -> Thread(r, "TiebaTS-TemplateKeyLog").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
        )
    }

    private fun logTemplateKeyIfFirstSeen(key: String, item: Any) {
        if (!seenTemplateKeys.add(key)) return
        // 类名在调用线程取(廉价且对象状态此刻有效), toString 交给后台
        val className = runCatching { item.javaClass.name }.getOrDefault("?")
        try {
            templateKeyLogger.execute {
                try {
                    val payload = runCatching { item.toString() }.getOrDefault("")
                        .replace("\n", " ").replace("\r", " ").take(200)
                    Logger.log("[模板键诊断] key=$key class=$className payload=$payload")
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
            // 队列满/线程池已关闭: 丢弃这条诊断, 不影响屏蔽逻辑
        }
    }

    // 旧版本兜底的方法缓存: CHM + 哨兵(null 负缓存, PurgeAdPostPage.typeMethodCache
    // 同范式)——此前 synchronized(HashMap) 在旧宿主上逐卡拿锁, 一旦走到该路径
    // 就是首页滚动逐元素热路径
    private val fallbackKeyMethods = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    private val NO_METHOD = Any()

    private fun getTemplateKey(item: Any): String? {
        // 22.11+: 模板键接口 b(); 接口名随版本轮换(22.12 起为 nt9, 低版本为 fs9),
        // 探测逻辑共享 Fs9Api(同候选顺序, 含 b() 返回 String 校验)。接口解析成功
        // 时即使单卡取键失败也返回 null: 对象非该接口实现, 兜底扫 String 方法反而误判
        if (Fs9Api.resolve()) {
            return Fs9Api.cardKey(item)
        }
        // 旧版本兜底: 类中声明的首个 String 返回方法(同 Purge 的 sideway_card 判定)。
        // 按类缓存(含"确无此方法"的哨兵负缓存): 候选接口均未解析时此路径落在
        // 热路径上, 逐元素全方法表扫描会造成首页滚动反射放大
        fallbackKeyMethods[item.javaClass]?.let { cached ->
            return if (cached === NO_METHOD) null else (cached as Method).let {
                runCatching { it.invoke(item) as? String }.getOrNull()
            }
        }
        val md = runCatching { findFirstMethodByExactReturnType(item.javaClass, String::class.java) }.getOrNull()
        if (md != null) {
            fallbackKeyMethods[item.javaClass] = md
            return runCatching { md.invoke(item) as? String }.getOrNull()
        }
        fallbackKeyMethods[item.javaClass] = NO_METHOD
        return null
    }

    // 22.11+: 推荐流为模板卡片体系(fs9), 卡片类型由模板键 fs9.b() 标识,
    // 入口参照 PurgeVideo 的定位: TemplateAdapter.setList 与 FeedTemplateAdapter 的 L/P/R。
    // 列表入口 hook 统一走 FeedListPipeline(单 hook 多阶段链), 此处只注册阶段
    internal fun blockFeedAdCards(isAdKey: (String) -> Boolean) {
        FeedListPipeline.register { list, _, _ -> filterAdFeedList(list, isAdKey) }
    }

    private fun filterAdFeedList(list: List<*>?, isAdKey: (String) -> Boolean): List<*>? {
        if (list == null || list.isEmpty()) return list
        // 诊断门控按批判定(每页一次), 不在逐元素路径上读偏好
        val diagnose = BuildConfig.DEBUG || Logger.isEnabled()
        return Fs9Api.filterCopy(list) { item ->
            val key = if (item != null) getTemplateKey(item) else null
            if (diagnose && item != null && key != null) {
                logTemplateKeyIfFirstSeen(key, item)
            }
            key != null && isAdKey(key)
        }
    }
}
