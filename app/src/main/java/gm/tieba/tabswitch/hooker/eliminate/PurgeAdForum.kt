package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.XposedContext.Companion.hookBeforeMethod
import gm.tieba.tabswitch.XposedContext.Companion.hookReplaceMethod
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.util.Logger
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

// 吧页面广告/推广 + 游戏悬浮条。从 PurgeAd 原文搬移（逐字保留注释）。

internal object PurgeAdForum {

    internal fun blockForumPageAds() {
        // 吧页 proto 层: 吧友任务弹窗/精灵气泡/遮罩弹窗/进吧弹窗/红包雨
        // (business_promot/activityhead 已由 Purge 处理)
        try {
            hookBeforeMethod(
                PurgeAdKeys.FRS_DATA_RES_BUILDER,
                "build", Boolean::class.javaPrimitiveType
            ) { param ->
                val builder = param.thisObject ?: return@hookBeforeMethod
                for (field in PurgeAdKeys.FRS_POP_FIELDS) {
                    runCatching { XposedHelpers.setObjectField(builder, field, null) }
                }
            }
        } catch (ignored: Throwable) {
        }

        // 商业推广弹窗: ForumDialogController 中 (String, FrsPage$BusinessPromot) 的布尔方法
        try {
            val controllerClass = findClass(PurgeAdKeys.FORUM_DIALOG_CONTROLLER)
            val promotClass = runCatching { findClass(PurgeAdKeys.FRS_BUSINESS_PROMOT) }.getOrNull()
            val candidates = controllerClass.declaredMethods.filter { md ->
                !Modifier.isStatic(md.modifiers) &&
                    md.returnType == Boolean::class.javaPrimitiveType &&
                    md.parameterTypes.size == 2 &&
                    md.parameterTypes[0] == String::class.java &&
                    (promotClass == null || md.parameterTypes[1] == promotClass)
            }
            if (candidates.size == 1) {
                hookReplaceMethod(candidates[0]) { false }
            }
        } catch (ignored: Throwable) {
        }

        // 底部游戏悬浮条: GameFloatingBarController 的展示方法隐藏控件并不再展示。
        // 方法名随版本混淆轮换(22.11 为 showFloatingBar, 22.12 起 R8 改名为 s2),
        // 按候选名逐个尝试, 命中即止
        try {
            val barClass = findClass(PurgeAdKeys.GAME_FLOATING_BAR_CONTROLLER)
            var hooked = false
            for (name in PurgeAdKeys.GAME_FLOATING_BAR_SHOW_METHODS) {
                val md = barClass.declaredMethods.firstOrNull { candidate ->
                    !Modifier.isStatic(candidate.modifiers) &&
                        candidate.returnType == Void.TYPE &&
                        candidate.parameterTypes.isEmpty() &&
                        candidate.name == name
                } ?: continue
                hookReplaceMethod(md) { param ->
                    hideFloatingBarView(param.thisObject)
                    null
                }
                hooked = true
                break
            }
            // 漂移留痕: 候选全 miss 时该净化点静默失效, 无日志则用户反馈无从排查
            if (!hooked && Logger.isEnabled()) {
                Logger.log("[悬浮条诊断] GameFloatingBarController 展示方法未命中, " +
                    "无参 void 候选 ${PurgeAdKeys.GAME_FLOATING_BAR_SHOW_METHODS.joinToString()} 全部落空")
            }
        } catch (ignored: Throwable) {
        }

        // 商业推广跳转: BusinessPromotBiz 中唯一的 void(String) 实例方法
        try {
            val bizClass = findClass(PurgeAdKeys.BUSINESS_PROMOT_BIZ)
            val candidates = bizClass.declaredMethods.filter { md ->
                !Modifier.isStatic(md.modifiers) &&
                    md.returnType == Void.TYPE &&
                    md.parameterTypes.size == 1 &&
                    md.parameterTypes[0] == String::class.java
            }
            if (candidates.size == 1) {
                hookReplaceMethod(candidates[0]) { null }
            }
        } catch (ignored: Throwable) {
        }
    }

    // 悬浮条视图字段按类缓存(含 null 负缓存, 哨兵占位): 字段布局类级稳定,
    // 该钩点在吧页滚动中反复触发, 每次重扫 declaredFields 纯属浪费
    private val sFloatingBarFieldCache =
        java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    private val sNoFloatingBarField = Any()

    private fun hideFloatingBarView(controller: Any?) {
        try {
            val field = controller?.javaClass?.let { clazz ->
                sFloatingBarFieldCache.computeIfAbsent(clazz) { c ->
                    c.declaredFields.firstOrNull { it.type.name == PurgeAdKeys.FLOATING_BAR_VIEW }?.also {
                        it.isAccessible = true
                    } ?: sNoFloatingBarField
                }
            }
            if (field == null || field === sNoFloatingBarField) return
            val view = (field as java.lang.reflect.Field).get(controller) as? android.view.View
            view?.visibility = android.view.View.GONE
            view?.alpha = 0f
        } catch (ignored: Throwable) {
        }
    }

    // ---------- 首页搜索框推广词 ----------

    private val homeSearchBoxes = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    // 仅处理首页 PersonalizeHeaderViewController 持有的 TbSearchBoxView, 避免误伤其它搜索框
    internal fun blockSearchBoxPromoText() {
        try {
            val searchBoxClass = findClass(PurgeAdKeys.SEARCH_BOX_VIEW)
            val ownerClass = findClass(PurgeAdKeys.SEARCH_BOX_OWNER)
            val recyclerViewClass = findClass(PurgeAdKeys.BD_TYPE_RECYCLER_VIEW)
            val coordinatorClass = findClass(PurgeAdKeys.COORDINATOR_LAYOUT)
            // 标记: init(BdTypeRecyclerView, CoordinatorLayout) 之后经 getter 持有首页搜索框
            val initMethod = ownerClass.declaredMethods.singleOrNull { md ->
                !Modifier.isStatic(md.modifiers) &&
                    md.returnType == Void.TYPE &&
                    md.parameterTypes.size == 2 &&
                    recyclerViewClass.isAssignableFrom(md.parameterTypes[0]) &&
                    coordinatorClass.isAssignableFrom(md.parameterTypes[1])
            }
            val getter = ownerClass.declaredMethods.singleOrNull { md ->
                !Modifier.isStatic(md.modifiers) &&
                    md.parameterTypes.isEmpty() &&
                    searchBoxClass.isAssignableFrom(md.returnType)
            }
            if (initMethod != null && getter != null) {
                gm.tieba.tabswitch.XposedContext.Companion.hookAfterMethod(initMethod) { param ->
                    val box = runCatching { getter.invoke(param.thisObject) }.getOrNull()
                    if (box != null) {
                        homeSearchBoxes[box] = true
                    }
                }
            }
            // 轮播推广词改写为空列表
            val setHint = searchBoxClass.declaredMethods.singleOrNull { md ->
                !Modifier.isStatic(md.modifiers) &&
                    md.returnType == Void.TYPE &&
                    md.parameterTypes.size == 2 &&
                    md.parameterTypes[0] == java.util.List::class.java &&
                    md.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            setHint?.let { md ->
                hookBeforeMethod(md) { param ->
                    if (homeSearchBoxes.containsKey(param.thisObject)) {
                        param.args[0] = ArrayList<Any>()
                        param.args[1] = false
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
    }
}
