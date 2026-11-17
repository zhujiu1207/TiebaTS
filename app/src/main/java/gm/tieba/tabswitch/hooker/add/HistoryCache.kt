package gm.tieba.tabswitch.hooker.add

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import android.widget.LinearLayout
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.Constants.strings
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.util.dipToPx
import gm.tieba.tabswitch.util.findFirstMethodByExactType
import gm.tieba.tabswitch.util.fixAlertDialogWidth
import gm.tieba.tabswitch.util.getCurrentActivity
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.util.getObjectField
import gm.tieba.tabswitch.util.isLightMode
import gm.tieba.tabswitch.widget.NavigationBar

class HistoryCache : XposedContext(), IHooker {

    // 三字段跨线程读写(对话框主线程改写 / 历史数据灌入钩子线程读取), 必须 volatile
    // 保证可见性
    @Volatile
    private var mInput = ""

    // "/" 隔开关键词的切分缓存: 该钩子历史页每次数据灌入都触发, 按原始串缓存避免重复切分。
    // 用单个不可变 Pair 快照(键与切分结果原子成对发布), 替代原先双字段分别写:
    // 多线程同时进入未命中分支时交错写会把"键 a 配旧名单"这类错配永久留在缓存里
    @Volatile
    private var mCached: Pair<String, List<String>>? = null

    // 关键词预转小写, 匹配时文本侧整串转小写一次; 空输入返回 null 表示不过滤
    private fun currentKeywords(): List<String>? {
        // 快照一次: 独立读 mInput 与 mCached 之间 TextWatcher 可能改写它, 出现
        // "旧名单配新输入"的判定。单次快照保证切分与键一致, 至多滞后一次写入
        val input = mInput
        mCached?.let { if (it.first == input) return it.second.ifEmpty { null } }
        val keywords = input.split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        mCached = input to keywords
        return keywords.ifEmpty { null }
    }

    override fun key(): String {
        return "history_cache"
    }

    override fun hook() {
        hookAfterMethod(
            "com.baidu.tieba.myCollection.history.PbHistoryActivity",
            "onCreate", Bundle::class.java
        ) { param ->
            val activity = param.thisObject as Activity
            if (param.args[0] == null) {
                mInput = ""
            }
            // recreate() 会再次触发 onCreate: 导航栏已有"搜索"按钮时不再追加,
            // 避免每次应用关键词过滤后按钮累积叠加
            val navigationBar = NavigationBar(param.thisObject)
            if (!navigationBar.hasTextButton("搜索")) {
                navigationBar.addTextButton("搜索") { showKeywordDialog(activity) }
            }
        }

        hookBeforeMethod(
            findFirstMethodByExactType("com.baidu.tieba.myCollection.history.PbHistoryActivity", MutableList::class.java)
        ) { param ->
            val keywords = currentKeywords() ?: return@hookBeforeMethod

            val historyList = param.args[0] as? List<*> ?: return@hookBeforeMethod
            // 复制式过滤: 只保留关键词命中的历史项; 取字段异常/字段全不可读时保留该条目
            // (按下标兜底会取到 cartoonId/chapterId 等非字符串字段, 全 null 后无差别
            // 过滤会把整个历史页清成空白, 比不兜底更糟)
            param.args[0] = historyList.filter { history ->
                history == null || runCatching {
                    val strings = arrayOf(
                        XposedHelpers.getObjectField(history, "forumName") as? String,
                        XposedHelpers.getObjectField(history, "threadName") as? String
                    )
                    strings.all { it == null } ||
                        strings.filterNotNull().any { text ->
                            val lower = text.lowercase()
                            keywords.any { lower.contains(it) }
                        }
                }.getOrDefault(true)
            }
        }
    }

    private fun showKeywordDialog(activity: Activity) {
        val currentActivity = getCurrentActivity() ?: return
        val isLightMode = isLightMode(getContext())

        // 多行输入框需子类化 EditText 修正 IME 动作键: 框架在建立输入连接时对多行
        // 字段强制置位 NO_ENTER_ACTION(回车=换行), view 层面设置 imeOptions 无效,
        // 只能在 onCreateInputConnection 里于 super 之后清标志并指定搜索动作
        // (物理键盘回车仍为换行)
        val editText = object : EditText(currentActivity) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val connection = super.onCreateInputConnection(outAttrs)
                outAttrs.imeOptions = outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv() or
                        EditorInfo.IME_ACTION_SEARCH
                return connection
            }
        }.apply {
            setHint(strings["history_hint"])
            setText(mInput)
            if (!isLightMode) {
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
            }
            setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            setFallbackLineSpacing(false)
            setLineSpacing(0f, 1.2f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) {
                    mInput = s.toString()
                }
            })
        }

        val linearLayout = LinearLayout(currentActivity).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dipToPx(currentActivity, 20f)
            rightMargin = dipToPx(currentActivity, 20f)
        }
        editText.setLayoutParams(layoutParams)
        linearLayout.addView(editText)

        val currInput = mInput
        val alert = AlertDialog.Builder(
            currentActivity,
            getDialogTheme(isLightMode)
        )
            .setTitle("搜索")
            .setView(linearLayout)
            .setOnCancelListener { mInput = currInput }
            .setNegativeButton(activity.getString(android.R.string.cancel)) { _, _ -> mInput = currInput }
            .setPositiveButton(activity.getString(android.R.string.ok), null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        // 关键词无语法错误, 无需保存前校验(正则模式时此处曾编译校验)
                        dismiss()
                        activity.recreate()
                    }
                }
                show()
                fixAlertDialogWidth(this)
            }

        alert.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        editText.apply {
            setOnEditorActionListener { _, actionId, event ->
                when {
                    actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER -> {
                        alert.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                        true
                    }
                    else -> false
                }
            }
            selectAll()
            requestFocus()
        }
    }
}
