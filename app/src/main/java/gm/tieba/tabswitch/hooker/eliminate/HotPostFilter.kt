package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.util.Logger
import java.lang.reflect.Field
import java.lang.reflect.Modifier

// 屏蔽首页热点, 移植自 ForbidAd4TieBa 的"屏蔽热点"(custom_post_filter.hot):
// 屏蔽推荐信息组件、实时热点和热门话题卡片。
// 22.11 首页推荐流为模板卡片体系, 与 PurgeVideo 共用同一套列表入口与
// 模板卡数据项接口((22.12=nt9/fs9, 22.13=eu9): b()=卡片键, d()=载荷), 在列表入口按键过滤,
// 合成卡载荷内的内层卡命中也整帖移除。
// 注意: 热点TOP1/实时热点话题卡(有料热榜插入推荐流)的模板键 b() 仍是
// common_card, 贴吧以卡参数 card_type 区分它们(见 PersonalizePageView 与
// xp9.c/d: mr9.f()/mr9.c().a() 的 card_type 为 heatTopicNormal/Vote/Pk),
// 故对这两类卡按 card_type 参数过滤, 其余仍按模板键。
// 实机(22.11.1.0)上实时热点卡以 home_delegate_card 合成卡形态出现
// (HomeDelegateCardView 内嵌 RecyclerView, 内层为 BaseCardInfo 列表),
// 外层 gs9 与内层 bg 都不是普通 mr9 参数载体, 故另有"内层参数探测":
// 对合成卡载荷列表元素反射其无参 Map 方法读取 card_type(贴吧侧同类判定见
// FeedHeadExtensionKt/CardAbstractView 对 card_type 的读取)。
// 22.11.1.0 修复: 卡的跳转 schema 并不在参数 Map 内(f()/c().a() 实测无
// "schema" 键, 诊断日志 schema 恒为 "-"), 而是存在卡数据类(载荷)的
// final String 字段中并有专用 getter(见 ForbidAd4TieBa
// FeedCardSchemaSymbolScanner 的 DexKit 调用关系定位)。此处不用 DexKit:
// 直接遍历载荷与内层元素的全部 String 字段值做 HotTopicRoute 判定,
// 字段列表按类缓存; 只要卡的跳转目的地是热点话题详情页即整卡移除,
// 与服务器下发什么 card_type/模板键无关。
class HotPostFilter : XposedContext(), IHooker {

    private val loggedSigs = HashSet<String>(64)

    // 走模块日志而非 logcat 直写: 与运行日志开关语义一致(诊断只在用户要求留痕时落盘)
    private fun logDiag(msg: String) {
        Logger.log("[HotPostDiag] $msg")
    }

    // 同一签名只记一次, 上限 300 条防刷屏
    private fun logSigOnce(sig: String, detail: String) {
        synchronized(loggedSigs) {
            if (loggedSigs.size >= 300 || !loggedSigs.add(sig)) return
        }
        logDiag(detail)
    }

    override fun key(): String {
        return "purge_hot_post"
    }

    // 原此处有约 150 行 DEBUG=false 的临时诊断基建(话题导航多路钩子/卡签名
    // 记录/每轮 PASS-DUMP 日志), 运行期整体不可达, 已随备份
    // (backup_refactor_20261115)移除; 排查"实时热点漏拦"时需要的失败留痕
    // 由 logSigOnce(unresolved 路径)承载, 判定逻辑注释完整可按需重写

    override fun hook() {
        // 列表入口 hook 统一走 FeedListPipeline(单 hook 多阶段链), 此处只注册阶段
        val status = StringBuilder("purge_hot_post entries:")
        FeedListPipeline.register { list, _, entry -> filterTemplateList(list, entry) }
        status.append(" pipeline=ok")
        // 走模块日志而非 logcat 直写: 与运行日志开关语义一致
        Logger.log(status.toString())
    }

    // ForbidAd4TieBa CustomPostFilterMatcher: HOT 模板键集合
    private val HOT_TEMPLATE_KEYS = setOf(
        "recommend_info", "hot_card", "hot_topic_card", "multi_thread_card"
    )

    // 热点TOP1/实时热点话题卡的 card_type 参数值: heatTopicScore 与
    // inspiration_card 同族(见 22.11 CardAbstractView.t), heatTopicHotCard
    // 为实时热点热卡(见 22.11 cfa.b(): ug9.l().b().a()["card_type"], 与
    // 双槽探测的 c().a() 同一槽位)
    private val HOT_CARD_TYPES = setOf(
        "heatTopicNormal", "heatTopicVote", "heatTopicPk", "heatTopicScore", "heatTopicHotCard", "inspiration_card"
    )

    // 载荷类实例的 List 字段(含继承), 共享缓存见 Fs9Api.resolveListFields
    private fun resolveListFields(clazz: Class<*>): List<Field> =
        Fs9Api.resolveListFields(clazz)

    // 卡参数接口(22.12=mr9/us9, 22.13=ds9/lt9): f()=信息Map, c()=qi9/zh9 参数载体(a()=Map); 贴吧以其中的
    // card_type 区分热点话题卡(xp9.c/d)。类名随版本轮换, 解析共享于 CardParamsApi
    private fun resolveMr9(): Boolean = CardParamsApi.resolve()

    // 模板卡数据项接口 (22.12=nt9/fs9, 22.13=eu9): b()=卡片键, d()=载荷; 与 PurgeVideo 相同(共享 Fs9Api)
    private fun resolveCardMethods(): Boolean = Fs9Api.resolve()

    private fun cardKey(item: Any?): String? {
        return Fs9Api.cardKey(item)
    }

    // 卡的 card_type 参数值: f()["card_type"] 与 c().a()["card_type"] 是两个
    // 独立槽位——热点卡实测 f() 为普通帖空(normal), heatTopic* 只在
    // c().a()(businessInfo, 贴吧 xp9.d 即读此处), 两者都返回供双槽判定
    private fun cardTypes(item: Any?): Pair<String?, String?> {
        if (item == null || !resolveMr9() || !CardParamsApi.isParamItem(item)) return null to null
        val infoType = CardParamsApi.fMap(item)?.get("card_type")?.toString()?.takeIf { it.isNotEmpty() }
        val businessType = CardParamsApi.cHolderMap(item)?.get("card_type")?.toString()?.takeIf { it.isNotEmpty() }
        return infoType to businessType
    }

    // 热点卡判定: 模板键或任一 card_type 槽位命中
    private fun isHotCard(item: Any?): Boolean {
        if (item == null) return false
        val key = cardKey(item)
        if (key != null && key in HOT_TEMPLATE_KEYS) return true
        return isHotCardType(item)
    }

    // 仅按双槽 card_type 判断; 内层 22.11 模板组件用这个,
    // 避免普通帖内层出现 multi_thread_card 等组件被 legacy 键误杀
    private fun isHotCardType(item: Any?): Boolean {
        if (item == null) return false
        val (infoType, businessType) = cardTypes(item)
        if ((infoType != null && infoType in HOT_CARD_TYPES) ||
            (businessType != null && businessType in HOT_CARD_TYPES)
        ) return true
        // 跳转目的地兜底: 跳热点话题详情页的卡无论 card_type 为何都算热点
        // (移植自 ForbidAd4TieBa HotTopicRoute, 防服务器下发新型 card_type 漏拦)
        return isHotTopicRoute(cardSchema(item))
    }

    // 卡参数里的跳转 schema: f()["schema"] 与 c().a()["schema"] 两个槽位都查
    private fun cardSchema(item: Any?): String? {
        if (item == null || !resolveMr9() || !CardParamsApi.isParamItem(item)) return null
        CardParamsApi.fMap(item)?.get("schema")?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
        return CardParamsApi.cHolderMap(item)?.get("schema")?.toString()?.takeIf { it.isNotEmpty() }
    }

    // 内层卡参数探测拿到的 schema(与 innerCardType 同一取值器)
    private fun innerSchema(element: Any): String? {
        for (accessor in resolveMapAccessors(element.javaClass)) {
            val map = runCatching { accessor(element) }.getOrNull() ?: continue
            val schema = map.get("schema")?.toString()?.takeIf { it.isNotEmpty() } ?: continue
            return schema
        }
        return null
    }

    // 热点话题路由判定, 移植自 ForbidAd4TieBa HotTopicRoute: 只认完整卡目的地,
    // 直接跳 naTopicDetail, 或经 tiebaapp://router/portal 包一层(h5/openWebView
    // 再跳 naTopicDetail)的都算; 组件内嵌链接与话题文本不参与判定
    private fun isHotTopicRoute(schema: String?): Boolean {
        if (schema.isNullOrEmpty()) return false
        // 前缀短路: 两种命中形式(直跳话题页 / portal 包装跳 url)的字面量都必含
        // "naTopicDetail"。该判定在每条 String 字段上高频调用, 绝大多数普通帖/
        // 广告 schema 不含此串, 先拦掉可省去 URI 构造与 JSON 解析
        if (!schema.contains("naTopicDetail", ignoreCase = true)) return false
        return try {
            val route = java.net.URI(schema)
            if (isTopicPage(route)) return true
            if (!route.scheme.equals("tiebaapp", ignoreCase = true) ||
                !route.host.equals("router", ignoreCase = true) || route.path != "/portal" ||
                route.userInfo != null || route.port != -1
            ) return false
            val params = queryValue(route.rawQuery, "params") ?: return false
            val page = org.json.JSONObject(params)
            if (page.opt("page") != "h5/openWebView") return false
            val url = page.optJSONObject("pageParams")?.opt("url") as? String ?: return false
            isTopicPage(java.net.URI(url))
        } catch (ignored: Exception) {
            false
        }
    }

    // 实测话题页 URL 带 /hybrid 后缀(naTopicDetail/hybrid?topic_id=...),
    // 且 schema 字段可能存带或不带后缀两种形式, 前缀匹配都认
    private fun isTopicPage(uri: java.net.URI): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true) && !uri.scheme.equals("http", ignoreCase = true)) return false
        if (!uri.host.equals("tieba.baidu.com", ignoreCase = true) || uri.userInfo != null) return false
        val path = uri.path ?: return false
        return path == "/mo/q/hybrid-usergrow-base/naTopicDetail" ||
            path.startsWith("/mo/q/hybrid-usergrow-base/naTopicDetail/")
    }

    private fun queryValue(query: String?, key: String): String? {
        if (query == null) return null
        var value: String? = null
        var start = 0
        while (start < query.length) {
            val end = query.indexOf('&', start).let { if (it < 0) query.length else it }
            val equals = query.indexOf('=', start)
            if (equals in start until end && query.regionMatches(start, key, 0, key.length) &&
                equals - start == key.length
            ) {
                if (value != null) return null
                value = java.net.URLDecoder.decode(query.substring(equals + 1, end), "UTF-8")
            }
            start = end + 1
        }
        return value
    }

    // 载荷/内层卡类的 String 字段缓存(含继承, 剔除静态): 跳转 schema 存在
    // 卡数据类的 final String 字段上(有专用 getter), 不在参数 Map 内,
    // 故直接对字段值做路由判定; 每类只解析一次
    private val schemaFields = java.util.concurrent.ConcurrentHashMap<Class<*>, List<Field>>(16)

    private fun resolveStringFields(clazz: Class<*>): List<Field> {
        // computeIfAbsent: ConcurrentHashMap 本身并发安全, 无需外层 synchronized
        // (此前"CHM + 手写锁"双保险, 白付锁开销); 并发首调最多重复解析一次, 幂等
        return schemaFields.computeIfAbsent(clazz) { c ->
            val out = ArrayList<Field>(8)
            var current: Class<*>? = c
            while (current != null && current != Any::class.java) {
                for (field in current.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    if (field.type != String::class.java) continue
                    try {
                        field.isAccessible = true
                    } catch (ignored: Throwable) {
                        continue
                    }
                    out.add(field)
                }
                current = current.superclass
            }
            out
        }
    }

    // 对象任一 String 字段值命中热点话题路由即true; 字段反射仅在对未知卡类
    // 首次解析后有缓存的少量 Field.get, isHotTopicRoute 精确匹配 naTopicDetail
    // 完整目的地, 卡内正文/头像等普通 http 字段不会误伤
    private fun hasHotTopicSchema(obj: Any?): Boolean {
        if (obj == null) return false
        // per-pass memo(机制同 Fs9Api.PassMemo/GameExtMemo): 管线一轮内同一载荷/
        // 内层元素会被 payloadHasHotCard 双路重复送进来(载荷自身判定 + 内层元素
        // 判定), 每次 String 字段全扫 + 逐值 URI 构造是 O(字段数) 反射; 判定结果
        // 对同一对象在一代内恒定(只读), 含 false 负结果一并缓存
        val memo = sSchemaMemo.get()!!.also { it.maybeReset(Fs9Api.currentPassGeneration()) }
        memo.results.containsKey(obj)?.let { return it }
        val result = runCatching {
            for (field in resolveStringFields(obj.javaClass)) {
                val value = field.get(obj) as? String ?: continue
                if (isHotTopicRoute(value)) return@runCatching true
            }
            false
        }.getOrDefault(false)
        memo.results[obj] = result
        return result
    }

    // hasHotTopicSchema 的 per-pass memo(含 false 负缓存)
    private class SchemaMemo {
        var generation = -1L
        val results = java.util.IdentityHashMap<Any, Boolean>()

        fun maybeReset(current: Long) {
            if (generation != current) {
                generation = current
                results.clear()
            }
        }
    }

    private val sSchemaMemo = object : ThreadLocal<SchemaMemo>() {
        override fun initialValue(): SchemaMemo = SchemaMemo()
    }

    // 从对象上提取 card_type 的取值器列表: 对象自身无参 Map 方法 + 其对象字段
    // (一级深度)的无参 Map 方法, 共享缓存见 Fs9Api.resolveMapAccessors
    private fun resolveMapAccessors(clazz: Class<*>): List<(Any) -> Map<*, *>?> =
        Fs9Api.resolveMapAccessors(clazz)

    // 内层 BaseCardInfo 的 card_type(参数探测), 找不到返回 null
    private fun innerCardType(element: Any): String? {
        for (accessor in resolveMapAccessors(element.javaClass)) {
            val map = runCatching { accessor(element) }.getOrNull() ?: continue
            val type = map.get("card_type")?.toString()?.takeIf { it.isNotEmpty() } ?: continue
            return type
        }
        return null
    }

    // 合成卡载荷内的内层卡是否含热点卡; 同时做一次性诊断记录。
    // 内层 22.11 模板组件不匹配 legacy 模板键(普通帖内层会出现
    // multi_thread_card 等组件导致误杀), 只认 heatTopic 家族 card_type
    private fun payloadHasHotCard(payload: Any): Boolean {
        // 载荷自身的 String 字段: 跳转 schema 在卡数据类字段上而非参数 Map 内,
        // 指向热点话题详情页即命中(与 card_type/模板键无关, 兜底新卡型)
        if (hasHotTopicSchema(payload)) return true
        for (field in resolveListFields(payload.javaClass)) {
            val inner = runCatching { field.get(payload) as? List<*> }.getOrNull() ?: continue
            for (element in inner) {
                if (element == null) continue
                if (isHotCardType(element)) return true
                // 实时热点合成卡: 内层 BaseCardInfo 非普通 mr9/fs9, 走参数探测
                val type = innerCardType(element)
                if (type != null && type in HOT_CARD_TYPES) return true
                // 跳转目的地兜底(双路): 参数 Map 里的 schema + 卡类 String 字段
                if (isHotTopicRoute(innerSchema(element))) return true
                if (hasHotTopicSchema(element)) return true
            }
        }
        return false
    }

    // 在适配器列表入口处过滤热点模板卡, 返回原列表表示无需修改
    private fun filterTemplateList(list: List<*>?, from: String): List<*>? {
        if (list == null || list.isEmpty()) return list
        if (!resolveCardMethods()) {
            // 模板卡接口解析失败, 过滤整体失效——诊断必须暴露这一点
            logSigOnce("unresolved", "card methods unresolved(fs9/mr9?), from=$from size=${list.size} skipped")
            return list
        }
        return Fs9Api.filterCopy(list) { item ->
            var drop = isHotCard(item)
            if (!drop && item != null) {
                // 载荷仅在独立卡判定通过后才取, 未命中时白付一次反射 invoke
                val payload = runCatching { Fs9Api.cardPayload(item) }.getOrNull()
                if (payload != null && payloadHasHotCard(payload)) {
                    drop = true
                }
            }
            drop
        }
    }
}
