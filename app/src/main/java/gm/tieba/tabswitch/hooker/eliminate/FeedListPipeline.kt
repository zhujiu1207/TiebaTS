package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.util.Logger

// 首页模板卡列表过滤阶段: list 为灌入适配器的列表, owner 为被 hook 方法的
// thisObject(FollowFilter 用它判别 HomeTemplateAdapter), entry 为入口名
// (L/P/R/setList/g0/j0, ScorePostFilter 的整列表置空只对 setList 内层分段合法)。
// 返回 null 表示未修改, 保留上一阶段结果
private typealias FeedStage = (list: List<*>?, owner: Any?, entry: String) -> List<*>?

/**
 * 首页模板卡列表过滤管线。
 *
 * FeedTemplateAdapter L/P/R、TemplateAdapter.setList、HomeTemplateAdapter g0/j0
 * 这批列表入口此前被各 PostFilter 逐一独立 hook(11 处注册): 同一页推荐数据要经历
 * 11 次 Xposed 桥派发与 11 轮逐卡反射扫描, 且各过滤器各自维护的反射缓存互不共享。
 * 管线在每个入口只挂一次 hook, 按注册顺序链式执行各过滤器注册的阶段函数;
 * 各阶段语义与原先独立过滤一致(复制式过滤、无命中返回 null), 判定逻辑仍留在
 * 各过滤器内, 管线只做汇聚。
 *
 * 首个 register 调用时安装 hook: 各过滤器的 hook() 仅在自身功能开关开启时执行,
 * 无任何净化功能开启时管线不注册任何 hook, 零开销。
 */
object FeedListPipeline : XposedContext() {

    // 注册发生在启动初始化(主线程), 阶段执行发生在主线程+后台解码线程:
    // 注册表读取须同步。执行走 stagesSnapshot(volatile 不可变快照):
    // applyStages 是每次 feed 灌入的热路径, 不加锁不分配
    private val lock = Any()
    private val stages = ArrayList<FeedStage>(8)

    @Volatile
    private var stagesSnapshot: List<FeedStage> = emptyList()

    @Volatile
    private var installed = false

    fun register(stage: FeedStage) {
        synchronized(lock) {
            stages.add(stage)
            stagesSnapshot = stages.toList()
            if (!installed) {
                installed = true
                installHooks()
            }
        }
    }

    private fun applyStages(list: List<*>?, owner: Any?, entry: String): List<*>? {
        if (list == null || list.isEmpty()) return list
        // 递增 Fs9Api 的 memo 代数: 同一批条目要被多个阶段逐个 cardKey/cardPayload,
        // 一轮管线内按条目对象记忆化, 轮与轮之间作废(条目内部状态可能随页刷新)
        Fs9Api.beginFilterPass()
        var current = list
        for (stage in stagesSnapshot) {
            // 管线级逐阶段隔离: 此前各过滤器是独立 hook, 单个回调抛异常只损失自身,
            // 其余过滤器的 hook 照常派发; 收敛为单 hook 后必须在此兜底等价语义,
            // 否则一个阶段异常会吞掉其后全部过滤阶段。
            // 异常走 logHookError(60s 限流): 阶段函数内部已有逐点 runCatching,
            // 逃逸到这里的应是意外异常, 不能在每次 feed 灌入的热路径上刷日志
            try {
                stage(current, owner, entry)?.let { current = it }
            } catch (tr: Throwable) {
                logHookError("FeedListPipeline:$entry", tr)
            }
        }
        return current
    }

    private fun installHooks() {
        val listType = java.util.List::class.java
        // 注册点逐项隔离: 单个入口类缺失(版本漂移)只损失该入口,
        // 与原先各过滤器独立注册的隔离粒度一致
        val entries = listOf(
            Triple("com.baidu.tieba.feed.list.FeedTemplateAdapter", "L", arrayOf<Any?>(listType)),
            Triple("com.baidu.tieba.feed.list.FeedTemplateAdapter", "P", arrayOf<Any?>(listType)),
            Triple(
                "com.baidu.tieba.feed.list.FeedTemplateAdapter", "R",
                arrayOf<Any?>(listType, Boolean::class.javaPrimitiveType)
            ),
            Triple("com.baidu.tieba.feed.list.TemplateAdapter", "setList", arrayOf<Any?>(listType)),
        )
        for ((clazz, method, params) in entries) {
            try {
                hookBeforeMethod(findClass(clazz), method, *params) { param ->
                    param.args[0] = applyStages(param.args[0] as? List<*>, param.thisObject, method)
                }
            } catch (tr: Throwable) {
                // 核心入口注册失败=全部净化失效, 属意外情况(类在 22.x 稳定存在),
                // 双写日志便于排查; g0/j0 的预期缺失走下方静默分支
                logBoth("FeedListPipeline: $clazz#$method 注册失败 -- $tr")
            }
        }
        // 22.11+ 翻页(g0)/缓存恢复(j0)兜底入口: 统一收编后, 此前未挂这两个入口的
        // 过滤器(PurgeVideo/LivePostFilter/RecommendForumFilter/PurgeAd)漏过滤通道
        // 一并闭合; 旧版本宿主无此类, 缺失时静默跳过(与原各过滤器 catch-ignored 语义一致,
        // 不双写日志刷屏)
        try {
            val homeClass = findClass("com.baidu.tieba.homepage.personalize.HomeTemplateAdapter")
            hookBeforeMethod(homeClass, "g0", listType, Boolean::class.javaPrimitiveType) { param ->
                param.args[0] = applyStages(param.args[0] as? List<*>, param.thisObject, "g0")
            }
            hookBeforeMethod(homeClass, "j0", listType, Boolean::class.javaPrimitiveType) { param ->
                param.args[0] = applyStages(param.args[0] as? List<*>, param.thisObject, "j0")
            }
        } catch (tr: Throwable) {
            Logger.log("FeedListPipeline: HomeTemplateAdapter g0/j0 未挂载(旧版本正常) -- $tr")
        }
    }
}
