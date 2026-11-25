package gm.tieba.tabswitch.hooker.eliminate

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.dao.Preferences.getStringSet
import gm.tieba.tabswitch.dao.Preferences.putBoolean
import gm.tieba.tabswitch.dao.Preferences.putStringSet
import gm.tieba.tabswitch.dao.Preferences.remove
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.util.dipToPx
import gm.tieba.tabswitch.util.fixAlertDialogWidth
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.widget.Switch
import gm.tieba.tabswitch.widget.TbToast
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import kotlin.concurrent.thread

// 22.11 顶部Tab数据层: 数据源类的 i() 是顶栏tab列表的唯一数据源——读取本地缓存/网络数据,
// 解析为tab条目并在缺失时补插默认的推荐/直播后返回; NewScrollFragmentAdapter 据此建页,
// 顶栏strip(NewHomeTabBarLayout)又完全由适配器驱动, 因此在此单点处理后两端保持一致。
// 直播Tab此前删不掉的根因即数据源 c() 会在缺失时强制补插直播, 数据/视图层删除均无法根治。
// 22.11 默认不带关注Tab, 关闭态时在此注入原生关注页(由适配器建 ConcernFragment)。
// 隐藏直播/有料/关注/三角洲/S16 等已合并为"隐藏顶部Tab"(home_top_tab_hide):
// tab 名称随宿主热更新轮换, 硬编码开关名必然漂移, 改为设置弹窗实时列出当前
// Tab栏条目勾选, 按展示名称集合过滤; 名称热更后旧集合失配即失效, 重开弹窗
// 重新勾选即可。
// 数据源与条目类名随宿主版本混淆轮换, 按方法返回类型/字段布局探测候选:
// 22.12.1.0 实测命中 t1b/bp6(22.12 的 j0b 无 List 型 i()、uo6 无字段布局,
// 均被探测排除), 22.13.1.1 起数据源轮换为 q1b
// (方法布局与 22.12 的 t1b 完全同构, i() 返回 List), 条目轮换为 lp6(字段布局
// 与旧 bp6 一致: int a / int i / String b / String c); 22.14.1.0 起数据源
// 轮换为 h3b(方法布局与 22.13 的 q1b 完全同构, 连 i() 所在行号都一致),
// 条目轮换为 mp6(字段布局同旧 lp6); 22.15.1.1 起数据源轮换为 i4b
// (i() 返回 List<fq6>, 方法布局与 22.14 的 h3b 完全同构), 条目轮换为 fq6
// (字段布局同旧 mp6); 注意 22.13 的 t1b 是同名异义类
// (顶栏背景配置, 无 i() 返回 List), 22.14 的 q1b 是同名异义页面类
// (extends es<a1b>, 无 i() 返回 List), 22.15 的 h3b 是同名异义接口、
// mp6 是 scheme 白名单工具类, 返回类型校验自动排除。
// 探测失败自动回退旧名, 排在最前的新名命中后条目类取同代候选
class HomeTopTab : XposedContext(), IHooker {

    override fun key(): String {
        return "home_top_tab"
    }

    // 分项开关缓存: i() 是数据源读取回调, 每次最多实时读 2 个 SP;
    // 缓存后热路径只剩一次 ConcurrentHashMap 读, 变更经偏好监听失效,
    // 设置页改动仍即时生效
    private val sSwitchCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val sPrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            sSwitchCache.clear()
        }

    private fun switch(key: String): Boolean {
        sSwitchCache[key]?.let { return it }
        val enabled = getBoolean(key)
        sSwitchCache[key] = enabled
        return enabled
    }

    override fun hook() {
        // SP 内部弱引用持有监听器, 属性强引用防 GC 后缓存永不失效
        try {
            gm.tieba.tabswitch.dao.Preferences.registerOnSharedPreferenceChangeListener(sPrefsListener)
        } catch (ignored: Throwable) {
        }
        try {
            val sourceName = resolveTabListSource()
            // 供弹窗实时扫描: 保留数据源 i() 的方法引用, 回调里再弱引用捕获实例
            runCatching {
                val method = findClass(sourceName).declaredMethods.firstOrNull { candidate ->
                    candidate.name == "i" && candidate.parameterTypes.isEmpty() &&
                        candidate.returnType == java.util.List::class.java
                }
                method?.isAccessible = true
                sTabListMethod = method
            }
            hookAfterMethod(sourceName, "i") { param ->
                // 捕获数据源实例: 弹窗打开时对它现调 i() 主动扫描当前Tab栏
                sTabSourceRef = WeakReference(param.thisObject)
                @Suppress("UNCHECKED_CAST")
                val tabs = param.result as? List<Any?> ?: return@hookAfterMethod
                // 复制式过滤: 原地修改在宿主返回不可变列表(Arrays.asList/List.of)时抛
                // UnsupportedOperationException 中断回调, 已生效的删除保留而后续开关
                // 与关注Tab注入被跳过(半生效); 原地修改也会污染宿主复用的缓存列表。
                // 删除+注入可能相互抵消(条数不变内容已变), 故不做条数比对, 恒写回副本
                // 逐项隔离: 条目字段漂移(getIntField 抛 NoSuchFieldError)时只损失该
                // 开关对应项, working 的部分修改仍写回——param.result 在最后一行,
                // 中途异常被外层吞掉等于本次全部开关失效且无日志
                fun isolate(tag: String, action: () -> Unit) {
                    runCatching(action).onFailure {
                        // i() 每次数据源读取都触发, 仅首次失败落日志防刷屏
                        if (sTabOpFailLogged.compareAndSet(false, true)) {
                            logBoth("HomeTopTab: [$tag] 失败 -- $it")
                        }
                    }
                }
                val working = ArrayList<Any?>(tabs)
                // 合并开关开启时按弹窗勾选的名称集合过滤; "关注"勾选时额外按
                // type 0 移除并抑制注入(注入条目与原生条目的展示名一致, 名称过滤
                // 已可命中, 按 type 移除兜底服务端下发条目名称异常的情况)
                val hideNames = if (switch("home_top_tab_hide")) {
                    getStringSet("home_top_tab_hide_names")
                } else {
                    emptySet()
                }
                if (FOLLOW_TAB_NAME in hideNames) {
                    isolate("hide_follow") { removeTabType(working, TAB_TYPE_FOLLOW) }
                } else {
                    isolate("inject_follow") { injectFollowTab(working) }
                }
                if (hideNames.isNotEmpty()) {
                    isolate("hide_selected") { removeTabNames(working, hideNames) }
                }
                // 快照最终Tab名(去重保序)供设置弹窗展示: 用户看到的条目才能被勾选,
                // 含注入的关注Tab; 弹窗打开时通常已在首页触发过 i(), 快照即实时数据
                runCatching {
                    val names = LinkedHashSet<String>()
                    for (item in working) {
                        val name = item?.let { XposedHelpers.getObjectField(it, "b") as? String }
                        if (!name.isNullOrEmpty()) names.add(name)
                    }
                    sTabNamesSnapshot = ArrayList(names)
                }
                param.result = working
            }
        } catch (tr: Throwable) {
            // 数据源解析失败时 home_top_tab 数据层整体失效(过滤/注入全灭):
            // 不能完全静默, 留一次性线索(类名随版本漂移属预期降级)
            logBoth("HomeTopTab: 数据源解析/注册失败(版本漂移时预期降级) -- $tr")
        }
    }

    // 候选类的 i() 必须返回 java.util.List 才是真正的数据源:
    // 同名类的无参 i() 可能被挪用为其他返回类型, 按返回类型区分
    // (22.13 的 t1b 同名类、22.15 的 h3b 同名接口即因此被排除)
    private fun resolveTabListSource(): String {
        for (name in listOf(
            "com.baidu.tieba.i4b", "com.baidu.tieba.h3b", "com.baidu.tieba.q1b",
            "com.baidu.tieba.t1b", "com.baidu.tieba.j0b"
        )) {
            val clazz = runCatching { findClass(name) }.getOrNull() ?: continue
            val returnsList = clazz.declaredMethods.any { method ->
                method.name == "i" && method.parameterTypes.isEmpty() &&
                    method.returnType == java.util.List::class.java
            }
            if (returnsList) {
                mEntryClass = resolveEntryClass(name)
                return name
            }
        }
        throw ClassNotFoundException("home_top_tab: no tab list source found")
    }

    // 条目类与数据源同代: 数据源选定后取同代候选并校验字段布局
    // (22.12 的 uo6 只剩 String a, 22.13 的 t1b 无字段布局, 布局不符时被跳过;
    //  22.13 的条目为 lp6, 与 22.12 的 bp6 同代对应; 22.14 的条目为 mp6,
    //  与 22.13 的 lp6 同代对应; 22.15 的条目为 fq6, 与 22.14 的 mp6 同代对应,
    //  22.15 的 mp6 已同名异义为 scheme 白名单工具类, 字段布局不符自动跳过)
    private fun resolveEntryClass(sourceName: String): String {
        val entryCandidates = when (sourceName) {
            "com.baidu.tieba.t1b" -> listOf("com.baidu.tieba.bp6", "com.baidu.tieba.uo6")
            "com.baidu.tieba.q1b" -> listOf("com.baidu.tieba.lp6", "com.baidu.tieba.bp6", "com.baidu.tieba.uo6")
            "com.baidu.tieba.h3b" -> listOf("com.baidu.tieba.mp6", "com.baidu.tieba.lp6", "com.baidu.tieba.bp6")
            "com.baidu.tieba.i4b" -> listOf("com.baidu.tieba.fq6", "com.baidu.tieba.mp6", "com.baidu.tieba.lp6")
            else -> listOf("com.baidu.tieba.uo6", "com.baidu.tieba.bp6")
        }
        for (name in entryCandidates) {
            val clazz = runCatching { findClass(name) }.getOrNull() ?: continue
            val fields = runCatching { clazz.declaredFields.associate { it.name to it.type } }.getOrNull()
                ?: continue
            if (fields["a"] == Int::class.javaPrimitiveType &&
                fields["i"] == Int::class.javaPrimitiveType &&
                fields["b"] == String::class.java &&
                fields["c"] == String::class.java
            ) {
                return name
            }
        }
        throw ClassNotFoundException("home_top_tab: no tab entry class found")
    }

    // 移除指定 tab_type 的条目 (uo6.a)
    private fun removeTabType(tabs: MutableList<Any?>, type: Int) {
        val iterator = tabs.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next() ?: continue
            if (XposedHelpers.getIntField(item, "a") == type) {
                iterator.remove()
            }
        }
    }

    // 按展示名称集合移除条目 (uo6.b): 三角洲等游戏频道 tab 由服务端下发,
    // tab_type 不固定, 以展示名称为准; 名称随热更新变化后集合失配自然失效,
    // 由用户在设置弹窗重新勾选
    private fun removeTabNames(tabs: MutableList<Any?>, names: Set<String>) {
        val iterator = tabs.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next() ?: continue
            val name = XposedHelpers.getObjectField(item, "b") as? String
            if (name != null && name in names) {
                iterator.remove()
            }
        }
    }

    // 注入原生关注页(type 0), 置于推荐Tab之后; 已存在则不重复注入
    private fun injectFollowTab(tabs: MutableList<Any?>) {
        for (item in tabs) {
            if (item != null && XposedHelpers.getIntField(item, "a") == TAB_TYPE_FOLLOW) {
                return
            }
        }
        val follow = XposedHelpers.newInstance(findClass(mEntryClass))
        XposedHelpers.setIntField(follow, "a", TAB_TYPE_FOLLOW)
        XposedHelpers.setObjectField(follow, "b", FOLLOW_TAB_NAME)
        XposedHelpers.setObjectField(follow, "c", FOLLOW_TAB_CODE)
        XposedHelpers.setIntField(follow, "i", 1)
        val anchor = tabs.indexOfFirst { it != null && XposedHelpers.getIntField(it, "a") == TAB_TYPE_RECOMMEND }
        tabs.add(if (anchor >= 0) anchor + 1 else 0, follow)
    }

    private var mEntryClass: String = "com.baidu.tieba.fq6"

    companion object {
        // Tab 操作首次失败日志标志: 数据源读取回调高频触发, 逐次落盘会刷屏
        private val sTabOpFailLogged = java.util.concurrent.atomic.AtomicBoolean(false)

        // 当前Tab栏条目名快照: i() 回调每次以最终列表(含注入的关注)刷新,
        // 供实时扫描失败时兜底展示
        @Volatile
        private var sTabNamesSnapshot: List<String> = emptyList()

        // 弹窗实时扫描用: 数据源实例弱引用(hook 回调捕获)与 i() 方法引用(hook 注册时解析)
        @Volatile
        private var sTabSourceRef: WeakReference<Any>? = null
        @Volatile
        private var sTabListMethod: Method? = null

        private const val TAB_TYPE_FOLLOW = 0
        private const val TAB_TYPE_RECOMMEND = 1
        private const val FOLLOW_TAB_NAME = "关注"
        private const val FOLLOW_TAB_CODE = "concern"
        private const val LIVE_TAB_NAME = "直播"

        // 视图层兜底判定(HomeTopBar 在旧版数据层解析失败时隐藏直播Tab的唯一依据):
        // 合并开关开启且选中"直播"; 旧分项键 hide_live 已迁移移除, 不再读取
        fun isLiveNameSelected(): Boolean =
            getBoolean("home_top_tab_hide") &&
                getStringSet("home_top_tab_hide_names").contains(LIVE_TAB_NAME)

        // 配置弹窗: 与"低配模式"同款(模块 Switch 行 + 保存/取消)。
        // 打开时先对数据源实时扫描一遍Tab栏: 条目名随宿主热更新轮换(如三角洲被
        // S16 替代), 被动快照可能滞后, 必须现调数据源 i() 取当前列表再展示数量
        // 与勾选项; 扫描失败退回上次快照。现调必须在主线程: i() 是宿主张单点
        // (操作内部集合), 与主线程的 i() 调用无任何同步, 后台线程现调与首页
        // 刷新/切 Tab 并发会 CME 甚至把宿主数据结构置于不一致状态
        fun showConfigDialog(activity: Activity, onSaved: (Boolean) -> Unit) {
            activity.runOnUiThread {
                val tabs = scanTabNames()
                // 扫描期间设置页可能已退出, 弹窗挂在失效窗口上会崩宿主
                if (activity.isFinishing ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)
                ) {
                    return@runOnUiThread
                }
                showTabListDialog(activity, tabs, onSaved)
            }
        }

        // 实时扫描Tab栏: 反射调用数据源实例的 i()。该调用会再次经过本 hooker 的
        // after 钩子, 返回的是最终列表(含注入的关注)并顺带刷新快照
        private fun scanTabNames(): List<String> {
            val source = sTabSourceRef?.get() ?: return currentTabNames()
            val method = sTabListMethod ?: return currentTabNames()
            val fresh = runCatching {
                @Suppress("UNCHECKED_CAST")
                val result = method.invoke(source) as? List<Any?>
                result?.mapNotNull { item ->
                    item?.let { XposedHelpers.getObjectField(it, "b") as? String }
                        ?.takeIf { it.isNotEmpty() }
                }?.let { LinkedHashSet(it).toList() }
            }.getOrNull()
            return if (fresh.isNullOrEmpty()) currentTabNames() else fresh
        }

        private fun currentTabNames(): List<String> = sTabNamesSnapshot

        private fun showTabListDialog(activity: Activity, tabs: List<String>, onSaved: (Boolean) -> Unit) {
            if (tabs.isEmpty()) {
                TbToast.showTbToast("暂未获取到Tab栏数据，请先回到首页再试", TbToast.LENGTH_SHORT)
                return
            }
            val saved = getStringSet("home_top_tab_hide_names")
            val switchByName = LinkedHashMap<String, Switch>()
            val listLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            listLayout.addView(TextView(activity).apply {
                text = "当前Tab栏共 ${tabs.size} 个，打开要隐藏的Tab"
                textSize = 13f
                setPaddingRelative(
                    dipToPx(activity, 12f), dipToPx(activity, 4f), dipToPx(activity, 20f), dipToPx(activity, 8f)
                )
            })
            tabs.forEach { name ->
                val switch = Switch().apply {
                    if (name in saved) turnOn() else turnOff()
                }
                switchByName[name] = switch
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dipToPx(activity, 48f)
                    setPaddingRelative(dipToPx(activity, 12f), 0, dipToPx(activity, 20f), 0)
                }
                row.addView(TextView(activity).apply {
                    text = name
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                })
                row.addView(
                    switch.bdSwitch, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                listLayout.addView(row)
            }

            val alert = AlertDialog.Builder(activity, getDialogTheme(getContext()))
                .setTitle("隐藏顶部Tab")
                .setView(listLayout)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create()
            alert.setOnShowListener {
                // 正向按钮点击置空后重挂, 避免未校验时系统默认直接 dismiss
                alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val selected = switchByName.filterValues { it.isOn() }.keys.toSet()
                    if (selected.isNotEmpty()) {
                        putStringSet("home_top_tab_hide_names", selected)
                        putBoolean("home_top_tab_hide", true)
                    } else {
                        remove("home_top_tab_hide_names")
                        putBoolean("home_top_tab_hide", false)
                    }
                    alert.dismiss()
                    // 数据层过滤经偏好监听即时生效, 主键视觉随保存结果同步
                    onSaved(selected.isNotEmpty())
                }
            }
            alert.show()
            fixAlertDialogWidth(alert)
        }
    }
}
