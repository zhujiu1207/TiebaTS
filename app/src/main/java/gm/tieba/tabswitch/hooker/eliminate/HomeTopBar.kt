package gm.tieba.tabswitch.hooker.eliminate

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.MAIN_TAB_ACTIVITY
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

// 22.11 顶栏右Slot内 搜索放大镜/发帖+/游戏手柄 的共享状态:
// 放大镜视图由 ScrollTabBarController 回调捕获, 隐藏"+"与手柄时必须跳过它
object HomeTopBarState {
    @Volatile var searchIconRef: WeakReference<View>? = null
    @Volatile var searchIconCaptured = false

    // 防重复注册的 view tag key(用大数避免与宿主 tag 冲突)。
    // 高位不能落在 0x7f 段: 那是宿主应用资源 id 空间, 撞车时宿主对同一视图
    // getTag(该id) 会读到模块写入的 Boolean 引发类型混淆 CCE; setTag 只要求 key>>>24 >= 2
    val HOME_TOP_BAR_GUARD_KEY = 0x5B5B4A17

    fun registerSearchIconCapture() {
        // 注册成功后才置位: 先置位时定位失败(bind 为 null/类缺失)会永久拒绝重试,
        // 放大镜捕获只剩 getter 兜底路径
        if (searchIconCaptured) return
        try {
            val controllerClass = XposedHelpers.findClass(
                "com.baidu.tieba.homepage.controller.ScrollTabBarController",
                XposedContext.sClassLoader
            )
            val bind = controllerClass.declaredMethods.firstOrNull { method ->
                method.parameterTypes.size == 2 &&
                    method.parameterTypes[0].name.endsWith("TbImage") &&
                    method.parameterTypes[1] == ImageView::class.java
            } ?: return
            XposedContext.hookBeforeMethod(bind) { param ->
                (param.args.getOrNull(1) as? View)?.let { v ->
                    searchIconRef = WeakReference(v)
                }
            }
            searchIconCaptured = true
        } catch (ignored: Throwable) {
        }
    }
}

class HomeTopBar : XposedContext(), IHooker {

    override fun key(): String {
        return "home_top_bar"
    }

    // 开关状态在 hook 时读取一次固化: 设置变更重启后才生效, 与 hook 生命周期一致,
    // 避免高频回调(onLayout/GlobalLayout)中反复读 SharedPreferences
    private val mHideSidebar = getBoolean("hide_sidebar")
    private val mHideGamepad = getBoolean("hide_gamepad")
    // 直播Tab视觉层兜底: 旧版数据层(HomeTopTab)解析失败时是唯一生效路径。
    // 分项开关键 hide_live 已迁移进"隐藏顶部Tab"合并开关, 选中"直播"同样触发;
    // 保留 getBoolean 兼容迁移未执行的异常态
    private val mHideLive = getBoolean("hide_live") || HomeTopTab.isLiveNameSelected()
    private val mHideWriteButton = getBoolean("write_thread")

    // onLayout 每帧触发: 缓存定位结果(弱引用, 不阻碍宿主视图回收),
    // 视图被宿主重建后弱引用失效, 自动重新查找
    private val mSlotCache = java.util.WeakHashMap<View, Pair<WeakReference<View>, WeakReference<View>>>()
    private val mStripCache = java.util.WeakHashMap<View, WeakReference<View>>()
    private val mDrawerCache = java.util.WeakHashMap<View, WeakReference<View>>()
    // 已定位的直播Tab(弱引用): 隐藏后每帧只需确认状态, 不再递归比对文本
    private var mLiveTabRef: WeakReference<View>? = null

    override fun hook() {
        // 捕获搜索放大镜视图引用, 防止被"隐藏发帖/隐藏游戏手柄"误隐藏
        HomeTopBarState.registerSearchIconCapture()

        // 首页顶部三条杠与游戏手柄
        // 12.74: FixedAppBarLayout$n 为顶栏控件持有者: m = 顶栏容器, b = Tab栏, h = 三条杠, o = 游戏手柄(含红点)
        // 22.11+: 首页顶栏改为 ScrollTabBarLayout/TripleSlotFlexLayout 三段式结构
        //   (HomeTabBarLeftSlot = 三条杠, NewHomeTabBarLayout = Tab栏, HomeTabBarRightSlot = 游戏手柄+发帖+)
        // 部分云控/事件流程会在初始化后重新显示它们, 因此在布局变化时持续保持隐藏
        try {
            hookAfterMethod(
                "com.baidu.tieba.homepage.framework.indicator.FixedAppBarLayout",
                "onAttachedToWindow"
            ) { param ->
                val layout = param.thisObject as View
                // onAttachedToWindow 随 Fragment 重挂载反复触发: 打标记防重,
                // 避免同一视图上累积多个 GlobalLayoutListener
                if (layout.getTag(HomeTopBarState.HOME_TOP_BAR_GUARD_KEY) == null) {
                    layout.setTag(HomeTopBarState.HOME_TOP_BAR_GUARD_KEY, true)
                    layout.post { hideSideButtons(layout) }
                }
            }
        } catch (ignored: Throwable) {
            // 宽粒度与本文件其余注册块及 Purge.hookSafely 范式对齐: 只捕
            // ClassNotFoundError 拦不住签名漂移的 NoSuchMethodError, 会中断
            // hook() 其后全部注册点
        }

        // 22.11+: TripleSlotFlexLayout 每次 onLayout 都会重排三段Slot, 在其后持续保持隐藏
        try {
            hookAfterMethod(
                "com.baidu.tieba.homepage.view.TripleSlotFlexLayout",
                "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ) { param ->
                val layout = param.thisObject as View
                enforceSlotsHiddenIn(layout)
                if (mHideLive) {
                    hideLiveTabInStrip(layout)
                }
                if (mHideSidebar) {
                    lockHomeDrawer(layout)
                }
            }
        } catch (ignored: Throwable) {
        }

        if (mHideSidebar) {
            // 22.11+: 首页侧边抽屉(直播/游戏面板)由 DrawerFullDragLayout 全屏拖拽实现,
            // 不走 DrawerLayout 锁模式, 直接禁用其触摸处理使手势无法拉出
            try {
                hookReplaceMethod(
                    "com.baidu.tieba.tblauncher.DrawerFullDragLayout",
                    "onInterceptTouchEvent",
                    android.view.MotionEvent::class.java
                ) { false }
                hookReplaceMethod(
                    "com.baidu.tieba.tblauncher.DrawerFullDragLayout",
                    "onTouchEvent",
                    android.view.MotionEvent::class.java
                ) { false }
            } catch (ignored: Throwable) {
            }

            // 22.11: 顶栏左缘/屏幕左缘右滑会拉起透明助手活动承载侧边菜单(三条杠菜单),
            // 其抽屉在该活动自己的窗口内, 锁首页 DrawerLayout 无效; 精简顶栏后直接终止它
            try {
                hookBeforeMethod(
                    "com.baidu.tieba.sidebar.SideBarAssistActivity",
                    "onCreate", Bundle::class.java
                ) { param ->
                    (param.thisObject as? Activity)?.finish()
                }
            } catch (ignored: Throwable) {
            }

            // 22.11: 侧边栏真正的视图是主页窗口内的 HomeSideBarView(H5), 透明助手活动只是遮罩;
            // 手势拉出的画面是抽屉本体, 仅杀助手活动会出现"拉出又收回"。
            // 以下多层加固均不依赖会随宿主版本改名的混淆类:
            // 1) 事件链: 按钮点击/H5 schema 经 HomeSideBarDispatcher 分发, 直接置空
            try {
                hookReplaceMethod(
                    "com.baidu.tieba.sidebar.HomeSideBarDispatcher",
                    "dispatch",
                    JSONObject::class.java,
                    Context::class.java
                ) { null }
            } catch (ignored: Throwable) {
            }

            // 2) 抽屉显示回调: onShow 内经 schema 路由拉起透明遮罩活动, 置空
            try {
                hookReplaceMethod(
                    "com.baidu.tieba.sidebar.HomeSideBarView",
                    "onShow"
                ) { null }
            } catch (ignored: Throwable) {
            }

            // 3) 兜底锁: MainTabActivity 是首页宿主(类名跨版本稳定), 在其窗口内
            //    持续锁死所有 DrawerLayout(类名同样稳定), 不依赖顶栏混淆类是否被 hook 到
            try {
                hookAfterMethod(
                    MAIN_TAB_ACTIVITY,
                    "onCreate", Bundle::class.java
                ) { param ->
                    val activity = param.thisObject as? Activity ?: return@hookAfterMethod
                    val decor = activity.window?.decorView ?: return@hookAfterMethod
                    // 全局布局事件高频触发: 缓存已定位的 DrawerLayout(弱引用),
                    // 失效(视图重建)时才重新收集, 避免每次布局事件全树遍历
                    val drawerRefs = ArrayList<WeakReference<View>>()
                    // 连续收集为空的退避: 22.11+ 首页窗口通常不含 DrawerLayout(侧边栏
                    // 改由 DrawerFullDragLayout 承载), 此时每次布局事件都全树遍历整棵
                    // decor 纯属浪费; 连续为空后降为限时重扫, 后加入的抽屉由 onResume
                    // 的全树收集兜底
                    var emptyScans = 0
                    var lastScanAt = 0L
                    fun lockAllCached() {
                        if (drawerRefs.isNotEmpty()) {
                            var anyAlive = false
                            var anyCleared = false
                            for (ref in drawerRefs) {
                                val drawer = ref.get()
                                if (drawer != null) {
                                    anyAlive = true
                                    lockDrawer(drawer)
                                } else {
                                    anyCleared = true
                                }
                            }
                            if (anyAlive && !anyCleared) return
                            // 存在失效引用(视图重建): 继续走重扫
                        }
                        if (emptyScans >= DRAWER_EMPTY_SCAN_BACKOFF &&
                            SystemClock.uptimeMillis() - lastScanAt < DRAWER_RESCAN_INTERVAL_MS
                        ) {
                            return
                        }
                        lastScanAt = SystemClock.uptimeMillis()
                        drawerRefs.clear()
                        collectDrawersInSubtree(decor, drawerRefs)
                        if (drawerRefs.isEmpty()) {
                            emptyScans++
                        } else {
                            emptyScans = 0
                            drawerRefs.forEach { it.get()?.let { drawer -> lockDrawer(drawer) } }
                        }
                    }
                    lockAllCached()
                    // 布局变化/宿主重置锁模式时持续保持锁死
                    decor.viewTreeObserver.addOnGlobalLayoutListener {
                        lockAllCached()
                    }
                }
                hookAfterMethod(
                    MAIN_TAB_ACTIVITY,
                    "onResume"
                ) { param ->
                    val decor = (param.thisObject as? Activity)?.window?.decorView
                        ?: return@hookAfterMethod
                    // onResume 低频, 直接全树收集一次, 兼顾捕获后加入的新 DrawerLayout
                    lockDrawersInSubtree(decor)
                }
            } catch (ignored: Throwable) {
            }

            // 4) 终极兜底: 在 Instrumentation.execStartActivity 拦截对侧边栏助手活动的启动
            //    (所有 Activity.startActivity 路径的必经点), 启动被吞掉且无异常
            try {
                XposedBridge.hookAllMethods(
                    Instrumentation::class.java,
                    "execStartActivity",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            // 全 app 每次 Activity 启动都触发: 按下标取第一个 Intent, 不做列表分配
                            var intent: Intent? = null
                            for (arg in param.args) {
                                if (arg is Intent) {
                                    intent = arg
                                    break
                                }
                            }
                            if (intent?.component?.className?.endsWith(SIDE_BAR_ASSIST_ACTIVITY) == true) {
                                param.setResult(null)
                            }
                        }
                    }
                )
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun hideSideButtons(layout: View) {
        try {
            val holder = XposedHelpers.getObjectField(layout, "a") ?: return
            var drawerLocked = false
            val hideSidebar = mHideSidebar
            val hideLive = mHideLive

            // 22.11+: 三段式Slot结构, 按类名定位(定位实现收编 RightSlotLocator)
            val leftSlot = RightSlotLocator.findDescendantByClass(layout, RightSlotLocator.LEFT_SLOT)
            val rightSlot = RightSlotLocator.findDescendantByClass(layout, RightSlotLocator.RIGHT_SLOT)
            if (leftSlot != null && rightSlot != null) {
                enforceSlotsHidden(leftSlot, rightSlot)
                if (hideSidebar) {
                    drawerLocked = lockHomeDrawer(layout)
                }
                if (hideLive) {
                    hideLiveTabInStrip(layout)
                }

                // 布局变化时保持隐藏(仅在实际可见时设置GONE, 不会造成循环布局)
                layout.viewTreeObserver.addOnGlobalLayoutListener {
                    enforceSlotsHidden(leftSlot, rightSlot)
                    if (hideSidebar && !drawerLocked) {
                        drawerLocked = lockHomeDrawer(layout)
                    }
                    if (hideLive) {
                        hideLiveTabInStrip(layout)
                    }
                }
                return
            }

            // 12.74: 旧版字段名定位
            val container = XposedHelpers.getObjectField(holder, "m") as? View ?: return
            enforceHidden(holder)
            if (hideSidebar) {
                drawerLocked = lockHomeDrawer(layout)
            }

            // 布局变化时保持隐藏(仅在实际可见时设置GONE, 不会造成循环布局)
            container.viewTreeObserver.addOnGlobalLayoutListener {
                enforceHidden(holder)
                if (hideSidebar && !drawerLocked) {
                    drawerLocked = lockHomeDrawer(layout)
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    // 22.11+: HomeTabBarRightSlot 内放大镜字段即为 f/getSearchIconView(), 按类名匹配兜不住
    // (放大镜与发帖+同为 android.widget.ImageView), 直接调 getter 取引用。
    // 解析缓存收编 RightSlotLocator(与 FragmentTab 共用单份实现)
    private fun rightSlotSearchIcon(rightSlot: View): View? =
        RightSlotLocator.rightSlotSearchIcon(rightSlot)

    // 22.11+: 在 TripleSlotFlexLayout 子树中定位三段Slot并保持隐藏
    private fun enforceSlotsHiddenIn(layout: View) {
        try {
            val slots = resolveSlots(layout) ?: return
            enforceSlotsHidden(slots.first, slots.second)
        } catch (ignored: Throwable) {
        }
    }

    // onLayout 每帧触发: 定位结果缓存为弱引用, 视图重建后自动重新查找。
    // 命中缓存需校验挂载状态(对齐 FragmentTab 的做法): 同一 layout 实例内 Slot
    // 被宿主重建时, 旧 Slot 已脱离窗口, 继续使用会把隐藏作用在旧视图上而漏掉新 Slot
    private fun resolveSlots(layout: View): Pair<View, View>? {
        mSlotCache[layout]?.let { cached ->
            val left = cached.first.get()
            val right = cached.second.get()
            if (left != null && right != null &&
                left.isAttachedToWindow && right.isAttachedToWindow
            ) return left to right
            mSlotCache.remove(layout)
        }
        val left = RightSlotLocator.findDescendantByClass(layout, RightSlotLocator.LEFT_SLOT) ?: return null
        val right = RightSlotLocator.findDescendantByClass(layout, RightSlotLocator.RIGHT_SLOT) ?: return null
        mSlotCache[layout] = WeakReference(left) to WeakReference(right)
        return left to right
    }

    // 22.11+: 左Slot隐藏三条杠(hide_sidebar), 右Slot隐藏游戏手柄/红点(hide_gamepad);
    // 发帖"+"归隐藏发帖开关管
    private fun enforceSlotsHidden(leftSlot: View, rightSlot: View) {
        if (!mHideSidebar && !mHideGamepad) {
            return
        }
        // 优先用 HomeTabBarRightSlot.getSearchIconView() 精确定位放大镜, 回调捕获兜底
        val searchIcon = rightSlotSearchIcon(rightSlot) ?: HomeTopBarState.searchIconRef?.get()
        if (mHideSidebar && leftSlot is ViewGroup) {
            for (i in 0 until leftSlot.childCount) {
                val child = leftSlot.getChildAt(i)
                if (child is ImageView && child !== searchIcon && child.visibility != View.GONE) {
                    child.visibility = View.GONE
                }
            }
        }
        if (rightSlot is ViewGroup) {
            for (i in 0 until rightSlot.childCount) {
                val child = rightSlot.getChildAt(i)
                if (child === searchIcon) continue
                val name = child.javaClass.name
                val hide = when {
                    // 游戏手柄图标 (TbImage) 及其红点
                    name.startsWith(TB_IMAGE) -> mHideGamepad
                    name == "android.view.View" -> mHideGamepad
                    // 右上角蓝色发帖"+"按钮 (普通ImageView)
                    name == "android.widget.ImageView" && mHideWriteButton -> true
                    else -> false
                }
                if (hide && child.visibility != View.GONE) {
                    child.visibility = View.GONE
                }
            }
        }
    }

    // 22.11+: 定位 SlidingTabStrip(缓存), 隐藏标题为"直播"的单个Tab
    private fun hideLiveTabInStrip(layout: View) {
        var strip = mStripCache[layout]?.get()
        if (strip == null) {
            strip = RightSlotLocator.findDescendantByClass(layout, RightSlotLocator.SLIDING_TAB_STRIP) ?: return
            mStripCache[layout] = WeakReference(strip)
        }
        if (strip.visibility == View.GONE || strip !is ViewGroup) {
            return
        }
        // 命中过的直播Tab缓存: onLayout 每帧触发, 命中一次后每帧只确认
        // "仍是本 strip 的子视图且为 GONE", 不再对整条 strip 递归比对文本;
        // 缓存视图脱离 strip(宿主重建 Tab 栏)时失效重扫
        mLiveTabRef?.get()?.let { cached ->
            if (cached.parent === strip) {
                if (cached.visibility != View.GONE) {
                    cached.visibility = View.GONE
                }
                return
            }
            mLiveTabRef = null
        }
        for (i in 0 until strip.childCount) {
            val tab = strip.getChildAt(i)
            if (tab is ViewGroup && tab.visibility != View.GONE && containsLiveTabTitle(tab)) {
                tab.visibility = View.GONE
                mLiveTabRef = WeakReference(tab)
            }
        }
    }

    // 整树按类名查找收编 RightSlotLocator(与 FragmentTab 共用单份实现)

    // 锁死首页侧边抽屉(DrawerLayout), 三条杠按钮隐藏后仍可从边缘划出
    // 优先沿父链查找; 22.11 顶栏可能不在抽屉子树内, 故再从根视图整树查找;
    // 定位结果缓存为弱引用(onLayout 高频触发), 视图重建后自动重新查找。
    // onLayout 每帧触发: 22.11+ 首页窗口通常无 DrawerLayout(侧栏改由
    // DrawerFullDragLayout 承载, 走 touch 事件层禁用), 此时"沿父链+整树"两次
    // 全树遍历每帧都付。连续找不到时降为限时重扫(onCreate 路径 emptyScans
    // 同口径), 后加入的抽屉由限时重扫兜底
    private var mDrawerScanMisses = 0
    private var mDrawerLastScanAt = 0L

    private fun lockHomeDrawer(layout: View): Boolean {
        return try {
            mDrawerCache[layout]?.get()?.let { cached ->
                if (lockDrawer(cached)) return true
            }
            // 退避窗口内不做全树重扫: 锁定动作仍由缓存命中路径承担,
            // 窗口期(2s)内新加的抽屉最多晚 2s 被锁上, 可接受
            val now = android.os.SystemClock.uptimeMillis()
            if (mDrawerScanMisses >= 3 && now - mDrawerLastScanAt < 2000L) {
                return false
            }
            mDrawerLastScanAt = now
            if (lockDrawerInSubtree(layout)) {
                mDrawerScanMisses = 0
                return true
            }
            val root = layout.rootView ?: return false
            val drawer = findDrawerInSubtree(root)
            if (drawer != null) {
                mDrawerCache[layout] = WeakReference(drawer)
                mDrawerScanMisses = 0
                lockDrawer(drawer)
            } else {
                mDrawerScanMisses++
                false
            }
        } catch (ignored: Throwable) {
            false
        }
    }

    private fun findDrawerInSubtree(view: View): View? {
        if (view.javaClass.name == DRAWER_LAYOUT) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findDrawerInSubtree(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun lockDrawerInSubtree(view: View): Boolean {
        val drawer = findDrawerInSubtree(view) ?: return false
        return lockDrawer(drawer)
    }

    // 收集子树内所有 DrawerLayout(首页窗口兜底用), 供缓存复用
    private fun collectDrawersInSubtree(view: View?, out: MutableList<WeakReference<View>>) {
        if (view == null) {
            return
        }
        if (view.javaClass.name == DRAWER_LAYOUT) {
            out.add(WeakReference(view))
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectDrawersInSubtree(view.getChildAt(i), out)
            }
        }
    }

    // 锁死子树内所有 DrawerLayout(首页窗口兜底用), 返回锁定的个数
    private fun lockDrawersInSubtree(view: View?): Int {
        if (view == null) {
            return 0
        }
        var count = 0
        if (view.javaClass.name == DRAWER_LAYOUT) {
            if (lockDrawer(view)) {
                count++
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                count += lockDrawersInSubtree(view.getChildAt(i))
            }
        }
        return count
    }

    private fun lockDrawer(view: View): Boolean {
        val lock = drawerLockReflectedFor(view)
        // 已处于锁定状态时早退: 该方法在每帧 onLayout/全局布局回调上重放,
        // 反射 invoke + 字段写即使早已锁定也照付开销; 先读回当前态,
        // 锁模式与边缘感应宽度均已就位则本帧不再写。读不到状态(字段缺失)
        // 时按未锁处理, 落回原有写入路径
        val alreadyLocked = try {
            lock.lockModeLeft?.getInt(view) == DRAWER_LOCKED_CLOSED
        } catch (ignored: Throwable) {
            false
        }
        val edgeAlreadyZero = try {
            val dragger = lock.leftDragger?.get(view)
            dragger == null || lock.edgeSize?.getInt(dragger) == 0
        } catch (ignored: Throwable) {
            false
        }
        if (alreadyLocked && edgeAlreadyZero) {
            return true
        }
        var locked = false
        lock.setLockMode?.let { setLockMode ->
            try {
                setLockMode.invoke(view, DRAWER_LOCKED_CLOSED)
                locked = true
            } catch (ignored: Throwable) {
            }
        }
        if (!locked) {
            // 方法不可用时直接写字段(LOCK_MODE_LOCKED_CLOSED)
            try {
                lock.lockModeLeft?.setInt(view, DRAWER_LOCKED_CLOSED)
                lock.lockModeRight?.setInt(view, DRAWER_LOCKED_CLOSED)
                locked = lock.lockModeLeft != null || lock.lockModeRight != null
            } catch (ignored: Throwable) {
            }
        }
        // 锁模式会被宿主在切页/恢复时重置, 改为把边缘拖拽感应宽度清零:
        // ViewDragHelper 不再触发 onEdgeDragStarted, 手势永久失效且程序化 openDrawer 不受影响
        try {
            val edgeSize = lock.edgeSize
            if (edgeSize != null) {
                lock.leftDragger?.get(view)?.let { dragger -> edgeSize.setInt(dragger, 0) }
                lock.rightDragger?.get(view)?.let { dragger -> edgeSize.setInt(dragger, 0) }
            }
        } catch (ignored: Throwable) {
        }
        return locked
    }

    // DrawerLayout 锁定接口的反射解析缓存: XposedHelpers.callMethod/setIntField
    // 每次调用都遍历类层次查找方法/字段, 全局布局回调高频触发时逐次解析开销可观,
    // 首次锁定时解析一次后复用(所有 DrawerLayout 子类继承同一组框架字段)
    private class DrawerLockReflected {
        var setLockMode: Method? = null
        var lockModeLeft: Field? = null
        var lockModeRight: Field? = null
        var leftDragger: Field? = null
        var rightDragger: Field? = null
        var edgeSize: Field? = null
    }

    @Volatile
    private var sDrawerLock: DrawerLockReflected? = null

    private fun drawerLockReflectedFor(view: View): DrawerLockReflected {
        sDrawerLock?.let { return it }
        val resolved = DrawerLockReflected()
        try {
            resolved.setLockMode = view.javaClass.getMethod(
                "setDrawerLockMode", Int::class.javaPrimitiveType
            )
        } catch (ignored: Throwable) {
        }
        val drawerClass = view.javaClass
        resolved.lockModeLeft = findFieldUpward(drawerClass, "mLockModeLeft")
        resolved.lockModeRight = findFieldUpward(drawerClass, "mLockModeRight")
        resolved.leftDragger = findFieldUpward(drawerClass, "mLeftDragger")
        resolved.rightDragger = findFieldUpward(drawerClass, "mRightDragger")
        // 两个 dragger 同为 ViewDragHelper, mEdgeSize 从其实例类解析一次
        try {
            resolved.leftDragger?.get(view)?.let { dragger ->
                resolved.edgeSize = findFieldUpward(dragger.javaClass, "mEdgeSize")
            }
        } catch (ignored: Throwable) {
        }
        sDrawerLock = resolved
        return resolved
    }

    private fun findFieldUpward(clazz: Class<*>, name: String): Field? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (ignored: NoSuchFieldException) {
            }
            c = c.superclass
        }
        return null
    }

    private fun enforceHidden(holder: Any) {
        // 12.74: h = 三条杠(侧边栏入口), o = 游戏手柄, 按各自开关隐藏
        val fields = ArrayList<String>(2)
        if (mHideSidebar) {
            fields.add("h")
        }
        if (mHideGamepad) {
            fields.add("o")
        }
        for (fieldName in fields) {
            val view = XposedHelpers.getObjectField(holder, fieldName) as? View ?: continue
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
        }
        if (mHideLive) {
            hideLiveTab(holder)
        }
    }

    // 视图层兜底: 隐藏Tab栏中标题为"直播"的单个Tab或直播动画Tab (12.74)
    private fun hideLiveTab(holder: Any) {
        try {
            val tabBar = XposedHelpers.getObjectField(holder, "b") ?: return
            val strip = XposedHelpers.getObjectField(tabBar, "a") as? ViewGroup ?: return
            hideLiveTabInView(strip)
        } catch (ignored: Throwable) {
        }
    }

    private fun hideLiveTabInView(view: View) {
        if (view.visibility == View.GONE || view !is ViewGroup) {
            return
        }
        if (view.javaClass.name == LIVE_TAB_CONTAINER) {
            if (LIVE_ANIM_TAB_VIEW == findChildClassName(view) || containsLiveTabTitle(view)) {
                view.visibility = View.GONE
            }
            return
        }
        for (i in 0 until view.childCount) {
            hideLiveTabInView(view.getChildAt(i))
        }
    }

    private fun findChildClassName(view: ViewGroup): String {
        for (i in 0 until view.childCount) {
            return view.getChildAt(i).javaClass.name
        }
        return ""
    }

    private fun containsLiveTabTitle(tab: ViewGroup): Boolean {
        for (i in 0 until tab.childCount) {
            val child = tab.getChildAt(i)
            // contentEquals(CharSequence 内容比较)替代 text.toString():
            // 每帧每个文本节点省一次字符串分配
            if (child is TextView && child.text.contentEquals(LIVE_TAB_TITLE)) {
                return true
            }
            if (child is ViewGroup && containsLiveTabTitle(child)) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val DRAWER_LAYOUT = "androidx.drawerlayout.widget.DrawerLayout"
        // DrawerLayout.LOCK_MODE_LOCKED_CLOSED, 锁死后手势无法拉开, 程序化openDrawer不受影响
        private const val DRAWER_LOCKED_CLOSED = 1
        // 首页窗口连续 N 次收集不到 DrawerLayout 后, 布局事件中的全树重扫降为限时重扫
        private const val DRAWER_EMPTY_SCAN_BACKOFF = 3
        private const val DRAWER_RESCAN_INTERVAL_MS = 10_000L
        // 22.11 侧边栏透明遮罩活动, 类名后缀跨小版本稳定
        private const val SIDE_BAR_ASSIST_ACTIVITY = "SideBarAssistActivity"
        // MAIN_TAB_ACTIVITY 收编至 hooker/HostAnchors.kt（五个子系统共用）
        private const val LIVE_TAB_CONTAINER = "com.baidu.tieba.homepage.framework.indicator.HomeTabViewContainer"
        private const val LIVE_ANIM_TAB_VIEW = "com.baidu.tieba.homepage.personalize.view.LiveAnimTabView"
        private const val LIVE_TAB_TITLE = "直播"
        private const val TB_IMAGE = "com.baidu.tbadk.widget.image.TbImage"
    }
}
