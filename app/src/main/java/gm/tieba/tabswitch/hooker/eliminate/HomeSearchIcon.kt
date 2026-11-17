package gm.tieba.tabswitch.hooker.eliminate

import android.view.View
import android.view.ViewGroup
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.logBoth

class HomeSearchIcon : XposedContext(), IHooker {

    override fun key(): String {
        return "home_search_icon"
    }

    override fun hook() {
        // 22.11+: 首页顶栏改为 ScrollTabBarLayout 三段式结构, 右上角放大镜是
        // HomeTabBarRightSlot 内的 searchIconView(f), 显隐由 z() 统一计算:
        // 推荐Tab(itemType==1)时 alpha=列表滚动进度, 停在顶部即隐藏; 有料等Tab恒显示。
        // 需求为推荐页也常驻放大镜: y/z 执行后强制可见可点, 覆盖切Tab/滚动所有路径。
        // y/z 的参数个数随版本漂移(22.11: y() 无参; 22.12: y(int,int), 显隐计算
        // 从 y 移入 z()), 必须用 hookAllMethods 按名挂——按参数类型精确查找会在
        // 22.12 上 NoSuchMethodError 并中断整个 try 块(含构造兜底), 放大镜随之消失
        try {
            val slotClass = findClass("com.baidu.tieba.homepage.personalize.view.HomeTabBarRightSlot")
            val forceHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    forceSearchIconVisible(param.thisObject as View)
                }
            }
            XposedBridge.hookAllMethods(slotClass, "y", forceHook)
            XposedBridge.hookAllMethods(slotClass, "z", forceHook)
            // 冷启动直接落在推荐页顶部时 y/z 可能不会被调用, 构造完成后兜底强制一次。
            // 第 2 参运行时类名为嵌套接口 \$a: 22.11-22.14 构造器签名一致(jadx 四版本
            // 均为 (Context, ScrollTabBarLayout.a)), "SlotConfig" 只是 Kotlin @Metadata
            // 里的混淆前原名, 运行时不存在该类名。仍把 \$SlotConfig 探测放在前面:
            // 宿主将来若去旋转还原原名, findClassIfExists 自然命中, 兜底不因签名
            // 漂移静默失效; 两种形态都未命中时留日志, 不再被外层 catch 吞成无声
            var constructorHooked = false
            for (slotParamName in listOf(
                "com.baidu.tieba.homepage.framework.indicator.ScrollTabBarLayout\$SlotConfig",
                "com.baidu.tieba.homepage.framework.indicator.ScrollTabBarLayout\$a"
            )) {
                val slotParamType = XposedHelpers.findClassIfExists(slotParamName, sClassLoader)
                    ?: continue
                runCatching {
                    hookAfterConstructor(
                        slotClass,
                        android.content.Context::class.java,
                        slotParamType
                    ) { param ->
                        val slot = param.thisObject as View
                        slot.post { forceSearchIconVisible(slot) }
                    }
                }.onSuccess { constructorHooked = true }
                if (constructorHooked) break
            }
            if (!constructorHooked) {
                logBoth("HomeSearchIcon: 构造兜底 hook 未命中(无 (Context, ScrollTabBarLayout\$a) 构造器?)")
            }
        } catch (ignored: Throwable) {
        }

        // 12.74 旧版顶栏: FixedAppBarLayout.setSearchImgVisibility(Z) 控制折叠放大镜显隐,
        // H(F) 由滚动偏移驱动, 参数为放大镜透明度 (22.14 宿主实证 T(F): f2<=0 隐、
        // f2>0 显并设 alpha=f2——0=隐藏, 值越大越实; 强制 1f 即常驻全显)。
        // 22.12 起 H 被挪用为无参方法(AlaLiveTipView 相关), hookAllMethods 按
        // "args[0] is Float" 守卫排除, 滚动驱动改走 T(F), 一并挂上(旧版本无 T 则为空集)
        try {
            val appBarLayout = findClass("com.baidu.tieba.homepage.framework.indicator.FixedAppBarLayout")

            XposedBridge.hookAllMethods(appBarLayout, "setSearchImgVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[0] = true
                }
            })

            val scrollAlphaHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isNotEmpty() && param.args[0] is Float) {
                        param.args[0] = 1f
                    }
                }
            }
            XposedBridge.hookAllMethods(appBarLayout, "H", scrollAlphaHook)
            XposedBridge.hookAllMethods(appBarLayout, "T", scrollAlphaHook)

            hookAfterMethod(appBarLayout, "onAttachedToWindow") { param ->
                val layout = param.thisObject as View
                layout.post { forceSearchIconLegacy(layout) }
            }
        } catch (ignored: Throwable) {
        }

        // 隐藏推荐列表顶部的大搜索框(列表头item), 仅作用于首页
        try {
            hookAfterMethod(
                "com.baidu.tbadk.widget.TbSearchBoxView",
                "onAttachedToWindow"
            ) { param ->
                val view = param.thisObject as View
                if (isHomeSearchBox(view)) {
                    view.post { hideSearchBarRow(view) }
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    // getSearchIconView 的反射解析缓存。负结果用 Optional.empty 落条目:
    // ConcurrentHashMap.computeIfAbsent 对 null 不记录, 负缓存会永久失效,
    // 版本漂移后 y/z 钩点(随首页滚动每帧触发)每次都要重扫类层次并构造
    // NoSuchMethodException。y/z 每帧触发, callMethod 每次都遍历类层次查找方法
    private val sSearchIconGetterCache =
        java.util.concurrent.ConcurrentHashMap<Class<*>, java.util.Optional<java.lang.reflect.Method>>()

    // 22.11+: 强制右槽位放大镜可见可点。y/z 钩点每滚动帧触发, 属性写入前先比对
    // 当前值——无条件写 alpha/visibility 会各自触发一次宿主侧无效化重绘
    private fun forceSearchIconVisible(slot: View) {
        try {
            val getter = sSearchIconGetterCache.computeIfAbsent(slot.javaClass) { clazz ->
                var c: Class<*>? = clazz
                while (c != null && c != Any::class.java) {
                    try {
                        return@computeIfAbsent java.util.Optional.of(c.getDeclaredMethod("getSearchIconView"))
                    } catch (ignored: NoSuchMethodException) {
                    }
                    c = c.superclass
                }
                java.util.Optional.empty()
            }.orElse(null) ?: return
            val icon = getter.invoke(slot) as? View ?: return
            if (icon.visibility != View.VISIBLE) {
                icon.visibility = View.VISIBLE
            }
            if (icon.alpha != 1f) {
                icon.alpha = 1f
            }
            if (!icon.isClickable) {
                icon.isClickable = true
            }
        } catch (ignored: Throwable) {
        }
    }

    // 12.74: 强制旧版顶栏放大镜可见
    private fun forceSearchIconLegacy(layout: View) {
        try {
            val holder = XposedHelpers.getObjectField(layout, "a") ?: return
            val searchImg = XposedHelpers.getObjectField(holder, "d") as? View ?: return
            XposedHelpers.callMethod(layout, "setSearchImgVisibility", true)
            searchImg.alpha = 1f
        } catch (ignored: Throwable) {
        }
    }

    private fun hideSearchBarRow(view: View) {
        try {
            val row = view.parent as? View ?: return
            (row.layoutParams as? ViewGroup.LayoutParams)?.let {
                it.height = 0
                row.layoutParams = it
            }
            row.visibility = View.GONE
        } catch (ignored: Throwable) {
        }
    }

    private fun isHomeSearchBox(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent.javaClass.name == "com.baidu.tieba.homepage.personalize.PersonalizePageView") {
                return true
            }
            parent = (parent as? View)?.parent
        }
        return false
    }
}
