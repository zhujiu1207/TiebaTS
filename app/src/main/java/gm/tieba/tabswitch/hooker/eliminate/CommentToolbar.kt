package gm.tieba.tabswitch.hooker.eliminate

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.getString
import gm.tieba.tabswitch.dao.Preferences.putString
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.util.dipToPx
import gm.tieba.tabswitch.util.fixAlertDialogWidth
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.widget.Switch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

// 自定义"发表评论"面板底部图标栏(语音/图片/Ai/表情/@/会员):
// 可隐藏单个图标, 拖动设置弹窗中的开关行调整图标顺序。
// 图标栏是 com.baidu.tbadk.editortools.EditorBar: 图标(BLauncher)按其内部
// List 字段的顺序在 m() 中 addView, 隐藏/显示由宿主 display()/hide() 控制。
// 因此不直接增删 EditorBar 的子 View(会与宿主重建逻辑冲突导致图标消失):
// before m() 重排图标列表, after m()/onLayout 把关闭的图标置 GONE 并持续保持。
object CommentToolbar : XposedContext(), IHooker {

    override fun key(): String {
        return "comment_toolbar"
    }

    override fun hook() {
        // 注册配置缓存失效监听: SP 内部弱引用持有监听器, 属性强引用防 GC 后缓存永不失效
        try {
            gm.tieba.tabswitch.dao.Preferences.registerOnSharedPreferenceChangeListener(sConfigPrefsListener)
        } catch (ignored: Throwable) {
        }
        try {
            val barClass = findClass(EDITOR_BAR)
            hookBeforeMethod(barClass, BUILD_ICONS_METHOD) { param ->
                try {
                    reorderIcons(param.thisObject)
                } catch (tr: Throwable) {
                    XposedBridge.log(tr)
                }
            }
            hookAfterMethod(barClass, BUILD_ICONS_METHOD) { param ->
                try {
                    enforceVisibility(param.thisObject as ViewGroup)
                } catch (tr: Throwable) {
                    XposedBridge.log(tr)
                }
            }
            // 宿主 display()/hide() 切换可能把被禁图标重新显示, 布局时持续保持隐藏
            hookAfterMethod(
                barClass, "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ) { param ->
                try {
                    enforceVisibility(param.thisObject as ViewGroup)
                } catch (ignored: Throwable) {
                }
            }
        } catch (tr: Throwable) {
            XposedBridge.log(tr)
        }
    }

    // EditorBar 中持有图标顺序的 List 字段: 元素是 View 且 content-desc 可识别
    // 图标 List 字段候选按类缓存: 反射全层级扫描仅在每类首次出现时执行一次,
    // 字段集合是类级稳定的, 实例匹配(List 内含 >=4 个可识别图标 View)每次照做
    private val iconListFieldCache =
        java.util.concurrent.ConcurrentHashMap<Class<*>, List<java.lang.reflect.Field>>()

    private fun iconListField(bar: Any): MutableList<Any>? {
        val candidates = iconListFieldCache.getOrPut(bar.javaClass) {
            val out = ArrayList<java.lang.reflect.Field>(2)
            var cls: Class<*>? = bar.javaClass
            while (cls != null && cls != Any::class.java) {
                for (field in cls.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                        continue
                    }
                    if (!List::class.java.isAssignableFrom(field.type)) {
                        continue
                    }
                    field.isAccessible = true
                    out.add(field)
                }
                cls = cls.superclass
            }
            out
        }
        for (field in candidates) {
            val value = runCatching { field.get(bar) as? List<*> }.getOrNull() ?: continue
            if (value.isEmpty()) {
                continue
            }
            val isIconList = value.count { it is View && idOf(it as View) != null } >= 4
            if (isIconList && value.size >= 4) {
                @Suppress("UNCHECKED_CAST")
                return value as MutableList<Any>
            }
        }
        return null
    }

    // 重排失败只记一次的标志(见 reorderIcons)
    private var sReorderFailureLogged = false

    private fun reorderIcons(bar: Any) {
        val list = iconListField(bar) ?: return
        val items = list.toList()
        val iconItems = items.filter { it is View && idOf(it as View) != null }
        if (iconItems.size < 4) {
            return
        }
        val idToItem = HashMap<String, Any>()
        for (item in iconItems) {
            idToItem[idOf(item as View)!!] = item
        }
        // 只交换图标项的顺序, 其它项保持原位, 图标按配置顺序依次顶上
        // (与 enforceVisibility 共用同一份解析缓存, 不再每次重解析 JSON)
        val queue = ArrayDeque(cachedOrderedConfig().map { it.first }.filter { idToItem.containsKey(it) })
        val used = HashSet<Any>()
        val newItems = items.map { item ->
            if (item is View && idOf(item) != null && queue.isNotEmpty()) {
                val candidate = idToItem[queue.removeFirst()]
                if (candidate != null && used.add(candidate)) {
                    candidate
                } else {
                    item
                }
            } else {
                item
            }
        }
        runCatching {
            list.clear()
            list.addAll(newItems)
        }.onFailure {
            // 宿主字段可能是不可变 List(如 Arrays.asList): 每次重排都抛异常会刷爆
            // LSPosed 日志, 只记一次(选中字段的逻辑本身不变)
            if (!sReorderFailureLogged) {
                sReorderFailureLogged = true
                XposedBridge.log("CommentToolbar: 图标列表不可变, 重排跳过 -- $it")
            }
        }
    }

    // 被本模块按配置压成 GONE 的图标 id 集合: 用于配置改回开启后恢复可见
    // (同屏不重建的 EditorBar 实例里, 只压不抬会让改回开启的图标一直消失)
    @Volatile
    private var sHiddenByConfig: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // 宿主 out-set 协议隐藏(语音/图片按场景禁用, xy6.setOutSetVisibilty)的图标
    // 不得由模块抬回: EditorBar.q() 对 visibility==8 && getIsOutSetVisibility()
    // 的图标保持隐藏是宿主自身意图, 配置开启也不能覆盖。非 BLauncher 子项无此
    // 方法, 反射失败按"无宿主隐藏意图"处理
    private fun isHostOutSetHidden(child: View): Boolean {
        return runCatching {
            XposedHelpers.callMethod(child, "getIsOutSetVisibility") as? Boolean
        }.getOrNull() ?: false
    }

    private fun enforceVisibility(bar: ViewGroup) {
        val allOn = configOnById.values.all { it }
        // 全部图标开启(默认配置)时零工作直接返回: onLayout 每帧触发,
        // 不做短路的话默认用户也要每帧付遍历+字符串分配的开销。
        // 例外: 此前有图标被配置压成 GONE(配置从关改回开), 需先抬回 VISIBLE
        if (allOn) {
            if (sHiddenByConfig.isNotEmpty()) {
                for (i in 0 until bar.childCount) {
                    val child = bar.getChildAt(i)
                    val id = idOf(child)
                    if (id != null && sHiddenByConfig.remove(id) &&
                        child.visibility != View.VISIBLE && !isHostOutSetHidden(child)
                    ) {
                        child.visibility = View.VISIBLE
                    }
                }
            }
            return
        }
        for (i in 0 until bar.childCount) {
            val child = bar.getChildAt(i)
            val id = idOf(child) ?: continue
            val on = configOnById[id] ?: true
            if (on) {
                // 配置改回开启: 恢复此前被压隐藏的图标(宿主 out-set 隐藏的除外)
                if (id in sHiddenByConfig && sHiddenByConfig.remove(id) &&
                    child.visibility != View.VISIBLE && !isHostOutSetHidden(child)
                ) {
                    child.visibility = View.VISIBLE
                }
                continue
            }
            if (child.visibility != View.GONE) {
                child.visibility = View.GONE
            }
            sHiddenByConfig.add(id)
        }
    }

    private fun idOf(view: View): String? {
        // 先按原始 desc 直查: 绝大多数 desc 本身就是精确键, 避免 trim 的字符串分配;
        // trim 仅在直查未命中时兜底(带首尾空白的 desc)
        val desc = view.contentDescription?.toString() ?: return null
        return DESC_TO_ID[desc] ?: DESC_TO_ID[desc.trim()]
    }

    // 配置解析结果缓存, 避免每次布局都解析 JSON;
    // associate 结果同样缓存: configOnById 在 onLayout 每帧触发, 每次分配新 Map 是无谓开销。
    // 失效改由偏好变更监听驱动(hook() 注册): 该路径每帧执行, 每帧读 SP 原串做
    // 字符串比对是纯浪费; 配置写入点仅本对象弹窗(同进程), 监听失效足够可靠
    @Volatile
    private var cacheLoaded = false
    @Volatile
    private var cachedConfig: List<Triple<String, String, Boolean>> = emptyList()
    @Volatile
    private var cachedConfigById: Map<String, Boolean> = emptyMap()

    private val sConfigPrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == CONFIG_KEY) {
                cacheLoaded = false
            }
        }

    // 有序配置(带缓存): reorderIcons 与 configOnById 共用同一份解析结果
    private fun cachedOrderedConfig(): List<Triple<String, String, Boolean>> {
        if (!cacheLoaded) {
            synchronized(this) {
                if (!cacheLoaded) {
                    val parsed = parseConfig(getString(CONFIG_KEY))
                    cachedConfig = parsed
                    cachedConfigById = parsed.associate { it.first to it.third }
                    cacheLoaded = true
                }
            }
        }
        return cachedConfig
    }

    private val configOnById: Map<String, Boolean>
        get() {
            cachedOrderedConfig()
            return cachedConfigById
        }

    fun showConfigDialog(activity: Activity, onSaved: () -> Unit) {
        val items = loadConfig().toMutableList()
        val switchById = HashMap<String, Switch>()

        val listLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // 上下留白对齐框架 setMessage 布局的文本区节奏(标题/按钮不贴行)
            setPadding(0, dipToPx(activity, 8f), 0, dipToPx(activity, 8f))
        }

        // 相邻换位: 用 detach/attach 移动被拖行, 不会像 removeView 那样
        // 向被触摸行派发 ACTION_CANCEL 打断拖动; 被挤开的行做平移补位动画
        fun swapLive(from: Int, to: Int, rowHeight: Int) {
            if (from == to) {
                return
            }
            val moving = listLayout.getChildAt(from)
            val displaced = listLayout.getChildAt(to)
            if (moving == null || displaced == null) {
                return
            }
            val item = items.removeAt(from)
            items.add(to, item)
            val params = moving.layoutParams
            XposedHelpers.callMethod(listLayout, "detachViewFromParent", moving)
            XposedHelpers.callMethod(listLayout, "attachViewToParent", moving, to, params)
            listLayout.requestLayout()
            displaced.translationY = if (to > from) rowHeight.toFloat() else -rowHeight.toFloat()
            displaced.animate().translationY(0f).setDuration(150).start()
        }

        fun addItemRow(index: Int) {
            val item = items[index]
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dipToPx(activity, 48f)
                tag = item.first
                // 24dp: 与框架 setMessage 文本内边距一致(同低配弹窗, 三弹窗 UI 对齐)
                setPaddingRelative(dipToPx(activity, 24f), 0, dipToPx(activity, 24f), 0)
            }
            val handle = TextView(activity).apply {
                text = "≡"
                textSize = 22f
                setPadding(
                    dipToPx(activity, 12f), dipToPx(activity, 12f),
                    dipToPx(activity, 16f), dipToPx(activity, 12f)
                )
                // 拖动 ≡: 被拖行跟随手指, 越过相邻行时与其实时换位,
                // 被挤开的行动画滑到空出的位置
                var grabOffset = 0f
                var dragIndex = -1
                var rowHeight = 0
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (row.height > 0) {
                                dragIndex = listLayout.indexOfChild(row)
                                val location = IntArray(2)
                                listLayout.getLocationOnScreen(location)
                                grabOffset = event.rawY - (location[1] + dragIndex * row.height)
                                rowHeight = row.height
                            } else {
                                dragIndex = -1
                            }
                            dragIndex >= 0
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (dragIndex >= 0) {
                                val location = IntArray(2)
                                listLayout.getLocationOnScreen(location)
                                val desiredTop = event.rawY - grabOffset
                                val target = ((desiredTop - location[1]) / rowHeight.toFloat())
                                    .roundToInt().coerceIn(0, listLayout.childCount - 1)
                                while (dragIndex != target) {
                                    val next = if (target > dragIndex) dragIndex + 1 else dragIndex - 1
                                    swapLive(dragIndex, next, rowHeight)
                                    dragIndex = next
                                }
                                row.translationY = desiredTop - (location[1] + dragIndex * rowHeight)
                            }
                            dragIndex >= 0
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            row.translationY = 0f
                            dragIndex = -1
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            // 被拖行已通过实时换位落在目标槽位, 平滑吸附
                            if (dragIndex >= 0) {
                                row.animate().translationY(0f).setDuration(100).start()
                            }
                            dragIndex = -1
                            true
                        }
                        else -> false
                    }
                }
            }
            row.addView(handle)
            row.addView(TextView(activity).apply {
                text = item.second
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val switch = Switch()
            if (item.third) switch.turnOn() else switch.turnOff()
            switchById[item.first] = switch
            row.addView(
                switch.bdSwitch, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            listLayout.addView(row)
        }

        for (i in items.indices) addItemRow(i)

        val alert = AlertDialog.Builder(activity, getDialogTheme(getContext()))
            .setTitle("自定义评论图标")
            .setView(listLayout)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        alert.setOnShowListener {
            alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val array = JSONArray()
                for (i in 0 until listLayout.childCount) {
                    val id = listLayout.getChildAt(i).tag as String
                    array.put(JSONObject().put("id", id).put("on", switchById[id]?.isOn() ?: true))
                }
                putString(CONFIG_KEY, array.toString())
                // 缓存失效由偏好变更监听完成, 这里无需手动清
                alert.dismiss()
                onSaved()
            }
        }
        alert.show()
        fixAlertDialogWidth(alert)
    }

    // 配置为有序数组 [{id, on}...], 未存储时用默认顺序且全部开启
    fun loadConfig(): List<Triple<String, String, Boolean>> {
        return parseConfig(getString(CONFIG_KEY))
    }

    private fun parseConfig(raw: String?): List<Triple<String, String, Boolean>> {
        if (raw != null) {
            try {
                val array = JSONArray(raw)
                val result = ArrayList<Triple<String, String, Boolean>>(array.length())
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.getString("id")
                    LABELS[id]?.let { result.add(Triple(id, it, obj.optBoolean("on", true))) }
                }
                // 补上配置缺失的图标, 按默认顺序排在末尾
                for (item in DEFAULT_CONFIG) {
                    if (result.none { it.first == item.first }) {
                        result.add(item)
                    }
                }
                return result
            } catch (ignored: Throwable) {
            }
        }
        return DEFAULT_CONFIG
    }

    const val CONFIG_KEY = "comment_toolbar_config"

    // 22.11: 图标在 m() 中按内部列表顺序 attach
    private const val EDITOR_BAR = "com.baidu.tbadk.editortools.EditorBar"
    private const val BUILD_ICONS_METHOD = "m"

    val LABELS = linkedMapOf(
        "voice" to "语音",
        "image" to "图片",
        "ai" to "Ai",
        "emotion" to "表情",
        "at" to "@",
        "vip" to "会员"
    )

    // 宿主中图标以 content-desc 区分: Ai 图标的 desc 实为"赠送礼物"
    private val DESC_TO_ID = mapOf(
        "语音" to "voice",
        "图片" to "image",
        "赠送礼物" to "ai",
        "表情" to "emotion",
        "@好友" to "at",
        "会员" to "vip"
    )

    private val DEFAULT_CONFIG = listOf(
        Triple("voice", "语音", true),
        Triple("image", "图片", true),
        Triple("ai", "Ai", true),
        Triple("emotion", "表情", true),
        Triple("at", "@", true),
        Triple("vip", "会员", true)
    )
}
