package gm.tieba.tabswitch.hooker.eliminate

import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

// FragmentTab 与 HomeTopBar 对同一批首页顶栏视图的平行定位实现收编——此前
// 互相"对齐"却未合并, 版本漂移时存在"两处修一处漏"的隐患:
// - HomeTabBarRightSlot 定位(WeakHashMap 弱引用缓存 + 挂载校验 + 空扫退避)
// - Slot/SlidingTabStrip 的整树按类名查找
// - HomeTabBarRightSlot.getSearchIconView() 的按类解析缓存(放大镜与发帖"+"
//   同为 android.widget.ImageView, 仅类名匹配会把放大镜误隐藏且无法恢复)
// 访问点全部在主线程(TripleSlotFlexLayout.onLayout / GlobalLayoutListener),
// HashMap 允许 null 负缓存, 无并发问题。
internal object RightSlotLocator {

    // 22.11+ 三段式顶栏
    const val LEFT_SLOT = "com.baidu.tieba.homepage.personalize.view.HomeTabBarLeftSlot"
    const val RIGHT_SLOT = "com.baidu.tieba.homepage.personalize.view.HomeTabBarRightSlot"
    const val SLIDING_TAB_STRIP = "com.baidu.tieba.homepage.framework.indicator.SlidingTabStrip"

    // getSearchIconView 按类解析一次(含 null 负缓存: 负缓存失守会让版本漂移后
    // 每帧重扫类层次并构造 NoSuchMethodException), 沿父类链查找
    private val sSearchIconGetterCache = HashMap<Class<*>, Method?>()

    fun searchIconGetterOf(clazz: Class<*>): Method? {
        if (sSearchIconGetterCache.containsKey(clazz)) {
            return sSearchIconGetterCache[clazz]
        }
        var resolved: Method? = null
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            val target: Class<*> = c
            val m = runCatching { target.getDeclaredMethod("getSearchIconView") }.getOrNull()
            if (m != null) {
                resolved = m
                break
            }
            c = c.superclass
        }
        sSearchIconGetterCache[clazz] = resolved
        return resolved
    }

    fun rightSlotSearchIcon(rightSlot: View): View? {
        val getter = searchIconGetterOf(rightSlot.javaClass) ?: return null
        return try {
            getter.invoke(rightSlot) as? View
        } catch (ignored: Throwable) {
            null
        }
    }

    fun findDescendantByClass(view: View, className: String): View? {
        if (view.javaClass.name == className) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findDescendantByClass(view.getChildAt(i), className)?.let { return it }
            }
        }
        return null
    }

    // RightSlot 定位: 定位结果按范式缓存为弱引用(视图重建后弱引用失活自动重查),
    // 命中缓存需校验挂载状态——同一 layout 实例内 Slot 被宿主重建时, 旧 Slot 已
    // 脱离窗口, 继续维护它会漏掉新 Slot 的内容
    private val mRightSlotCache = WeakHashMap<View, WeakReference<ViewGroup>>()
    // 空扫退避(与 HomeTopBar 抽屉扫描同口径): 宿主形态异常(无 RightSlot)时
    // onLayout 逐帧全树重扫, 连续 3 次落空后 2s 内不再重扫; 后加入的 Slot 由
    // 下一重扫窗口兜底
    private var mMisses = 0
    private var mLastScanAt = 0L

    fun rightSlotOf(layout: View): ViewGroup? {
        mRightSlotCache[layout]?.get()?.let { cached ->
            if (cached.isAttachedToWindow) return cached
            mRightSlotCache.remove(layout)
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (mMisses >= 3 && now - mLastScanAt < 2000L) return null
        mLastScanAt = now
        val slot = findDescendantByClass(layout, RIGHT_SLOT) as? ViewGroup
        if (slot != null) {
            mMisses = 0
            mRightSlotCache[layout] = WeakReference(slot)
        } else {
            mMisses++
        }
        return slot
    }
}
