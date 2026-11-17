package gm.tieba.tabswitch.hooker.auto

import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.AcRules.findRule
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.hooker.deobfuscation.StringMatcher
import java.lang.reflect.Method

class FrsTab : XposedContext(), IHooker, Obfuscated {

    override fun key(): String {
        return "frs_tab"
    }

    override fun matchers(): List<Matcher> {
        return listOf(
            StringMatcher("forum_tab_current_list"),
            StringMatcher("c/f/frs/page?cmd=301001&format=protobuf")
        )
    }

    // 未应用时为哨兵值: TopController 消费后立即复位, 避免无 DataRes 配对时套用过期位置;
    // volatile 保证 build 钩子(解析线程)写入对 TopController 钩子(主线程)可见。
    // 残余限制: 两个吧的响应交错时仍可能张冠李戴(钩子间无请求关联可用的共享键),
    // 该窗口只能靠"消费即复位"收窄
    @Volatile
    private var mPosition = Int.MIN_VALUE

    // 在对象上按字段类型定位 ViewPager(CustomViewPager 优先, 兜底 androidx ViewPager)。
    // 目标类与字段按缓存复用: TopController 钩子在主线程高频触发, findClass +
    // findFirstFieldByExactType(全字段线性扫描, miss 时构造 NoSuchFieldError)
    // 每次照付的开销不划算; 字段布局是类级稳定的, 解析一次即可(含 null 负缓存,
    // 哨兵占位避免 ConcurrentHashMap.computeIfAbsent 不记录 null 映射)
    private val sCustomViewPagerClass by lazy {
        findClass("com.baidu.tbadk.widget.CustomViewPager")
    }
    private val sAndroidxViewPagerClass by lazy {
        findClass("androidx.viewpager.widget.ViewPager")
    }
    private val sViewPagerFieldCache =
        java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    private val sNoViewPagerField = Any()

    private fun findViewPagerIn(owner: Any?): Any? {
        if (owner == null) return null
        val field = sViewPagerFieldCache.computeIfAbsent(owner.javaClass) { clazz ->
            try {
                XposedHelpers.findFirstFieldByExactType(clazz, sCustomViewPagerClass)
            } catch (ignored: NoSuchFieldError) {
                try {
                    XposedHelpers.findFirstFieldByExactType(clazz, sAndroidxViewPagerClass)
                } catch (ignored2: NoSuchFieldError) {
                    sNoViewPagerField
                } catch (ignored3: Throwable) {
                    sNoViewPagerField
                }
            } catch (ignored: Throwable) {
                sNoViewPagerField
            }
        }
        if (field === sNoViewPagerField) return null
        return try {
            (field as java.lang.reflect.Field)[owner]
        } catch (ignored: Throwable) {
            null
        }
    }

    override fun hook() {
        hookBeforeMethod(
            "tbclient.FrsPage.DataRes\$Builder",
            "build", Boolean::class.javaPrimitiveType,
        ) { param ->
            val tabList = XposedHelpers.getObjectField(param.thisObject, "frs_main_tab_list") as? List<*>
            tabList?.forEachIndexed { index, tab ->
                // proto 字段未设置时为 null, 硬 as Int 会对每条数据抛 NPE 刷日志
                if (XposedHelpers.getObjectField(tab, "tab_type") as? Int == 14) {
                    mPosition = index
                    val tabId = XposedHelpers.getObjectField(tab, "tab_id") as? Int ?: return@forEachIndexed
                    XposedHelpers.setObjectField(
                        param.thisObject,
                        "frs_tab_default",
                        tabId
                    )
                    return@hookBeforeMethod
                }
            }
        }

        findRule(matchers()) { matcher, clazz, method ->
            when (matcher) {
                "forum_tab_current_list" -> {
                    if ("com.baidu.tieba.forum.controller.TopController" != clazz) return@findRule
                    // 12.x: 双参方法(参数1持有 ViewPager 字段); 22.12 起为单参 h2(k7a),
                    // ViewPager 字段挂在 TopController 自身(g)。方法按名取(优先双参形态),
                    // ViewPager 依次在参数1与方法所属对象上按字段类型定位
                    val topClass = findClass(clazz)
                    val candidates = topClass.declaredMethods.filter { it.name == method }
                    val targetMethod = candidates.firstOrNull {
                        it.parameterTypes.size == 2
                    } ?: candidates.firstOrNull() ?: return@findRule
                    hookAfterMethod(targetMethod) { param ->
                        // 先判哨兵再做反射: 绝大多数触发(未应用/已消费)无需
                        // 定位 ViewPager, 不应替它们白付字段查找开销
                        val position = mPosition
                        if (position == Int.MIN_VALUE) return@hookAfterMethod
                        val viewPager: Any? = findViewPagerIn(param.args.getOrNull(1))
                            ?: findViewPagerIn(param.thisObject)
                            ?: return@hookAfterMethod
                        mPosition = Int.MIN_VALUE
                        XposedHelpers.callMethod(viewPager, "setCurrentItem", position)
                    }
                }

                "c/f/frs/page?cmd=301001&format=protobuf" ->  {
                    // SharedPrefHelper 单例与类按缓存复用: 每个 FRS 响应解析都触发,
                    // findClass + getInstance 属重复固定开销(lazy 初始化抛异常时会重试)
                    val sharedPrefHelper by lazy {
                        XposedHelpers.callStaticMethod(
                            findClass("com.baidu.tbadk.core.sharedPref.SharedPrefHelper"),
                            "getInstance"
                        )
                    }
                    hookBeforeMethod(
                        clazz, method, "com.baidu.tieba.forum.model.FrsPageRequestMessage"
                    ) { param ->
                        // proto 字段未设置时为 null, 用 as? 兼容
                        if (XposedHelpers.getObjectField(param.args[0], "sortType") as? Int == -1) {
                            val lastSortType = XposedHelpers.callMethod(sharedPrefHelper, "getInt", "key_forum_last_sort_type", 0) as Int
                            XposedHelpers.setObjectField(param.args[0], "sortType", lastSortType)
                        }
                    }
                }
            }
        }
    }
}
