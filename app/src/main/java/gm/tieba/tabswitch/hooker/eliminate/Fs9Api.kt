package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 首页模板卡数据项接口的共享解析, b()=卡片键, d()=载荷。
 * 接口名随宿主版本轮换, 按"同时存在零参 b()/d()"逐候选探测:
 * 22.12 为 nt9, 22.13.1.1 为 eu9, 22.14.1.0 为 pu9, 22.15.1.1 起为
 * ov9(方法布局同构, b()/d() 不变, 全量约 7900 实现类同代);
 * 其余候选是同名异义类/旧名(如 22.12 的 eu9 是三参
 * a(View,nt9,int) 点击绑定器、fs9 是 void a(Map) 接口、22.14 的 eu9
 * 是 setOnTemplateClickListener 接口、22.15 的 pu9 是 ViewOutlineProvider
 * 实现类、22.15 的 nt9 是卡频控类), 因无零参 b() 或类缺失自动
 * 跳过不会误绑。均失败时置 resolved 停止重试
 * (避免 7 个 PostFilter 在热路径反复构造异常)。
 * b() 必须返回 String: 防止同名异义类(方法重载/语义不同)被绑为卡片键
 */
object Fs9Api : XposedContext() {

    private val INTERFACE_CANDIDATES = arrayOf(
        "com.baidu.tieba.ov9", "com.baidu.tieba.pu9", "com.baidu.tieba.eu9",
        "com.baidu.tieba.nt9", "com.baidu.tieba.fs9"
    )

    @Volatile
    private var resolved = false

    @Volatile
    private var keyMethod: Method? = null

    @Volatile
    private var payloadMethod: Method? = null

    // 跨过滤器共享的反射缓存, 此前 HotPostFilter/GoodsPostFilter/LivePostFilter/
    // BountyPostFilter/GamePromoPostFilter/ScorePostFilter/FollowFilter/
    // RecommendForumFilter 各持一份相同实现的 dataListFields/mapAccessors,
    // 同一载荷类被各过滤器首遇时重复扫描继承链。钩点可能来自后台解码线程,
    // 与主线程并发, 必须用并发容器
    private val dataListFields = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>(16)

    private val mapAccessors =
        java.util.concurrent.ConcurrentHashMap<Class<*>, List<(Any) -> Map<*, *>?>>(16)

    fun resolveListFields(clazz: Class<*>): List<Field> {
        dataListFields[clazz]?.let { return it }
        val out = ArrayList<Field>(4)
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (!java.util.List::class.java.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                out.add(field)
            }
            current = current.superclass
        }
        dataListFields[clazz] = out
        return out
    }

    // 对象上无参 Map 返回值方法取值器(含一级对象字段的方法), 按类缓存;
    // gs9/bg 族卡参数不经字段直接暴露, 只能从这些方法拿
    fun resolveMapAccessors(clazz: Class<*>): List<(Any) -> Map<*, *>?> {
        mapAccessors[clazz]?.let { return it }
        val out = ArrayList<(Any) -> Map<*, *>?>(4)
        for (method in clazz.methods) {
            if (method.parameterCount != 0) continue
            if (!Map::class.java.isAssignableFrom(method.returnType)) continue
            method.isAccessible = true
            out.add { obj -> runCatching { method.invoke(obj) as? Map<*, *> }.getOrNull() }
        }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (field.type.isPrimitive || field.type == String::class.java) continue
                try {
                    field.isAccessible = true
                } catch (ignored: Throwable) {
                    continue
                }
                for (method in field.type.methods) {
                    if (method.parameterCount != 0) continue
                    if (!Map::class.java.isAssignableFrom(method.returnType)) continue
                    method.isAccessible = true
                    out.add { obj ->
                        runCatching {
                            val inner = field.get(obj) ?: return@runCatching null
                            method.invoke(inner) as? Map<*, *>
                        }.getOrNull()
                    }
                }
            }
            current = current.superclass
        }
        mapAccessors[clazz] = out
        return out
    }

    // @Synchronized: 主线程与后台解码线程可能并发首调, resolved 检查无锁时两侧
    // 都会跑一遍候选循环(幂等无害但注释宣称"每进程只跑一次"须成立);
    // resolved 置位在结果发布之后, 锁内执行不改变 volatile 发布序
    @Synchronized
    fun resolve(): Boolean {
        if (resolved) return keyMethod != null
        return try {
            for (name in INTERFACE_CANDIDATES) {
                val clazz = runCatching { findClass(name) }.getOrNull() ?: continue
                val key = runCatching {
                    clazz.getMethod("b").apply { isAccessible = true }
                }.getOrNull() ?: continue
                // b() 必须返回 String: 防止同名异义类(方法重载/语义不同)被绑为卡片键
                if (key.returnType != String::class.java) continue
                val payload = runCatching {
                    clazz.getMethod("d").apply { isAccessible = true }
                }.getOrNull() ?: continue
                keyMethod = key
                payloadMethod = payload
                break
            }
            resolved = true
            keyMethod != null
        } catch (ignored: Throwable) {
            // 失败也置 resolved: 接口不存在的旧版本上, 不置位会让 7 个 PostFilter
            // 每次 filterTemplateList 都重跳 findClass 并构造异常,热路径放大
            resolved = true
            false
        }
    }

    fun cardKey(item: Any?): String? {
        if (item == null) return null
        val method = keyMethod ?: return null
        val memo = passMemo().also { it.maybeReset(sPassGeneration.get()) }
        if (memo.keys.containsKey(item)) return memo.keys[item]
        val key = runCatching { method.invoke(item) as? String }.getOrNull()
        memo.keys[item] = key
        return key
    }

    fun cardPayload(item: Any?): Any? {
        if (item == null) return null
        val method = payloadMethod ?: return null
        val memo = passMemo().also { it.maybeReset(sPassGeneration.get()) }
        if (memo.payloads.containsKey(item)) return memo.payloads[item]
        val payload = runCatching { method.invoke(item) }.getOrNull()
        memo.payloads[item] = payload
        return payload
    }

    // ===== 跨过滤阶段的 per-item memo =====
    // FeedListPipeline 收敛为单 hook 后, 同一批条目仍被 9-10 个过滤阶段逐个
    // cardKey/cardPayload 反射 invoke(每页数百次重复调用, 而两个结果对同一
    // 条目对象恒定——各阶段均为只读判定, 不改写卡片内部状态)。以"代"为单位
    // 缓存: 管线每轮 applyStages 前递增代数, 各线程 memo 检测到代数变化即整体
    // 作废。按调用线程分桶免锁(管线执行在主线程+后台解码线程); 条目对象随页
    // 释放, memo 至多钉住单线程上一页的条目, 该线程下次过管线时清空, 有界
    // 缓存安全性完全依赖"各阶段对同一 card 只读"这一约定, 代数自增必须原子:
    // 主线程与后台解码线程并发过管线时, @Volatile 上的 ++ 是读-改-写竞态,
    // 会丢失递增使某线程 memo 跨代未作废(残留脏缓存)
    private val sPassGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    private class PassMemo {
        var generation = -1L
        val keys = java.util.IdentityHashMap<Any, String?>()
        val payloads = java.util.IdentityHashMap<Any, Any?>()

        fun maybeReset(current: Long) {
            if (generation != current) {
                generation = current
                keys.clear()
                payloads.clear()
            }
        }
    }

    private val sPassMemo = object : ThreadLocal<PassMemo>() {
        override fun initialValue(): PassMemo = PassMemo()
    }

    private fun passMemo(): PassMemo = sPassMemo.get() ?: PassMemo().also { sPassMemo.set(it) }

    // FeedListPipeline 每轮过滤前调用: 递增代数使所有线程的 memo 逐次失效
    fun beginFilterPass() {
        sPassGeneration.incrementAndGet()
    }

    // 当前管线代数: CardParamsApi 的双槽位 memo 以同一代数失效, 与本对象
    // (b()/d() memo)在每轮 applyStages 一起作废, 见 CardParamsApi.ParamMemo
    fun currentPassGeneration(): Long = sPassGeneration.get()

    // ===== 模板卡过滤器共享脚手架 =====
    // 此前 Bounty/GamePromo/Goods/Score 四个 PostFilter 各持一份逐行相同的
    // filterTemplateList 两级拦截主循环(反编译实测互相克隆 100-160 行)与
    // CardHeadUiState/Map 字段反射工具, 收敛于此: 各过滤器只声明自己的
    // 键表与判定谓词(TemplateFilterSpec), 语义与各文件原实现一一对应

    // 模板卡键: 内层 head 分段与外层合成卡(口径与 ForbidAd4TieBa 一致)
    private const val FEED_HEAD_KEY = "feed_head"
    private const val OUTER_COMPOSITE_KEY = "common_card"

    // feed_head 卡的 UI 态类(类名稳定), 仅作候选判定; 类名混淆时 head 由
    // 模板键兜底。失败置位不重试(旧版本宿主上避免热路径反复构造异常),
    // volatile 发布顺序同各过滤器原实现: 结果先写, resolved 后置
    @Volatile
    private var headStateClass: Class<*>? = null

    @Volatile
    private var headStateResolved = false
    private val headStateLock = Any()

    fun resolveHeadState(): Class<*>? {
        if (headStateResolved) return headStateClass
        synchronized(headStateLock) {
            if (headStateResolved) return headStateClass
            headStateClass = runCatching {
                findClass("com.baidu.tieba.feed.component.uistate.CardHeadUiState")
            }.getOrNull()
            headStateResolved = true
            return headStateClass
        }
    }

    // 类的全部 Map 实例字段(含继承), 按类缓存
    private val elementMapFields = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>(16)

    fun resolveMapFields(clazz: Class<*>): List<Field> {
        elementMapFields[clazz]?.let { return it }
        val out = ArrayList<Field>(2)
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (!Map::class.java.isAssignableFrom(field.type)) continue
                field.isAccessible = true
                out.add(field)
            }
            current = current.superclass
        }
        elementMapFields[clazz] = out
        return out
    }

    // 合成卡载荷内的内层卡是否含给定模板键(Goods/Score 同构实现收编)
    fun payloadHasKey(payload: Any, keys: Set<String>): Boolean {
        for (field in resolveListFields(payload.javaClass)) {
            val inner = runCatching { field.get(payload) as? List<*> }.getOrNull() ?: continue
            for (element in inner) {
                if (element == null) continue
                val key = cardKey(element)
                if (key != null && key in keys) return true
            }
        }
        return false
    }

    // 合成卡载荷内的内层卡是否含 feed_head 且命中谓词(Bounty/GamePromo 同构
    // 实现收编): 经 CardHeadUiState 实例(类名稳定)或内层卡键 feed_head 双路定位
    fun payloadHasHead(payload: Any, predicate: (Any) -> Boolean): Boolean {
        val headClass = resolveHeadState()
        for (field in resolveListFields(payload.javaClass)) {
            val inner = runCatching { field.get(payload) as? List<*> }.getOrNull() ?: continue
            for (element in inner) {
                if (element == null) continue
                val viaClass = headClass?.isInstance(element) == true
                if (!viaClass && cardKey(element) != FEED_HEAD_KEY) continue
                if (predicate(element)) return true
            }
        }
        return false
    }

    // 合成卡载荷内的内层 feed_head 元素(CardHeadUiState, 卡片作者栏); 无则 null。
    // 双路定位口径与 payloadHasHead 一致
    fun payloadHeadElement(payload: Any): Any? {
        val headClass = resolveHeadState()
        for (field in resolveListFields(payload.javaClass)) {
            val inner = runCatching { field.get(payload) as? List<*> }.getOrNull() ?: continue
            for (element in inner) {
                if (element == null) continue
                val viaClass = headClass?.isInstance(element) == true
                if (!viaClass && cardKey(element) != FEED_HEAD_KEY) continue
                return element
            }
        }
        return null
    }

    // 过滤规格: 各 PostFilter 声明自己的键表与判定谓词
    // - dropKeys: 独立卡直接移除的模板键
    // - innerKeys: dropKeys 中属于内层分段组件的键, 命中才允许整列表置空
    //   (独立卡直接移除即可, 否则不含合成卡的列表会被误整页清空)
    // - headPredicate: feed_head 载荷(CardHeadUiState)直接判定, 命中标记 innerBlocked
    // - payloadPredicate: 外层合成卡载荷判定, 命中整帖移除
    // - payloadCheckAllOuter: 载荷判定不限于 common_card——Bounty/GamePromo 原实现
    //   对任意非 head/命中键的外层卡都做载荷探测, Goods/Score 仅对 common_card
    class TemplateFilterSpec(
        val dropKeys: Set<String>,
        val innerKeys: Set<String> = emptySet(),
        val headPredicate: ((Any) -> Boolean)? = null,
        val payloadPredicate: ((Any) -> Boolean)? = null,
        val payloadCheckAllOuter: Boolean = false,
    )

    // 入口名推断"是否内层分段列表"的 owner 纠偏。TemplateAdapter.setList 不只被
    // 内层分段适配器调用: FeedTemplateAdapter 的 P/R/L 内部转调 setList
    // (jadx_2214origin 实证, FeedTemplateAdapter extends TemplateAdapter), 外层
    // 首页列表必以 "setList" 二跳过管线。仅凭入口名会把外层误判为内层——
    // Bounty/GamePromo 的 feed_head 命中项在外层第一跳只标记不移除, 存活进二跳
    // 后经"整列表置空"分支可把整套适配器数据清空(首页条件性整页白屏)。
    // owner 结构判定纠偏: FeedTemplateAdapter 及其子类按外层处理(置空分支失效
    // 方向为 fail-open, 最坏只是内层置空兜底不生效, 不会误清页); 真内层分段
    // 适配器(CommentTemplateAdapter/PersonPageTemplateAdapter/CommentFloorView
    // 匿名子类, 22.14 全量检索实证)均非其子类, 内层行为不变
    @Volatile
    private var outerAdapterClass: Class<*>? = null

    @Volatile
    private var outerAdapterResolved = false

    fun isOuterFeedAdapter(owner: Any?): Boolean {
        if (owner == null) return false
        if (!outerAdapterResolved) {
            outerAdapterClass = runCatching {
                findClass("com.baidu.tieba.feed.list.FeedTemplateAdapter")
            }.getOrNull()
            outerAdapterResolved = true
        }
        return outerAdapterClass?.isInstance(owner) ?: false
    }

    /**
     * 复制式过滤公共骨架(收编自 PurgeAd/MsgCenterPurge/Purge/HotPostFilter/
     * FollowFilter/RecommendForumFilter/PurgeVideo/LivePostFilter 逐字同构的
     * 手写主循环): 不原地 removeIf(入参可能为不可变列表, 原地删会抛异常击穿
     * 宿主), 命中时才复制新列表, 无命中返回原列表引用零分配。
     *
     * @param isDrop 条目移除谓词(纯 item 驱动); null 条目口径由谓词决定
     *               —— 多数调用点按 fail-open 惯例保留, MsgCenterPurge 的
     *               广播/频道两处与各自旧实现一致选择剔除
     */
    fun filterCopy(list: List<*>, isDrop: (Any?) -> Boolean): List<*> {
        var out: MutableList<Any?>? = null
        for (i in list.indices) {
            val item = list[i]
            val drop = isDrop(item)
            if (drop && out == null) {
                out = ArrayList(list.size)
                for (j in 0 until i) out.add(list[j])
            } else if (!drop && out != null) {
                out.add(item)
            }
        }
        return out ?: list
    }

    // 两级拦截主循环(收编自 Bounty/GamePromo/Goods/Score 的逐字同构实现),
    // 返回原列表表示无需修改:
    // - 内层分段列表(feed_head/title/... 渲染必经 TemplateAdapter.setList):
    //   内层键/head 命中且该列表无外层合成卡时整列表置空, 该帖无内容可渲染;
    // - 外层入口(g0/j0/L/P/R): 只移除条目本身, 防未知形态把整页清空
    fun filterTemplateList(list: List<*>?, innerList: Boolean, spec: TemplateFilterSpec): List<*>? {
        if (list == null || list.isEmpty()) return list
        if (!resolve()) return list
        var out: MutableList<Any?>? = null
        var innerBlocked = false
        var hasOuterComposite = false
        for (i in list.indices) {
            val item = list[i]
            var drop = false
            if (item != null) {
                val key = cardKey(item)
                if (key == OUTER_COMPOSITE_KEY) {
                    hasOuterComposite = true
                    val payload = runCatching { cardPayload(item) }.getOrNull()
                    if (payload != null && spec.payloadPredicate?.invoke(payload) == true) drop = true
                } else if (key != null && key in spec.dropKeys) {
                    drop = true
                    if (key in spec.innerKeys) innerBlocked = true
                } else if (key == FEED_HEAD_KEY && spec.headPredicate != null) {
                    // 内层分段列表: feed_head 的载荷即 CardHeadUiState, 直接判定
                    val head = runCatching { cardPayload(item) }.getOrNull()
                    if (head != null && spec.headPredicate.invoke(head)) innerBlocked = true
                } else if (spec.payloadCheckAllOuter && spec.payloadPredicate != null) {
                    // Bounty/GamePromo 口径: 任意其他外层卡键也做载荷内 head 探测
                    val payload = runCatching { cardPayload(item) }.getOrNull()
                    if (payload != null && spec.payloadPredicate.invoke(payload)) drop = true
                }
            }
            if (drop && out == null) {
                out = ArrayList(list.size)
                for (j in 0 until i) out.add(list[j])
            } else if (!drop) {
                out?.add(item)
            }
        }
        if (innerList && innerBlocked && !hasOuterComposite) {
            return ArrayList<Any?>()
        }
        return out ?: list
    }
}
