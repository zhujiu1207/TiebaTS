package gm.tieba.tabswitch.util

// hook 回调异常落盘限流的单一事实源：此前 XposedContext.logHookError(Kotlin) 与
// XposedBridge.CallbackInterceptor.logHookError(Java) 各持一份逐行同构的
// "60s 窗口 + 有界 map + 最旧淘汰"实现，修正（如窗口推进顺序）只能逐份同步。
// 两处调用方各自保留原日志文案与落盘通道（XposedBridge 走框架日志、
// XposedContext 走模块日志），本对象只裁决"这次是否放行"。
//
// key 不含 message：动态 message（携带解析值/索引/时间戳）会让每次调用都成为
// 新 key，60s 限流被完全击穿成刷屏。单槽"仅比对上一条"去重会被交替异常击穿，
// 这里用有界 map；超限按"最旧落盘时间"逐条淘汰而不是整表 clear——
// 整表清空会把其他异常的限流窗口连带重置，让它们也重新放行一轮洪峰
object HookErrorRateLimiter {

    private const val INTERVAL_MS = 60_000L
    private const val MAX_KEYS = 64

    private val sLastLogTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** key(阶段+异常类) 60s 内只放行一次; 返回 true 表示本次应落盘。 */
    @JvmStatic
    fun shouldLog(phase: String, tr: Throwable): Boolean {
        val key = phase + ':' + tr.javaClass.name
        val now = System.currentTimeMillis()
        val last = sLastLogTimes[key]
        if (last != null && now - last < INTERVAL_MS) return false
        if (sLastLogTimes.size > MAX_KEYS) {
            // 并发下的重复淘汰/漏淘汰均无害, 最多多放行一轮, 下一轮触发再补
            var oldestKey: String? = null
            var oldestAt = Long.MAX_VALUE
            for ((k, t) in sLastLogTimes) {
                if (t < oldestAt) {
                    oldestAt = t
                    oldestKey = k
                }
            }
            if (oldestKey != null) sLastLogTimes.remove(oldestKey)
        }
        sLastLogTimes[key] = now
        return true
    }
}
