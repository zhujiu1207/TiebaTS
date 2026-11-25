package gm.tieba.tabswitch.hooker.eliminate

import android.os.SystemClock
import android.view.View
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.bridge.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class FoldTopCardView : XposedContext(), IHooker {

    override fun key(): String {
        return "fold_top_card_view"
    }

    override fun hook() {
        // 12.74 及以下: TopCardView 仍存在, 直接把展开判定方法置 false
        // 22.11+: 置顶区改由 TbBottomSheetView 承载, 进入吧页面时经 setup()/initScroll() 自动展开。
        // 22.14 起 FE(frsPage.initHybridData/forceUpdateTabHeight) 的通知时序不再保证
        // smoothInit==2 时机, 旧挂起-重放方案失效(2026-11-13 用户反馈+模拟器取证),
        // 22.14+ 改走 initScroll 改参直折; 22.11-22.13 保留旧实现(多年验证正常, 不动)
        if (DeobfuscationHelper.isTbSatisfyVersionRequirement("22.14.0.0")) {
            hookBottomSheetDirectFold()
        } else if (DeobfuscationHelper.isTbSatisfyVersionRequirement("22.11.0.0")) {
            hookBottomSheetAutoExpand()
        } else {
            hookTopCardViewExpand()
        }
    }

    private fun hookTopCardViewExpand() {
        // 总是折叠置顶帖
        findClass("com.baidu.tieba.forum.view.TopCardView").declaredMethods.filter { method ->
            method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == MutableList::class.java &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }.forEach { method ->
            hookReplaceMethod(method) { false }
        }
    }

    // ===== 22.11-22.13 旧实现(与 5.1.9 备份一致, 勿改动——该区间用户验证正常) =====

    private companion object {
        const val SHEET_VIEW_CLASS = "com.baidu.tieba.forum.widget.TbBottomSheetView"

        // setup(mode, ?, ?, ?) 的展开模式: 1 折叠 / 2 半展开 / 3 完全展开
        const val NATIVE_COLLAPSED_MODE = 1
        const val NATIVE_PARTIAL_EXPANDED_MODE = 2
        const val NATIVE_FULLY_EXPANDED_MODE = 3

        // getSmoothInit() 的 web 高度初始化状态, 2 表示 web 高度尚未就绪, 自动展开发生在该状态下
        const val NATIVE_WEB_HEIGHT_INIT_STATE = 2
        const val TERMINAL_STATE_RECHECK_MS = 32L
        const val NATIVE_STATE_TIMEOUT_MS = 2_000L

        // 导航栏透明度联动入口按宿主版本轮换(2026-11-13 四版逐一核对,
        // 2026-11-25 补 22.15):
        // 门面类.j(activity) 返回 NavigationBarController, 其 f2(float) 方法把
        // 标题栏背景/图标/吧名/文字整体渐变到给定透明度。22.14 门面类是
        // x7a、22.15 起是 w8a(方法布局同构, NavigationBarController.f2(float)
        // 本体未漂移, 体内 forumIcon/forumName/barBgView.setAlpha 逐字一致)——
        // 22.13 及以下 x7a 是不相干的 PopInfo 配置类, 反射会命中
        // 错误签名, 必须按版本取表。
        // 三元组第二元素 = 控制器上的 alpha 方法名(非门面方法名; 门面 j 由
        // 消费侧按"返回类型上存在该 alpha 方法"自动选出)
        val NAV_ALPHA_ROUTES: List<Triple<String, String, String>> = listOf(
            Triple("com.baidu.tieba.w8a", "f2", "22.15.0.0"),
            Triple("com.baidu.tieba.x7a", "f2", "22.14.0.0"),
            Triple("com.baidu.tieba.c7a", "X1", "22.13.0.0"),
            Triple("com.baidu.tieba.n6a", "e2", "22.12.0.0"),
            Triple("com.baidu.tieba.f5a", "c2", "22.11.0.0"),
        )
    }

    private class DeferredInitScroll(
        val originalScrollY: Int,
        val showBounceGuide: Boolean,
        val completion: Any?,
    )

    private class PendingNativeExpansion {
        var layoutReady = false
        var deferredInitScroll: DeferredInitScroll? = null
        var layoutListener: View.OnLayoutChangeListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
        var timeoutRunnable: Runnable? = null
    }

    private fun hookBottomSheetAutoExpand() {
        val sheetClass = try {
            findClass(SHEET_VIEW_CLASS)
        } catch (t: Throwable) {
            logBoth("FoldTopCardView: $SHEET_VIEW_CLASS not found: ${t.message}")
            return
        }
        if (!View::class.java.isAssignableFrom(sheetClass)) {
            logBoth("FoldTopCardView: $SHEET_VIEW_CLASS is not a View")
            return
        }
        val declaredMethods = sheetClass.declaredMethods
        val initScrollCandidates = declaredMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 3 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes[2].name == "kotlin.jvm.functions.Function0"
        }
        val smoothInitCandidates = declaredMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                    method.name == "getSmoothInit" &&
                    method.returnType == Int::class.javaPrimitiveType &&
                    method.parameterTypes.isEmpty()
        }
        val setupCandidates = declaredMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                    method.name == "setup" &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(
                        arrayOf(
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType
                        )
                    )
        }
        val maxScrollCandidates = collectInstanceMethods(sheetClass).filter { method ->
            method.name == "getMaxScroll" &&
                    method.returnType == Int::class.javaPrimitiveType &&
                    method.parameterTypes.isEmpty()
        }
        if (initScrollCandidates.size != 1 || smoothInitCandidates.size != 1 ||
            setupCandidates.size != 1 || maxScrollCandidates.size != 1
        ) {
            logBoth(
                "FoldTopCardView: TbBottomSheetView symbols mismatch " +
                        "(initScroll=${initScrollCandidates.size}, smoothInit=${smoothInitCandidates.size}, " +
                        "setup=${setupCandidates.size}, maxScroll=${maxScrollCandidates.size})"
            )
            return
        }
        val initScrollMethod = initScrollCandidates.single()
        val smoothInitGetter = smoothInitCandidates.single()
        val setupMethod = setupCandidates.single()
        val maxScrollGetter = maxScrollCandidates.single()
        initScrollMethod.isAccessible = true
        smoothInitGetter.isAccessible = true
        setupMethod.isAccessible = true
        maxScrollGetter.isAccessible = true
        val onScrollChangedMethod = View::class.java.getDeclaredMethod(
            "onScrollChanged",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }

        // setup/initScroll/布局回调均发生在主线程, WeakHashMap 即可
        val pendingTargets = WeakHashMap<View, PendingNativeExpansion>()
        val failureLogged = AtomicBoolean()

        fun logFailureOnce(message: String) {
            if (failureLogged.compareAndSet(false, true)) {
                XposedBridge.log("FoldTopCardView: $message")
            }
        }

        fun clearPendingCallbacks(view: View, pending: PendingNativeExpansion) {
            pending.layoutListener?.let { view.removeOnLayoutChangeListener(it) }
            pending.attachListener?.let { view.removeOnAttachStateChangeListener(it) }
            pending.timeoutRunnable?.let { view.removeCallbacks(it) }
            pending.layoutListener = null
            pending.attachListener = null
            pending.timeoutRunnable = null
        }

        fun finishPendingExpansion(view: View, pending: PendingNativeExpansion): Boolean {
            if (pendingTargets[view] !== pending) {
                clearPendingCallbacks(view, pending)
                return false
            }
            pendingTargets.remove(view)
            clearPendingCallbacks(view, pending)
            return true
        }

        // 挂起未完成 (超时/detach/重入) 时, 按宿主原始参数补放 initScroll
        fun fallbackPendingInit(view: View, pending: PendingNativeExpansion?) {
            pending ?: return
            val deferred = pending.deferredInitScroll
            if (!finishPendingExpansion(view, pending) || deferred == null) return
            try {
                initScrollMethod.invoke(
                    view, deferred.originalScrollY, deferred.showBounceGuide, deferred.completion
                )
            } catch (t: Throwable) {
                logFailureOnce("initScroll fallback failed: $t")
            }
        }

        // 布局就绪后把挂起的 initScroll 重放到 getMaxScroll() 终态, 使吧页面保持折叠
        fun replayDeferredInit(view: View, pending: PendingNativeExpansion) {
            if (!pending.layoutReady) return
            if (pendingTargets[view] !== pending) {
                clearPendingCallbacks(view, pending)
                return
            }
            val deferred = pending.deferredInitScroll ?: return
            val fullyExpandedTarget = try {
                maxScrollGetter.invoke(view) as? Int
            } catch (t: Throwable) {
                logFailureOnce("getMaxScroll failed: $t")
                null
            }
            if (fullyExpandedTarget == null) {
                fallbackPendingInit(view, pending)
                return
            }
            if (!finishPendingExpansion(view, pending)) return
            try {
                initScrollMethod.invoke(
                    view, fullyExpandedTarget, deferred.showBounceGuide, deferred.completion
                )
            } catch (t: Throwable) {
                logFailureOnce("initScroll replay failed: $t")
                return
            }
            // smooth-init 仍处于 web 高度初始化状态时每 32ms 复查,
            // 退出后补发 onScrollChanged 让宿主把 UI 状态同步到终态。
            // 注: 22.13 宿主 TbBottomSheetView 的 maxScroll 恒为初值 0(父类
            // TbNestedScrollView.w() 是唯一写入点, 但被本类 w() 覆写且未调
            // super, 亦无 setMaxScroll 调用)——重放目标恒定, 不存在"布局
            // 就绪早于 web 高度导致 maxScroll 偏小"的问题, 无需二次校准
            val deadline = SystemClock.uptimeMillis() + NATIVE_STATE_TIMEOUT_MS
            val terminalStateSync = object : Runnable {
                override fun run() {
                    if (!view.isAttachedToWindow) return
                    val smoothInit = try {
                        smoothInitGetter.invoke(view) as? Int
                    } catch (t: Throwable) {
                        logFailureOnce("getSmoothInit failed: $t")
                        return
                    }
                    if (smoothInit == NATIVE_WEB_HEIGHT_INIT_STATE) {
                        if (SystemClock.uptimeMillis() >= deadline) {
                            logFailureOnce("terminal state sync timeout")
                            return
                        }
                        view.postDelayed(this, TERMINAL_STATE_RECHECK_MS)
                        return
                    }
                    try {
                        onScrollChangedMethod.invoke(
                            view, view.scrollX, view.scrollY, view.scrollX, view.scrollY
                        )
                    } catch (t: Throwable) {
                        logFailureOnce("onScrollChanged sync failed: $t")
                    }
                }
            }
            view.postOnAnimation(terminalStateSync)
        }

        // 拦下首帧 initScroll, 待布局就绪后重放到终态; 挂起期间原调用一律吞掉
        hookBeforeMethod(initScrollMethod) { param ->
            val view = param.thisObject as? View ?: return@hookBeforeMethod
            val pending = pendingTargets[view] ?: return@hookBeforeMethod
            val originalScrollY = param.args.getOrNull(0) as? Int
            val showBounceGuide = param.args.getOrNull(1) as? Boolean
            if (originalScrollY == null || showBounceGuide == null) {
                finishPendingExpansion(view, pending)
                return@hookBeforeMethod
            }
            pending.deferredInitScroll = DeferredInitScroll(
                originalScrollY, showBounceGuide, param.args.getOrNull(2)
            )
            replayDeferredInit(view, pending)
            param.setResult(null)
        }

        // 宿主以折叠/半展开模式启动时强制为完全展开, 使 getMaxScroll() 覆盖整个置顶区
        hookBeforeMethod(setupMethod) { param ->
            val view = param.thisObject as? View ?: return@hookBeforeMethod
            val originalMode = param.args.getOrNull(0) as? Int
            if (originalMode != NATIVE_COLLAPSED_MODE &&
                originalMode != NATIVE_PARTIAL_EXPANDED_MODE
            ) {
                return@hookBeforeMethod
            }
            val smoothInit = try {
                smoothInitGetter.invoke(view) as? Int
            } catch (t: Throwable) {
                logFailureOnce("getSmoothInit failed: $t")
                null
            }
            if (smoothInit != NATIVE_WEB_HEIGHT_INIT_STATE) return@hookBeforeMethod

            param.args[0] = NATIVE_FULLY_EXPANDED_MODE
            // 同一 view 可能再次 setup, 先按原始参数补放上一轮挂起
            fallbackPendingInit(view, pendingTargets[view])
            val pending = PendingNativeExpansion()
            pendingTargets[view] = pending
            val layoutListener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    pending.layoutReady = true
                    replayDeferredInit(v, pending)
                }
            }
            val detachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}

                override fun onViewDetachedFromWindow(v: View) {
                    fallbackPendingInit(v, pending)
                }
            }
            val timeoutFallback = Runnable {
                fallbackPendingInit(view, pending)
            }
            pending.layoutListener = layoutListener
            pending.attachListener = detachListener
            pending.timeoutRunnable = timeoutFallback
            view.addOnLayoutChangeListener(layoutListener)
            view.addOnAttachStateChangeListener(detachListener)
            view.postDelayed(timeoutFallback, NATIVE_STATE_TIMEOUT_MS)
        }
    }

    // ===== 22.14+ 新实现: FE 通知时序不再保证 smoothInit==2, 旧方案失效 =====

    private fun hookBottomSheetDirectFold() {
        val sheetClass = try {
            findClass(SHEET_VIEW_CLASS)
        } catch (t: Throwable) {
            logBoth("FoldTopCardView: $SHEET_VIEW_CLASS not found: ${t.message}")
            return
        }
        if (!View::class.java.isAssignableFrom(sheetClass)) {
            logBoth("FoldTopCardView: $SHEET_VIEW_CLASS is not a View")
            return
        }
        val declaredMethods = sheetClass.declaredMethods
        // initScroll(int scrollY, boolean, Function0): NonFullWeb/FullWeb 两条布局策略的
        // initLayoutParams 均以它做进吧初始定位(22.11-22.14 混淆名 d0/c0/d0, 按签名匹配),
        // 是自动定位的唯一汇聚点; 用户手动展开走触摸回弹与 smoothScrollTo, 不经过它
        val initScrollCandidates = declaredMethods.filter { method ->
            !Modifier.isStatic(method.modifiers) &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.size == 3 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes[2].name == "kotlin.jvm.functions.Function0"
        }
        if (initScrollCandidates.size != 1) {
            logBoth(
                "FoldTopCardView: TbBottomSheetView symbols mismatch " +
                        "(initScroll=${initScrollCandidates.size})"
            )
            return
        }
        val initScrollMethod = initScrollCandidates.single()
        initScrollMethod.isAccessible = true

        // 导航栏联动入口只解析一次(宿主版本进程内不变), 失败记日志走降级:
        // 折叠本身不受影响, 仅标题栏可能停在透明态(头图从其下透出)
        val navRoute = NAV_ALPHA_ROUTES.firstOrNull { (_, _, minVer) ->
            runCatching { DeobfuscationHelper.isTbSatisfyVersionRequirement(minVer) }.getOrDefault(false)
        }
        var navFacadeClass: Class<*>? = null
        var navAlphaMethodName: String? = null
        if (navRoute != null) {
            try {
                val facade = findClass(navRoute.first)
                // 门面存在同名重载 j(22.14 x7a 有两个: 一个返回 NavigationBar-
                // Controller, 一个返回 BdUniqueId)。以"返回类型上存在目标 alpha
                // 方法"为判据选出正确的 j——比按参数/静态性筛选更可靠
                val alphaMethodRef = facade.declaredMethods
                    .filter { method ->
                        Modifier.isStatic(method.modifiers) &&
                                method.parameterTypes.size == 1 &&
                                android.app.Activity::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                                method.returnType != Void.TYPE
                    }
                    .map { jMethod ->
                        val ctrl = jMethod.returnType as Class<*>
                        val alpha = ctrl.declaredMethods.firstOrNull {
                            it.name == navRoute.second && it.parameterTypes.size == 1 && it.parameterTypes[0] == Float::class.javaPrimitiveType
                        }
                        if (alpha == null) null else Pair(jMethod, alpha)
                    }
                    .firstOrNull { it != null }
                    ?: throw NoSuchMethodException(
                        "no .j(Activity) returning controller with ${navRoute.second}(float) in ${navRoute.first}"
                    )
                val (jMethod, alpha) = alphaMethodRef
                jMethod.isAccessible = true
                alpha.isAccessible = true
                navFacadeClass = facade
                navAlphaMethodName = alpha.name
            } catch (t: Throwable) {
                logBoth("FoldTopCardView: nav alpha route ${navRoute.first}.${navRoute.second} unavailable: ${t.message}")
                navFacadeClass = null
                navAlphaMethodName = null
            }
        }
        val facadeClass = navFacadeClass
        val alphaMethod = navAlphaMethodName

        // 2026-11-13 模拟器取证定案(22.14.1.0):
        // sheet 滚动域 [minScroll(负), 0], 0 是最收起端——头图完全藏进标题栏
        // 下、面板顶到导航栏, 与"先下拉再上划"手势停靠后的形态一致(手势轨迹
        // 实测 -669→回弹 0, 终态即图2)。进吧原生目标 -260/-539(随头图高度变)
        // 只是半收起。折叠到 0 有两件事必须一起做:
        // ① 改参 initScroll 目标为 0;
        // ② 补发导航栏联动 alpha=1——手势滚动会经 K()→J()→导航栏联动方法把
        //    标题栏渐变到不透明, 而改参直落 0 时 onScrollChanged 无变化(0→0),
        //    联动不触发, 标题栏停在透明态、头图从其底下透出。
        // 两步合起来才等价于手势停靠形态。导航栏补发立即生效(下一帧), 避免
        // 进吧后标题栏先透明再变蓝的可见闪烁; 再留一发 500ms 保险补发(幂等,
        // 只是设透明度)——真机时序无法在模拟器完全验证, H5 高度事件若晚于
        // 首发把导航栏写回初值, 由它兜住。全程 runCatching, 失败即放弃
        // (降级为折叠但标题栏透明)。
        hookBeforeMethod(initScrollMethod) { param ->
            val view = param.thisObject as? View ?: return@hookBeforeMethod
            if (param.args.getOrNull(0) !is Int) return@hookBeforeMethod
            param.args[0] = 0
            if (facadeClass == null || alphaMethod == null) return@hookBeforeMethod
            val applyNavAlpha = Runnable {
                runCatching {
                    val act = view.context as? android.app.Activity ?: return@runCatching
                    val navCtl = XposedHelpers.callStaticMethod(facadeClass, "j", act)
                    XposedHelpers.callMethod(navCtl, alphaMethod, 1.0f)
                }
            }
            view.post(applyNavAlpha)
            view.postDelayed(applyNavAlpha, 500L)
        }
    }

    private fun collectInstanceMethods(clazz: Class<*>): List<Method> {
        val out = ArrayList<Method>(24)
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (!Modifier.isStatic(method.modifiers)) {
                    out.add(method)
                }
            }
            current = current.superclass
        }
        return out
    }
}
