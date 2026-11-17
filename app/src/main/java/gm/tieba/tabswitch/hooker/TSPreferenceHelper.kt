package gm.tieba.tabswitch.hooker

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.Constants.strings
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.dao.Preferences.getString
import gm.tieba.tabswitch.dao.Preferences.putBoolean
import gm.tieba.tabswitch.dao.Preferences.putString
import gm.tieba.tabswitch.dao.Preferences.remove
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.util.dipToPx
import gm.tieba.tabswitch.util.Logger
import gm.tieba.tabswitch.util.fixAlertDialogWidth
import gm.tieba.tabswitch.util.getColor
import gm.tieba.tabswitch.util.getCurrentActivity
import gm.tieba.tabswitch.util.getTbadkCoreApplicationInst
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.util.getDimen
import gm.tieba.tabswitch.util.getDimenDip
import gm.tieba.tabswitch.util.getDrawableId
import gm.tieba.tabswitch.util.getObjectField
import gm.tieba.tabswitch.util.getR
import gm.tieba.tabswitch.util.isLightMode
import gm.tieba.tabswitch.widget.Switch
import gm.tieba.tabswitch.widget.TbToast
import gm.tieba.tabswitch.widget.TbToast.Companion.showTbToast
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * 开关视觉操作的统一防护: BdSwitchView 的 turnOn/turnOff/changeState 均为
 * 宿主混淆反射调用, 混淆名漂移时异常发生在宿主 View 框架直接调用的点击监听里,
 * 不经过 hook 包装层——必须就地捕获, 不能击穿宿主主线程。
 * 全部开关行共用本函数(原 18 处逐字重复的 runCatching+onFailure 样板),
 * "开关组件失效"文案单点维护。
 *
 * @param on true=turnOn / false=turnOff / null=changeState(双向切换)
 * @param toastOnFailure 对话框保存/自动清空等已有后续日志的静默路径传 false
 * @return 视觉操作是否成功; 调用方据此决定状态落盘时机, 保持各自原有顺序语义
 */
internal fun TSPreferenceHelper.SwitchButtonHolder.turnSafely(
    on: Boolean? = null,
    toastOnFailure: Boolean = true
): Boolean {
    val result = runCatching {
        when (on) {
            true -> bdSwitch.turnOn()
            false -> bdSwitch.turnOff()
            null -> bdSwitch.changeState()
        }
    }
    result.onFailure {
        Logger.log("开关组件失效: $it")
        if (toastOnFailure) {
            showTbToast("开关组件失效，贴吧版本可能不受支持", TbToast.LENGTH_SHORT)
        }
    }
    return result.isSuccess
}

object TSPreferenceHelper : XposedContext() {

    @JvmStatic
    fun createTextView(text: String?): TextView = TextView(getContext()).apply {
        this.text = text
        setTextColor(getColor("CAM_X0108"))
        textSize = getDimenDip("fontsize22")

        layoutParams = text?.let {
            setPaddingRelative(
                getDimen("ds30").toInt(),
                getDimen("ds20").toInt(),
                0,
                getDimen("ds20").toInt()
            )
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        } ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, getDimen("ds32").toInt())
    }

    // 22.11 起 TbSettingTextTipView 的文字颜色跟随创建时 Context 的主题解析，
    // 应用级 Context 会得到灰色，因此优先用当前 Activity 作为 Context。
    // 注意：getCurrentActivity() 在页面 onCreate 阶段可能返回 null，
    // 其内部的 `as Activity` 强转对 null 是空操作（check-cast 不抛异常），必须用 as? 二次防护
    private fun viewContext(): Context {
        val activity: Activity? = try {
            @Suppress("UNCHECKED_CAST")
            XposedHelpers.callMethod(getTbadkCoreApplicationInst(), "getCurrentActivity") as? Activity
        } catch (tr: Throwable) {
            null
        }
        return activity ?: getContext()
    }

    // 换肤时需要手动刷新的按钮（弱引用持有，页面销毁后自动清理）。
    // onChangeSkinType 的 hook 只注册一次，按钮创建时只往列表里登记，
    // 否则每次打开设置页都会重复注册，累计 64 个后被 LSPosed 拒绝并抛
    // IllegalStateException，导致“规则异常：初始化设置失败”
    private val skinRefreshTargets =
        mutableListOf<Pair<WeakReference<LinearLayout>, WeakReference<ImageView>>>()
    private var skinHookRegistered = false

    private fun registerSkinChangeHook() {
        if (skinHookRegistered) return
        hookAfterMethod(
            "com.baidu.tieba.setting.more.MoreActivity",
            "onChangeSkinType", Int::class.javaPrimitiveType
        ) { _ ->
            val svgManager = XposedHelpers.callStaticMethod(
                findClass("com.baidu.tbadk.core.util.SvgManager"),
                "getInstance"
            )
            skinRefreshTargets.removeAll { it.first.get() == null || it.second.get() == null }
            skinRefreshTargets.forEach { (buttonRef, imageViewRef) ->
                val button = buttonRef.get() ?: return@forEach
                button.setBackgroundColor(getColor("CAM_X0201"))
                XposedHelpers.callMethod(
                    svgManager,
                    "setPureDrawableWithDayNightModeAutoChange",
                    imageViewRef.get(),
                    getDrawableId("icon_pure_list_arrow16_right_svg"),
                    getR("color", "CAM_X0109"),
                    null
                )
            }
        }
        skinHookRegistered = true
    }

    @SuppressLint("ClickableViewAccessibility")
    fun createButton(text: String?, tip: String?, showArrow: Boolean, l: View.OnClickListener?): LinearLayout {
        val textTipView = XposedHelpers.newInstance(
            findClass("com.baidu.tbadk.coreExtra.view.TbSettingTextTipView"),
            viewContext()
        )
        XposedHelpers.callMethod(textTipView, "setText", text)
        XposedHelpers.callMethod(textTipView, "setTip", tip)

        // 22.11 布局里主文字的 tb_textColor 皮肤属性默认是灰色，程序化创建的视图
        // 不会经过皮肤系统的页面级替换，这里显式设为与标题一致的主文字色
        getObjectField(textTipView, TextView::class.java)?.apply {
            setTextColor(getColor("CAM_X0105"))
        }

        val imageView = getObjectField(textTipView, ImageView::class.java)?.apply {
            visibility = if (showArrow) View.VISIBLE else View.GONE
        }

        val svgManager = XposedHelpers.callStaticMethod(
            findClass("com.baidu.tbadk.core.util.SvgManager"),
            "getInstance"
        )

        XposedHelpers.callMethod(
            svgManager,
            "setPureDrawableWithDayNightModeAutoChange",
            imageView,
            getDrawableId("icon_pure_list_arrow16_right_svg"),
            getR("color", "CAM_X0109"),
            null
        )

        val newButton = getObjectField(textTipView, LinearLayout::class.java)
        newButton?.apply {
            // as? 防护: 若宿主改为纯代码构建 TbSettingTextTipView(无 inflate 父级),
            // 硬转 null 会沿 init 链抛异常, 让设置页整行降级为"初始化失败"按钮
            (parent as? ViewGroup)?.removeView(this)
            l?.let { setOnClickListener(it) }
            setBackgroundColor(getColor("CAM_X0201"))

            if (showArrow) {
                setOnTouchListener { v, event ->
                    val isInside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    val backgroundColor = getColor("CAM_X0201")

                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> setBackgroundColor(
                            Color.argb(
                                128,
                                Color.red(backgroundColor),
                                Color.green(backgroundColor),
                                Color.blue(backgroundColor)
                            )
                        )
                        MotionEvent.ACTION_MOVE -> if (!isInside) setBackgroundColor(backgroundColor)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setBackgroundColor(backgroundColor)
                    }
                    false
                }
            }

            // Fix TS Preference button not changing background when skin type changed
            registerSkinChangeHook()
            imageView?.let {
                // 添加前修剪已回收条目: 列表实际只在换肤回调里清理, 用户长期不换肤时
                // 反复进出设置页会让它无界增长(每页约 20 条)
                skinRefreshTargets.removeAll { e -> e.first.get() == null || e.second.get() == null }
                skinRefreshTargets.add(WeakReference(this) to WeakReference(it))
            }
        }

        return newButton ?: throw IllegalStateException("LinearLayout not found in TbSettingTextTipView")
    }

    class PreferenceLayout(context: Context?) : LinearLayout(context) {
        init {
            orientation = VERTICAL
            // 不可对 sIdToTag 做整体 clear(包括容量触发的兜底): 设置主页(MAIN)与
            // 屏蔽/精简/扩展子页是同一个 ForbiddenForumActivity 的叠加实例, 子页
            // 打开会抹掉主页已注册的 id→key 映射, 返回主页后拨动开关将不持久化
            // (UI 变了配置没写盘)。条目仅为 Int→String(~50B), 单次进出设置页新增
            // ~17 条, 进程生命周期内无界增长的内存代价远小于误清存活映射的功能损失,
            // 故不做任何清除
        }

        fun addView(view: SwitchButtonHolder) {
            addView(view.switchButton)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    // dialogHint 仅 TYPE_DIALOG 使用(纯文本设置值的输入框提示); TYPE_SWITCH 传参
    // 处未提供, 默认值只为让其余调用点省略
    class SwitchButtonHolder(activity: Activity, text: String?, private val mKey: String, type: Int, private val dialogHint: String = "keyword_hint") {
        lateinit var bdSwitch: Switch
        lateinit var switchButton: LinearLayout

        init {
            if (exceptions.containsKey(mKey)) {
                switchButton = createButton(text, "此功能初始化失败", false) { _ ->
                    val tr = exceptions[mKey]
                    tr?.let { logBoth(it) }
                    showTbToast(Log.getStackTraceString(tr), TbToast.LENGTH_SHORT)
                }

            } else {
                bdSwitch = Switch().apply {
                    setOnSwitchStateChangeListener(SwitchStatusChangeHandler())
                }

                val bdSwitchView = bdSwitch.bdSwitch.apply {
                    layoutParams = LinearLayout.LayoutParams(width, height, 0.16f)
                    id = View.generateViewId()
                }

                when (type) {
                    TYPE_SWITCH -> {
                        // 点击监听由宿主 View 框架直接调用, 不经过任何 hook 包装层:
                        // 混淆名漂移导致 changeState 失败时不能沿宿主主线程抛出。
                        // 按偏好目标态显式 turnOn/turnOff 而非 changeState: 内层
                        // BdSwitchView 可能在自身触摸链里已自行切换过一次,
                        // changeState 会再翻一次, 导致开关"点不动"或状态来回跳
                        switchButton = createButton(text, null, false) { _ ->
                            val turnOnTarget = !getBoolean(mKey)
                            turnSafely(turnOnTarget)
                        }
                        sIdToTag[bdSwitchView.id] = "$TYPE_SWITCH$mKey"
                        if (getBoolean(mKey)) bdSwitch.turnOn() else bdSwitch.turnOff()
                    }

                    TYPE_DIALOG -> {
                        switchButton = createButton(text, null, false) { _ -> showTextDialog(activity, text, dialogHint) }
                        // 开关可直接点按: 开→关 自动清空设置值并关闭(纯文本过滤无独立
                        // "关"态, 关即无值); 关→开 无内容可开, 转发整行打开输入对话框。
                        // 行点击仍为打开对话框(编辑已有内容)。
                        // 触摸须消费: 若放行, 内层开关被直接点按时只改视觉不落盘(本类型
                        // 不注册 sIdToTag), 造成"开关显示开但过滤未生效"的错位
                        bdSwitchView.setOnTouchListener { v, event ->
                            runCatching {
                                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                    XposedHelpers.callMethod(bdSwitch.getVibrator(), "vibrate", 30L)
                                }
                            }
                            // UP 必须带落点边界判定: 触摸事件按 target 派发规则会持续送达
                            // 首个 ACTION_DOWN 的目标视图, 按住开关滑出范围再松手时 UP 照样
                            // 送达——无判定会把"误触后滑走"也当成点击, 用户整理的关键词/
                            // 用户过滤规则被无提示永久清空(宿主 BdSwitchView.onTouch 自带
                            // 边界判定, 自写监听不可丢掉这一防护)
                            val inside = event.x >= 0f && event.x <= v.width &&
                                event.y >= 0f && event.y <= v.height
                            if (event.actionMasked == MotionEvent.ACTION_UP && inside) {
                                if (getString(mKey) != null) {
                                    remove(mKey)
                                    turnSafely(false, toastOnFailure = false)
                                    Logger.log("过滤规则 [$text] 已关闭并清空")
                                } else {
                                    runCatching { switchButton.performClick() }
                                }
                            }
                            true
                        }
                        if (getString(mKey) != null) bdSwitch.turnOn() else bdSwitch.turnOff()
                    }
                }
                switchButton.addView(bdSwitchView)
            }
        }

        fun setOnButtonClickListener(l: View.OnClickListener?) {
            switchButton.setOnClickListener(l)
            // exceptions 分支(此功能初始化失败)不初始化 bdSwitch: 此时仅按钮,
            // 不挂震动触摸监听, 否则 lateinit 击穿让整页被"初始化设置失败"对话框取代
            if (!::bdSwitch.isInitialized) return
            bdSwitch.bdSwitch.setOnTouchListener { v, event ->
                // BdSwitchView 内 Vibrator 字段缺失/为 null 时跳过震动, 避免异常击穿宿主
                // 仅在 ACTION_DOWN 震动一次
                runCatching {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        XposedHelpers.callMethod(bdSwitch.getVibrator(), "vibrate", 30L)
                    }
                }
                // 消费触摸并转发为整行点击: 若返回 false, BdSwitchView 自身 onTouchEvent
                // 会直接切换并持久化, 绕过行点击的拦截逻辑(自动签到风险弹窗/
                    // 日志目录选择器/文本配置对话框), 造成"风险确认被跳过/日志假开启"
                // 仅在 ACTION_UP 转发一次: 一次触摸流含 DOWN/UP(可能含 MOVE)多个事件,
                // 逐事件 performClick 会连击多次——第一次切换开关状态后, 第二次读到的
                // 已是新状态又立即翻回, 造成"开关点了没反应/点关又弹回开"的错乱。
                // UP 带落点边界判定: 按住滑出开关范围再松手不应触发整行点击
                // (拉起 SAF 目录选择器/风险确认框等非预期弹窗), 与 TYPE_DIALOG 同口径
                val inside = event.x >= 0f && event.x <= v.width &&
                    event.y >= 0f && event.y <= v.height
                if (event.actionMasked == MotionEvent.ACTION_UP && inside) {
                    runCatching { switchButton.performClick() }
                }
                true
            }
        }

        // 设置值为纯文本(关键词/用户 ID, 用 "/" 隔开): dialogHint 指定输入框提示文案
        private fun showTextDialog(activity: Activity, title: String?, dialogHint: String) {
            // getCurrentActivity() 在页面销毁/重建阶段可能返回 null
            val currentActivity = getCurrentActivity() ?: return
            val isLightMode = isLightMode(getContext())

            val editText = EditText(currentActivity).apply {
                hint = strings[dialogHint]
                setText(getString(mKey))
                if (!isLightMode) {
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                }
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                isFallbackLineSpacing = false
                setLineSpacing(0f, 1.2f)

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dipToPx(currentActivity, 20f)
                    rightMargin = dipToPx(currentActivity, 20f)
                }
            }

            val linearLayout = LinearLayout(currentActivity).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(editText)
            }

            val builder = AlertDialog.Builder(
                currentActivity,
                getDialogTheme(getContext())
            )
                .setTitle(title)
                .setView(linearLayout)
                .setNegativeButton(activity.getString(android.R.string.cancel), null)
                .setPositiveButton(activity.getString(android.R.string.ok), null)
            // 过滤用户弹窗左下角: 主页"获取ID"按钮(ProfileUserId)的行为开关,
            // 未启用统一 ID 的用户靠该按钮从主页收录进名单
            if (mKey == "user_filter") {
                builder.setNeutralButton(
                    if (getBoolean("get_user_id")) "获取ID：开" else "获取ID：关", null
                )
            }
            val alert = builder.create()

            alert.setOnShowListener { _ ->
                    // 开关按钮仅出现在 user_filter 弹窗: 点击翻转 get_user_id 并
                    // 更新按钮文案; hooker 按启动时偏好激活, 切换后需重启贴吧生效
                    if (mKey == "user_filter") {
                        alert.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { _ ->
                            runCatching {
                                val target = !getBoolean("get_user_id")
                                putBoolean("get_user_id", target)
                                alert.getButton(AlertDialog.BUTTON_NEUTRAL).text =
                                    if (target) "获取ID：开" else "获取ID：关"
                                showTbToast(
                                    if (target) "主页获取ID按钮已开启，重启贴吧后生效"
                                    else "主页获取ID按钮已关闭，重启贴吧后生效",
                                    TbToast.LENGTH_SHORT
                                )
                                Logger.log("主页获取ID按钮已${if (target) "开启" else "关闭"}")
                            }.onFailure {
                                Logger.log("获取ID开关切换失败: $it")
                            }
                        }
                    }
                    alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { _ ->
                        // 设置值均为纯文本语义(关键词/用户 ID), 无需保存前校验
                        if (TextUtils.isEmpty(editText.text)) {
                            remove(mKey)
                            // 同 TYPE_SWITCH 默认路径: 反射漂移时降级, 不击穿宿主主线程
                            turnSafely(false, toastOnFailure = false)
                            Logger.log("过滤规则 [$title] 已清空")
                            alert.dismiss()
                        } else if (mKey == "user_filter") {
                            // 过滤用户: 保存前弹确认框, 用户确定后才落盘并发起 ID 解析
                            // (网络查询为用户明确触发); 以输入框当前内容为准
                            showResolveConfirmDialog(currentActivity, editText.text.toString()) {
                                putString(mKey, editText.text.toString())
                                turnSafely(true, toastOnFailure = false)
                                Logger.log("过滤规则 [$title] 已保存")
                                alert.dismiss()
                            }
                        } else {
                            putString(mKey, editText.text.toString())
                            turnSafely(true, toastOnFailure = false)
                            Logger.log("过滤规则 [$title] 已保存")
                            alert.dismiss()
                        }
                    }
                }

            alert.show()
            fixAlertDialogWidth(alert)
            alert.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

            editText.imeOptions = EditorInfo.IME_ACTION_DONE
            editText.setOnEditorActionListener { _, actionId: Int, event: KeyEvent? ->
                if (actionId == EditorInfo.IME_ACTION_DONE || event != null
                    && event.keyCode == KeyEvent.KEYCODE_ENTER
                ) {
                    alert.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    true
                } else {
                    false
                }
            }
            editText.requestFocus()
        }

        // 过滤用户解析结果窗口: 逐条展示名单当前映射状态(统一 ID/用户名/头像ID
        // 各按其已建立的映射), 点确定=保存名单+对未映射数字 ID 发起解析(用户明确
        // 触发), 随后执行 onConfirmed(关闭输入对话框)。用户名/头像ID 类条目不发
        // 网络查询——靠被动收割(打开其发布/回复过的帖子即建立), 窗口内注明
        private fun showResolveConfirmDialog(
            activity: Activity,
            rawIds: String,
            onConfirmed: () -> Unit
        ) {
            val currentActivity = getCurrentActivity() ?: return
            val statuses = runCatching {
                gm.tieba.tabswitch.hooker.eliminate.UserFilter.listEntryStatuses(rawIds)
            }.getOrDefault(emptyList())

            val message = buildString {
                if (statuses.isEmpty()) {
                    append("名单为空")
                } else {
                    statuses.forEachIndexed { i, s ->
                        if (i > 0) append('\n')
                        append(s.kind).append(" ").append(s.entry).append("：")
                        if (s.mappedId != null) {
                            append("已解析 ✓")
                        } else if (s.kind == "统一ID" || s.kind == "头像ID") {
                            // 统一 ID 与 portrait 都有主动查询通道(309702/303012)
                            append("未解析，确定后发起查询")
                        } else {
                            append("未收割，打开其帖子后自动生效")
                        }
                    }
                    val pendingResolvable = statuses.count {
                        it.mappedId == null && (it.kind == "统一ID" || it.kind == "头像ID")
                    }
                    if (pendingResolvable > 0) append("\n\n确定后将发起 $pendingResolvable 个查询")
                }
            }

            val alert = AlertDialog.Builder(
                currentActivity,
                getDialogTheme(getContext())
            )
                .setTitle("解析用户ID")
                .setMessage(message)
                .setNegativeButton(activity.getString(android.R.string.cancel), null)
                .setPositiveButton(activity.getString(android.R.string.ok), null)
                .create()
                .apply {
                    setOnShowListener {
                        getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { _ ->
                            // 用户确定=明确触发(允许的一次性查询): 保存 + 对未映射的
                            // 统一ID/portrait 发起解析, toast 反馈后直接关闭
                            val dispatched = runCatching {
                                gm.tieba.tabswitch.hooker.eliminate.UserFilter.resolveIds(rawIds)
                            }.getOrDefault(-1)
                            showTbToast(
                                when {
                                    dispatched > 0 -> "已保存，并发起 $dispatched 个用户ID解析"
                                    else -> "已保存"
                                },
                                TbToast.LENGTH_SHORT
                            )
                            dismiss()
                            onConfirmed()
                        }
                    }
                    show()
                    fixAlertDialogWidth(this)
                }
            alert.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }

        private class SwitchStatusChangeHandler : InvocationHandler {
            // 经 Proxy 注入宿主回调: 此处异常沿宿主主线程调用栈直接击穿宿主
            // (XposedBridge 只兜 hook 回调, 不兜 Proxy 调用), 必须整体防护
            override fun invoke(proxy: Any, method: Method, args: Array<Any>?): Any? {
                try {
                    val view = args?.getOrNull(0) as? View ?: return null
                    val tag = sIdToTag[view.id]
                    tag?.let {
                        if (it.isNotEmpty() && it.substring(0, 1).toIntOrNull() == TYPE_SWITCH) {
                            val key = it.substring(1)
                            val state = args.getOrNull(1)?.toString() ?: return null
                            putBoolean(key, state == "ON")
                            Logger.log("开关切换 [$key] -> $state")
                        }
                    }
                } catch (tr: Throwable) {
                    Logger.log("SwitchStatusChangeHandler failed: $tr")
                }
                return null
            }
        }

        companion object {
            const val TYPE_SWITCH: Int = 0
            const val TYPE_DIALOG: Int = 1
            val sIdToTag: MutableMap<Int, String> = HashMap()
        }
    }
}

