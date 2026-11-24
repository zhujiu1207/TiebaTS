package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.XposedContext.Companion.hookBeforeMethod
import gm.tieba.tabswitch.XposedContext.Companion.hookReplaceMethod
import gm.tieba.tabswitch.bridge.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

// 帖子页列表广告 + 帖子页广告网络请求层。从 PurgeAd 原文搬移（逐字保留注释）。
// isPostAdItem 判定的两块缓存（首楼推荐类/getType 方法）随本对象走，
// 判定入口为本对象的 filterPostAdList（FeedListPipeline 与列表入口均经它进入）。
internal object PurgeAdPostPage {

    // TypeAdapter/RecyclerViewTypeAdapter 的数据入口: 唯一的 void(List) 实例方法
    // (参照 ForbidAd4TieBa PostAdDataFilterSymbolScanner 的结构定位)
    internal fun blockPostPageAds() {
        val listType = java.util.List::class.java
        val uniqueIdClass = try {
            findClass(PurgeAdKeys.BD_UNIQUE_ID)
        } catch (ignored: Throwable) {
            return
        }
        for (adapterName in listOf(PurgeAdKeys.TYPE_ADAPTER, PurgeAdKeys.RECYCLER_TYPE_ADAPTER)) {
            try {
                val matches = findClass(adapterName).declaredMethods.filter { md ->
                    !Modifier.isStatic(md.modifiers) &&
                        md.returnType == Void.TYPE &&
                        md.parameterTypes.size == 1 &&
                        md.parameterTypes[0] == listType
                }
                // 仅唯一匹配时 hook, 避免误伤
                if (matches.size != 1) continue
                hookBeforeMethod(matches[0]) { param ->
                    param.args[0] = filterPostAdList(param.args[0] as? List<*>, uniqueIdClass)
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun filterPostAdList(list: List<*>?, uniqueIdClass: Class<*>): List<*>? {
        if (list == null || list.isEmpty()) return list
        val blockedTypes = resolveAdvertAppTypes(uniqueIdClass) ?: return list
        return Fs9Api.filterCopy(list) { item ->
            item != null && isPostAdItem(item, uniqueIdClass, blockedTypes)
        }
    }

    private fun isPostAdItem(item: Any, uniqueIdClass: Class<*>, blockedTypes: Set<Any>): Boolean {
        // 首楼推荐数据直接拦截(注册后类引用不变, 按结果缓存 findClass:
        // 此前每元素一次 Class.forName, 帖子页高频路径放大反射开销)
        if (firstFloorDataClass === UNRESOLVED_SENTINEL) {
            firstFloorDataClass = try {
                findClass(PurgeAdKeys.PB_FIRST_FLOOR_RECOMMEND_DATA)
            } catch (ignored: Throwable) {
                null
            }
        }
        if (firstFloorDataClass?.isAssignableFrom(item.javaClass) == true) {
            return true
        }
        // DataItem.getType() 返回 BdUniqueId, 与 AdvertAppInfo 的广告类型常量比对
        val md = resolveGetTypeMethod(item.javaClass, uniqueIdClass) ?: return false
        val type = runCatching { md.invoke(item) }.getOrNull() ?: return false
        return blockedTypes.contains(type)
    }

    // 首楼推荐数据类缓存: 哨兵区分"未解析"与"解析失败(类不存在)"
    private val UNRESOLVED_SENTINEL: Class<*> = PurgeAdPostPage::class.java

    @Volatile
    private var firstFloorDataClass: Class<*>? = UNRESOLVED_SENTINEL

    @Volatile
    private var advertAppTypes: Set<Any>? = null

    // 解析失败负缓存: 本函数挂在全 app 列表页的灌入热路径上, 版本漂移导致类缺失
    // 或无常量字段时, 每次列表灌入都要重付 findClass 的异常构造 + declaredFields
    // 全扫描; 失败一旦确认进程内不再重试(宿主升级后新进程自然恢复), 与上方
    // firstFloorDataClass 的哨兵口径一致
    @Volatile
    private var advertTypesFailed = false

    private fun resolveAdvertAppTypes(uniqueIdClass: Class<*>): Set<Any>? {
        advertAppTypes?.let { return it }
        if (advertTypesFailed) return null
        return try {
            val types = HashSet<Any>()
            for (field in findClass(PurgeAdKeys.ADVERT_APP_INFO).declaredFields) {
                if (Modifier.isStatic(field.modifiers) && uniqueIdClass.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    val value = field.get(null) ?: continue
                    types.add(value)
                }
            }
            if (types.isEmpty()) {
                advertTypesFailed = true
                null
            } else types.also { advertAppTypes = it }
        } catch (ignored: Throwable) {
            advertTypesFailed = true
            null
        }
    }

    private val typeMethodCache = ConcurrentHashMap<Class<*>, Any>()
    private val NO_METHOD = Any()

    private fun resolveGetTypeMethod(clazz: Class<*>, uniqueIdClass: Class<*>): Method? {
        typeMethodCache[clazz]?.let { cached ->
            return if (cached === NO_METHOD) null else cached as Method
        }
        return try {
            val candidates = clazz.methods.filter { md ->
                md.parameterTypes.isEmpty() && uniqueIdClass.isAssignableFrom(md.returnType)
            }
            val md = candidates.singleOrNull()
            if (md != null) {
                md.isAccessible = true
                typeMethodCache[clazz] = md
                md
            } else {
                typeMethodCache[clazz] = NO_METHOD
                null
            }
        } catch (ignored: Throwable) {
            typeMethodCache[clazz] = NO_METHOD
            null
        }
    }

    // ---------- 帖子页广告网络请求层 ----------

    // PbPageRequestMessage.encode: 编码请求前清空广告位参数; isReqAd 置 0 不请求广告
    internal fun blockPbAdRequest() {
        try {
            hookBeforeMethod(PurgeAdKeys.PB_PAGE_REQUEST_MESSAGE, "encode", Boolean::class.javaPrimitiveType) { param ->
                val request = param.thisObject ?: return@hookBeforeMethod
                for (field in PurgeAdKeys.PB_REQUEST_CLEAR_FIELDS) {
                    runCatching { XposedHelpers.setObjectField(request, field, "") }
                }
                runCatching { XposedHelpers.setIntField(request, "isReqAd", 0) }
            }
        } catch (ignored: Throwable) {
        }
        // PageBrowserRequestMessage.addAdRequestMessage: 不再向 PbList 请求附加广告子请求
        try {
            hookReplaceMethod(
                PurgeAdKeys.PAGE_BROWSER_REQUEST_MESSAGE, "addAdRequestMessage",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                findClass(PurgeAdKeys.PB_LIST_DATA_REQ_BUILDER)
            ) { null }
        } catch (ignored: Throwable) {
        }
    }
}
