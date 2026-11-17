package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// 屏蔽首页"可能感兴趣的吧"推荐卡片, 移植自 ForbidAd4TieBa 的
// "屏蔽可能感兴趣的吧"(custom_post_filter.recommend_forum):
// 推荐吧卡片模板键为 recommend_card_forum_attention/recommend_card_person_attention
// /recommend_card_list/sideway_list, 独立卡直接移除; 合成卡载荷内的内层卡命中
// 则整帖移除。推荐吧合成卡(RecommendCardView 绑定的 State)的卡片列表不在载荷
// 直接 List 字段内, 而藏在嵌套数据对象中(State 字段+无参方法可得), 需单独解析:
// 从稳定类名 com.baidu.tieba.feed.component.RecommendCardView 的绑定方法
// (无返回值、单参数、参数为 com.baidu.tieba.* 类)取 State 类, 在其中找
// "无参且返回类型同时是 State 字段类型"的方法, 且返回类型恰好只有 1 个
// List 实例字段, 结构唯一匹配才启用。
// 列表入口为 FeedTemplateAdapter L/P/R + TemplateAdapter.setList(未挂
// HomeTemplateAdapter g0/j0 兜底, 与 HotPostFilter 的双兜底入口不同),
// 卡片键取模板卡数据项接口 (22.12=nt9/fs9, 22.13=eu9) 的 b()。
class RecommendForumFilter : XposedContext(), IHooker {

    override fun key(): String {
        return "purge_recommend_forum"
    }

    override fun hook() {
        // 列表入口 hook 统一走 FeedListPipeline(单 hook 多阶段链), 此处只注册阶段。
        // FollowFilter 复用本类的 filterTemplateList, 也经其自身注册的阶段生效
        FeedListPipeline.register { list, _, _ -> filterTemplateList(list) }
    }

    // ForbidAd4TieBa CustomPostFilterMatcher: RECOMMEND_FORUM 模板键集合
    private val RECOMMEND_FORUM_TEMPLATE_KEYS = setOf(
        "recommend_card_forum_attention", "recommend_card_person_attention",
        "recommend_card_list", "sideway_list"
    )

    // 模板卡数据项接口 (22.12=nt9/fs9, 22.13=eu9): b()=卡片键, d()=载荷; 内层卡实现同一接口(共享 Fs9Api)
    private fun resolveCardMethods(): Boolean = Fs9Api.resolve()

    private fun cardKey(item: Any?): String? {
        return Fs9Api.cardKey(item)
    }

    // 载荷类实例的 List 字段(含继承), 共享缓存见 Fs9Api.resolveListFields
    private fun resolveListFields(clazz: Class<*>): List<Field> =
        Fs9Api.resolveListFields(clazz)

    // 嵌套数据入口: State类 + 取嵌套数据的方法 + 嵌套数据内的List字段。
    // resolveNestedData 会被主线程与后台解码线程并发调用(经 FeedListPipeline):
    // 置位标志必须 volatile, 否则另一线程可能看到 resolved=true 而结果字段
    // 尚未发布, 从此永远返回 false(过滤器静默失效)
    @Volatile
    private var nestedStateClass: Class<*>? = null
    @Volatile
    private var nestedDataMethod: Method? = null
    @Volatile
    private var nestedDataListField: Field? = null
    @Volatile
    private var nestedResolved = false

    // 与 ForbidAd4TieBa RecommendCardSymbolScanner 同规则, 唯一匹配才生效
    private fun resolveNestedData(): Boolean {
        if (nestedResolved) return nestedStateClass != null
        try {
            val viewClass = findClass("com.baidu.tieba.feed.component.RecommendCardView")
            val candidates = ArrayList<Triple<Class<*>, Method, Field>>(4)
            for (bindMethod in viewClass.declaredMethods) {
                if (Modifier.isStatic(bindMethod.modifiers) || bindMethod.isSynthetic || bindMethod.isBridge) continue
                if (bindMethod.returnType != Void.TYPE) continue
                if (bindMethod.parameterTypes.size != 1) continue
                if (bindMethod.parameterTypes[0].isPrimitive) continue
                if (!bindMethod.parameterTypes[0].name.startsWith("com.baidu.tieba.")) continue
                val stateClass = bindMethod.parameterTypes[0]
                val stateFieldTypes = HashSet<Class<*>>()
                val stateMethods = ArrayList<Method>(32)
                var current: Class<*>? = stateClass
                while (current != null && current != Any::class.java) {
                    for (field in current.declaredFields) {
                        if (Modifier.isStatic(field.modifiers)) continue
                        stateFieldTypes.add(field.type)
                    }
                    for (method in current.declaredMethods) {
                        if (Modifier.isStatic(method.modifiers)) continue
                        stateMethods.add(method)
                    }
                    current = current.superclass
                }
                for (method in stateMethods) {
                    if (method.isSynthetic || method.isBridge) continue
                    if (method.parameterTypes.isNotEmpty()) continue
                    val nestedType = method.returnType
                    if (nestedType.isPrimitive || nestedType == Void.TYPE || nestedType == String::class.java) continue
                    if (!stateFieldTypes.contains(nestedType)) continue
                    val listFields = resolveListFields(nestedType)
                    if (listFields.size != 1) continue
                    candidates.add(Triple(stateClass, method, listFields[0]))
                }
            }
            val distinct = candidates.distinctBy { "${it.first.name}.${it.second.name}:${it.third.name}" }
            if (distinct.size == 1) {
                nestedStateClass = distinct[0].first
                nestedDataMethod = distinct[0].second.also { it.isAccessible = true }
                nestedDataListField = distinct[0].third
                if (gm.tieba.tabswitch.util.Logger.isEnabled()) {
                    gm.tieba.tabswitch.util.Logger.log(
                        "recommend_forum nested: state=${distinct[0].first.name} " +
                            "method=${distinct[0].second.name} field=${distinct[0].third.name}"
                    )
                }
            } else {
                gm.tieba.tabswitch.util.Logger.log("recommend_forum nested candidates=${distinct.size}")
            }
        } catch (ignored: Throwable) {
        }
        // 置位必须在结果发布之后: volatile 只保证之前的写可见, 先置位的话
        // 并发首调线程可能读到 resolved=true 而结果字段还是 null(注释见上方字段声明)
        nestedResolved = true
        return nestedStateClass != null
    }

    // 载荷为推荐吧 State 时取嵌套数据里的内层卡判定
    private fun payloadHasNestedRecommendCard(payload: Any): Boolean {
        if (!resolveNestedData()) return false
        val stateClass = nestedStateClass ?: return false
        if (!stateClass.isInstance(payload)) return false
        val nested = runCatching { nestedDataMethod?.invoke(payload) }.getOrNull() ?: return false
        val list = runCatching { nestedDataListField?.get(nested) as? List<*> }.getOrNull() ?: return false
        for (element in list) {
            if (element == null) continue
            val key = cardKey(element) ?: continue
            if (key in RECOMMEND_FORUM_TEMPLATE_KEYS) return true
        }
        return false
    }

    // 合成卡载荷内的内层卡是否含推荐吧卡
    private fun payloadHasRecommendCard(payload: Any): Boolean {
        for (field in resolveListFields(payload.javaClass)) {
            val inner = runCatching { field.get(payload) as? List<*> }.getOrNull() ?: continue
            for (element in inner) {
                if (element == null) continue
                val key = cardKey(element) ?: continue
                if (key in RECOMMEND_FORUM_TEMPLATE_KEYS) return true
            }
        }
        return false
    }

    // 在适配器列表入口处过滤推荐吧模板卡; 返回原列表表示无需修改。
    // 非 private: FollowFilter(只推荐已关注的吧)复用同一检测丢弃推荐吧卡
    fun filterTemplateList(list: List<*>?): List<*>? {
        if (list == null || list.isEmpty()) return list
        if (!resolveCardMethods()) return list
        return Fs9Api.filterCopy(list) { item ->
            var drop = false
            if (item != null) {
                val key = cardKey(item)
                if (key != null && key in RECOMMEND_FORUM_TEMPLATE_KEYS) {
                    // 独立推荐吧卡直接移除
                    drop = true
                } else {
                    // 合成卡: 载荷内的内层卡(直接List字段或嵌套数据)含推荐吧卡则整帖移除
                    val payload = runCatching { Fs9Api.cardPayload(item) }.getOrNull()
                    if (payload != null &&
                        (payloadHasRecommendCard(payload) || payloadHasNestedRecommendCard(payload))
                    ) {
                        drop = true
                    }
                }
            }
            if (drop && gm.tieba.tabswitch.util.Logger.isEnabled()) {
                gm.tieba.tabswitch.util.Logger.log("recommend_forum dropped")
            }
            drop
        }
    }
}
