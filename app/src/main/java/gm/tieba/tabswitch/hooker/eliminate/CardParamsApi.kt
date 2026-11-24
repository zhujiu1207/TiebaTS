package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.logBoth
import java.lang.reflect.Method

/**
 * 模板卡参数载体的共享解析: f()=信息Map, c()=参数Map持有者, 持有者.a()=Map。
 * 类名随宿主版本混淆轮换，按"无参方法存在 + 返回类型符合语义"双重校验探测候选；
 * holder 优先由 c() 的返回类型直接推导（与混淆名完全解耦），候选名仅兜底。
 * 不能只按"存在同名方法"绑定：22.12.1.0 中 zh9.a() 返回 String，误绑会让
 * businessInfo 槽位永久读空（heatTopic 判定随之退化）。
 * 22.13.1.1 轮换: us9→lt9(卡片数据基类, c() 返回 qi9), zh9→qi9, mr9→ds9;
 * 22.14.1.0 轮换: lt9→wt9(c() 返回 zi9, 方法布局完全同构), 持有者兜底
 * qi9→zi9。22.13 的 mr9/rg9/zh9 与 22.14 的 lt9/mr9/qi9/rg9 均是同名异义类,
 * us9/zh9 在 22.14 已移除, 靠返回类型校验自动排除。
 * FollowFilter 与 HotPostFilter 共用, 解析结果进程级缓存。
 */
object CardParamsApi : XposedContext() {

    private val PARAM_CANDIDATES = arrayOf(
        "com.baidu.tieba.wt9", "com.baidu.tieba.lt9", "com.baidu.tieba.us9",
        "com.baidu.tieba.mr9"
    )
    private val HOLDER_CANDIDATES = arrayOf(
        "com.baidu.tieba.qi9", "com.baidu.tieba.zh9", "com.baidu.tieba.rg9",
        // zi9 只能垫底: 22.11 宿主的同名 zi9 是卡片处理器注册表(静态 HashMap<String,nl9>,
        // a() 返回该 Map), 排在旧名前面时, 22.11 上载体解析一旦失败走兜底会误绑它
        "com.baidu.tieba.zi9"
    )

    @Volatile
    private var resolved = false

    @Volatile
    private var paramClass: Class<*>? = null

    @Volatile
    private var fMethod: Method? = null

    @Volatile
    private var cMethod: Method? = null

    @Volatile
    private var holderAMethod: Method? = null

    // @Synchronized: 主线程与后台解码线程可能并发首调(与 Fs9Api.resolve 同口径),
    // 候选循环幂等, 加锁只为"每进程只跑一次"的注释承诺成立
    @Synchronized
    fun resolve(): Boolean {
        if (resolved) return paramClass != null
        for (name in PARAM_CANDIDATES) {
            val clazz = runCatching { findClass(name) }.getOrNull() ?: continue
            val f = runCatching {
                clazz.getMethod("f").apply { isAccessible = true }
            }.getOrNull() ?: continue
            // f() 必须返回 Map: 同名异义类误绑会让信息槽位恒空
            if (!Map::class.java.isAssignableFrom(f.returnType)) continue
            val c = runCatching {
                clazz.getMethod("c").apply { isAccessible = true }
            }.getOrNull() ?: continue
            paramClass = clazz
            fMethod = f
            cMethod = c
            break
        }
        holderAMethod = resolveHolderMethod()
        // 置位必须在结果发布之后: volatile 只保证之前的写可见, 先置位的话
        // 并发首调线程可能读到 resolved=true 而各结果字段还是 null。
        // resolved 守卫保证本函数体每进程只跑一次, 此处失败日志天然一次性;
        // holder 不可用时 cHolderMap 恒 null(businessInfo 槽位退化), 留痕便于反馈定位
        resolved = true
        if (paramClass != null && holderAMethod == null) {
            logBoth("CardParamsApi: holder 未解析成功, businessInfo 槽位不可用")
        }
        return paramClass != null
    }

    // holder 的 a() 必须返回 Map。参数载体的 c() 返回类型即持有者类
    // (22.11/22.12 实测 mr9.c()→rg9 / us9.c()→zh9, 22.13 实测 lt9.c()→qi9, rg9/qi9.a()→Map)，
    // 不依赖候选名；载体未解析成功时才按候选名兜底，同样校验返回类型
    private fun resolveHolderMethod(): Method? {
        val fromCarrier = cMethod?.let { c ->
            runCatching {
                val md = c.returnType.getMethod("a").apply { isAccessible = true }
                if (Map::class.java.isAssignableFrom(md.returnType)) md else null
            }.getOrNull()
        }
        if (fromCarrier != null) return fromCarrier
        for (name in HOLDER_CANDIDATES) {
            val a = runCatching {
                findClass(name).getMethod("a").apply { isAccessible = true }
            }.getOrNull() ?: continue
            if (Map::class.java.isAssignableFrom(a.returnType)) return a
        }
        return null
    }

    fun isParamItem(item: Any?): Boolean {
        val clazz = paramClass ?: return false
        return item != null && clazz.isInstance(item)
    }

    // ===== f()/c().a() 双槽位的 per-pass memo(机制同 Fs9Api.PassMemo, 见其注释) =====
    // FeedListPipeline 单 hook 后, 同一批卡仍被 HotPostFilter(cardTypes/cardSchema/
    // 双槽收集, 一次判定最多 4 次 invoke)与 FollowFilter(cardForumName)等多个阶段
    // 重复取槽位; 两个 Map 对同一卡在一代管线内恒定(各阶段均只读), 以代为单位缓存。
    // 代数源取 Fs9Api 的管线代数: 两个 Api 的 memo 随同一轮 applyStages 一起失效;
    // 条目对象随页释放, memo 至多钉住单线程上一代的卡, 有界性论证与 Fs9Api 相同
    private class ParamMemo {
        var generation = -1L
        val fMaps = java.util.IdentityHashMap<Any, Map<*, *>?>()
        val holderMaps = java.util.IdentityHashMap<Any, Map<*, *>?>()

        fun maybeReset(current: Long) {
            if (generation != current) {
                generation = current
                fMaps.clear()
                holderMaps.clear()
            }
        }
    }

    private val sParamMemo = object : ThreadLocal<ParamMemo>() {
        override fun initialValue(): ParamMemo = ParamMemo()
    }

    private fun paramMemo(): ParamMemo =
        sParamMemo.get()!!.also { it.maybeReset(Fs9Api.currentPassGeneration()) }

    fun fMap(item: Any): Map<*, *>? {
        val method = fMethod ?: return null
        val memo = paramMemo()
        if (memo.fMaps.containsKey(item)) return memo.fMaps[item]
        val map = runCatching { method.invoke(item) as? Map<*, *> }.getOrNull()
        memo.fMaps[item] = map
        return map
    }

    // c().a()(businessInfo, 贴吧 xp9.d 即读此处), 与 f() 是两个独立参数槽位
    fun cHolderMap(item: Any): Map<*, *>? {
        val c = cMethod ?: return null
        val a = holderAMethod ?: return null
        val memo = paramMemo()
        if (memo.holderMaps.containsKey(item)) return memo.holderMaps[item]
        val map = runCatching {
            val holder = c.invoke(item) ?: return@runCatching null
            a.invoke(holder) as? Map<*, *>
        }.getOrNull()
        memo.holderMaps[item] = map
        return map
    }
}
