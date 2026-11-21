package gm.tieba.tabswitch.hooker

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.BuildConfig
import gm.tieba.tabswitch.Constants.strings
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.dao.Preferences.getIsAutoSignEnabled
import gm.tieba.tabswitch.dao.Preferences.getIsEULAAccepted
import gm.tieba.tabswitch.dao.Preferences.putAutoSignEnabled
import gm.tieba.tabswitch.dao.Preferences.putBoolean
import gm.tieba.tabswitch.dao.Preferences.putEULAAccepted
import gm.tieba.tabswitch.hooker.TSPreferenceHelper.PreferenceLayout
import gm.tieba.tabswitch.hooker.TSPreferenceHelper.SwitchButtonHolder
import gm.tieba.tabswitch.hooker.TSPreferenceHelper.createButton
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.getTbVersion
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.hooker.eliminate.CommentToolbar
import gm.tieba.tabswitch.hooker.eliminate.HomeTopTab
import gm.tieba.tabswitch.util.Logger
import gm.tieba.tabswitch.util.dipToPx
import gm.tieba.tabswitch.util.getColor
import gm.tieba.tabswitch.util.getObjectField
import gm.tieba.tabswitch.hooker.extra.LowSpecMode
import gm.tieba.tabswitch.hooker.extra.TraceChecker
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.util.fixAlertDialogWidth
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.widget.NavigationBar
import gm.tieba.tabswitch.widget.TbToast
import gm.tieba.tabswitch.widget.TbToast.Companion.showTbToast
import java.util.Locale

class TSPreference : XposedContext(), IHooker, Obfuscated {

    override fun key(): String {
        return "ts_pref"
    }

    override fun matchers(): List<Matcher> {
        // 22.11 移除了 SecretSettingActivity，设置页改为劫持 ForbiddenForumActivity + 自建布局，不再依赖 DexKit 规则
        return emptyList()
    }

    override fun hook() {
        hookBeforeMethod(
            Dialog::class.java,
            "dismissDialog"
        ) { param ->
            val dialog = param.thisObject as? Dialog
            dialog?.takeIf { it.isShowing }?.window?.currentFocus?.let { view ->
                val imm = view.context.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.rootView.windowToken, 0)
            }
        }

        hookAfterMethod(
            "com.baidu.tieba.setting.more.MoreActivity",
            "onCreate", Bundle::class.java
        ) { param ->
            val activity = param.thisObject as Activity
            Logger.log("MoreActivity.onCreate hooked, injecting entry")
            try {
                val contentView = activity.findViewById<ViewGroup>(android.R.id.content)
                val parent = contentView.getChildAt(0) as RelativeLayout
                val scroll = parent.getChildAt(0) as ScrollView
                val containerView = scroll.getChildAt(0) as LinearLayout
                Logger.log("MoreActivity view chain ok, children=${containerView.childCount}")
                // 硬编码下标 11 在子视图不足时 IndexOutOfBounds: 收敛到容器末尾
                containerView.addView(
                    createButton(MAIN, null, true) { startRootPreferenceActivity(activity) },
                    minOf(11, containerView.childCount)
                )
                Logger.log("TS entry added")
            } catch (tr: Throwable) {
                Logger.log("inject entry FAILED")
                Logger.log(tr)
            }
        }

        hookAfterMethod(
            PROXY_ACTIVITY,
            "onCreate", Bundle::class.java
        ) { param ->
            val activity = param.thisObject as Activity
            val proxyPage0 = activity.intent.getStringExtra("proxyPage") ?: return@hookAfterMethod
            try {
                val navigationBar = NavigationBar(activity)
                installProxyContent(activity)
                when (proxyPage0) {
                    MAIN -> proxyPage(activity, navigationBar, MAIN, createRootPreference(activity))
                    BLOCK_MENU -> proxyPage(activity, navigationBar, BLOCK_MENU, createBlockMenuPreference(activity))
                    SIMPLIFY_MENU -> proxyPage(activity, navigationBar, SIMPLIFY_MENU, createSimplifyMenuPreference(activity))
                    EXTEND_MENU -> proxyPage(activity, navigationBar, EXTEND_MENU, createExtendMenuPreference(activity))
                    TRACE -> proxyPage(activity, navigationBar, TRACE, createHidePreference(activity))
                }

            } catch (tr: Throwable) {
                val messages = ArrayList<String?>().apply {
                    add(strings["exception_init_preference"])
                    add(
                        "贴吧版本：%s, 模块版本：%d".format(Locale.CHINA, getTbVersion(getContext()), BuildConfig.VERSION_CODE)
                    )
                    add(Log.getStackTraceString(tr))
                }

                val message = TextUtils.join("\n", messages)
                logBoth(message)

                val alert = AlertDialog.Builder(
                    activity,
                    getDialogTheme(getContext())
                )
                    .setTitle("规则异常")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(activity.getString(android.R.string.ok)) { _, _ -> activity.finish() }
                    .create()

                alert.show()
                fixAlertDialogWidth(alert)
            }
        }

        // 日志目录选择器的结果回调: 沿继承链找到声明 onActivityResult 的类再 hook
        var cls: Class<*>? = findClass(PROXY_ACTIVITY)
        while (cls != null && cls != Any::class.java) {
            val method = try {
                cls.getDeclaredMethod(
                    "onActivityResult",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java
                )
            } catch (ignored: NoSuchMethodException) {
                null
            }
            if (method != null) {
                hookAfterMethod(method) { param ->
                    val handler = sLogDirHandler ?: return@hookAfterMethod
                    // 继承链可能最终挂到基类 onActivityResult(对宿主全部 Activity 生效):
                    // 限定目标类, 避免其他 Activity 以相同 requestCode 收结果时错误消费回调
                    if (param.thisObject !is Activity ||
                        param.thisObject.javaClass.name != PROXY_ACTIVITY
                    ) return@hookAfterMethod
                    if ((param.args[0] as? Int) != REQUEST_LOG_DIR) return@hookAfterMethod
                    sLogDirHandler = null
                    try {
                        handler(param.args[2] as? Intent)
                    } catch (tr: Throwable) {
                        Logger.log("处理目录选择结果失败")
                        Logger.log(tr)
                    }
                }
                break
            }
            cls = cls.superclass
        }

        // sLogDirHandler 是静态引用且强持有 activity/logSwitch: SAF 选择器不返回时
        // (异常/进程切换) onActivityResult 不会触发, 必须在页面销毁时清空, 否则泄漏 Activity
        var destroyCls: Class<*>? = findClass(PROXY_ACTIVITY)
        while (destroyCls != null && destroyCls != Any::class.java) {
            val destroyMethod = try {
                destroyCls.getDeclaredMethod("onDestroy")
            } catch (ignored: NoSuchMethodException) {
                null
            }
            if (destroyMethod != null) {
                hookAfterMethod(destroyMethod) { param ->
                    // 沿继承链可能最终挂到 android.app.Activity.onDestroy, 限定目标类
                    if (param.thisObject is Activity &&
                        param.thisObject.javaClass.name == PROXY_ACTIVITY
                    ) sLogDirHandler = null
                }
                break
            }
            destroyCls = destroyCls.superclass
        }
    }

    private fun installProxyContent(activity: Activity) {
        val navigationBarView = getObjectField(activity, "com.baidu.tbadk.core.view.NavigationBar") as View
        (navigationBarView.parent as? ViewGroup)?.removeView(navigationBarView)
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply {
            addView(
                container,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                navigationBarView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        activity.setContentView(root)
    }

    private fun proxyPage(activity: Activity, navigationBar: NavigationBar, title: String, preferenceLayout: LinearLayout) {
        navigationBar.apply {
            setTitleText(title)
            setCenterTextTitle("")
        }

        val contentView = activity.findViewById<ViewGroup>(android.R.id.content)
        val parent = contentView.getChildAt(0) as LinearLayout
        val mainScroll = parent.getChildAt(1) as ScrollView
        val containerView = mainScroll.getChildAt(0) as LinearLayout

        containerView.apply {
            removeAllViews()
            addView(preferenceLayout)
        }
    }

    // 在"运行日志"行标题右侧插入"导出崩溃日志"入口。
    // 布局依据(宿主 TbSettingTextTipView, aapt2 dump 22.13 r/NTp): 根 FrameLayout
    // 内的行 LinearLayout 首个子项是 width=0dp/weight=1 的标题区(内含标题
    // TextView), tip/箭头/开关依次排在它后面。入口必须放进这个权重区——
    // 若直接加进行 LinearLayout, WRAP_CONTENT 按钮会压缩权重区的可用空间,
    // 开关的 0.16f 权重随之缩水, 实测开关被推出屏幕右缘
    // 行内嵌套可点击 View 会与整行 OnClickListener 竞争触摸: 内层 View 自身
    // 的点击监听优先消费, 恰好实现"点文字导出、点行内其它区域不误触"
    private fun addExportNativeLogButton(activity: Activity, holder: SwitchButtonHolder) {
        try {
            val row = holder.switchButton
            // 行 LinearLayout 的首个 child = weight=1 标题区(文本变化时宽度自适应)
            if (row.childCount < 1) return
            val titleArea = row.getChildAt(0) as? ViewGroup ?: return
            // 色名兜底: CAM_X0302(#4070FF)=宿主标准主题蓝, 深色模式经皮肤系统
            // 自动切换; 此前 CAM_X0195 宿主资源表不存在(NotFoundException 被外层
            // catch 吞掉导致按钮整体消失), CAM_X0108 实际是灰(#8f8e94)。
            // 解析失败再退回 tip 灰
            val btnColor = try {
                getColor("CAM_X0302")
            } catch (ignored: Throwable) {
                getColor("CAM_X0109")
            }
            val exportBtn = android.widget.TextView(activity).apply {
                text = "导出崩溃日志"
                textSize = 13f
                setTextColor(btnColor)
                setPadding(0, 0, dipToPx(activity, 6f), 0)
                // 单行省略: 标题区宽度过窄时不再挤压, 交给父容器权重机制
                maxLines = 1
                // 文字右对齐+垂直居中: 按钮占据标题区全部剩余空间(width=0+weight=1),
                // 文字贴到权重区右缘 = 行内箭头左侧, 即"运行日志与箭头之间的空白区"
                gravity = android.view.Gravity.RIGHT or android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { _ ->
                    exportNativeLog(activity)
                }
            }
            // 追加到标题区末尾(标题 TextView 与隐藏 ImageView 之后):
            // weight=1 吸收标题与箭头之间的全部剩余宽度
            titleArea.addView(exportBtn)
        } catch (tr: Throwable) {
            // 入口构建失败只损失导出功能, 不影响设置页其余内容
            Logger.log("导出崩溃日志入口构建失败: $tr")
        }
    }

    // 原生日志导出到"运行日志"所选的同一目录(TiebaTS.txt 旁边生成
    // TiebaTS-native.txt), 用户在文件管理器即可取到, 不走分享面板。
    // 失败信息明确到动作: 未配置目录→先开运行日志; 无记录→本身也是排查结论
    private fun exportNativeLog(activity: Activity) {
        try {
            val dir = Logger.exportNativeLogToLogDir(getContext())
            showTbToast("已导出到 $dir/TiebaTS-native.txt", TbToast.LENGTH_LONG)
        } catch (tr: IllegalStateException) {
            // 预期内的未就绪场景: 未配置目录/暂无原生记录, 提示而非报错
            showTbToast(tr.message, TbToast.LENGTH_SHORT)
        } catch (tr: Throwable) {
            Logger.log("导出崩溃日志失败: $tr")
            showTbToast("导出失败: ${tr.message}", TbToast.LENGTH_SHORT)
        }
    }

    // 调起系统文件管理器的目录选择器(SAF), 结果在 onActivityResult hook 中回调
    private fun launchLogDirPicker(activity: Activity, onPicked: (String) -> Unit) {
        sLogDirHandler = { data ->
            val uri = data?.data
            if (uri == null) {
                showTbToast("未选择文件夹", TbToast.LENGTH_SHORT)
            } else {
                val dir = Logger.setLogDir(getContext(), uri)
                if (dir == null) {
                    showTbToast("日志文件夹初始化失败，请换一个文件夹重试", TbToast.LENGTH_LONG)
                } else {
                    onPicked(dir)
                }
            }
        }
        try {
            activity.startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                    // 再次打开时定位到上次选择的目录
                    Logger.getLogTreeUri()?.let { putExtra("android.provider.extra.INITIAL_URI", it) }
                },
                REQUEST_LOG_DIR
            )
        } catch (tr: Throwable) {
            sLogDirHandler = null
            Logger.log("打开目录选择器失败")
            Logger.log(tr)
            showTbToast("无法打开文件管理器", TbToast.LENGTH_SHORT)
        }
    }

    private fun startRootPreferenceActivity(activity: Activity) {
        if (!getIsEULAAccepted()) {
            val stringBuilder = StringBuilder().apply {
                append(strings["EULA"])
                if (isModuleBetaVersion) {
                    append("\n\n").append(strings["dev_tip"])
                }
            }

            val alert = AlertDialog.Builder(
                activity,
                getDialogTheme(getContext())
            )
                .setTitle("使用协议")
                .setMessage(stringBuilder.toString())
                .setNegativeButton(activity.getString(android.R.string.cancel), null)
                .setPositiveButton(activity.getString(android.R.string.ok)) { _, _ ->
                    putEULAAccepted()
                    startRootPreferenceActivity(activity)
                }
                .create()

            alert.show()
            fixAlertDialogWidth(alert)

        } else {
            launchProxyPage(activity, MAIN)
        }
    }

    // 统一入口: 打开模块设置代理页。PROXY_ACTIVITY 是硬编码的宿主 Activity 类名,
    // 宿主移除/改名时裸 startActivity 抛 ActivityNotFoundException 会击穿点击监听崩宿主,
    // 这里降级为提示; 页面缺失时 TSPreference.hook 的 onCreate 分发保持原样
    private fun launchProxyPage(activity: Activity, page: String) {
        try {
            activity.startActivity(Intent().apply {
                setClassName(activity, PROXY_ACTIVITY)
                putExtra("proxyPage", page)
            })
        } catch (tr: Throwable) {
            Logger.log("打开设置页失败($page): $tr")
            showTbToast("无法打开设置页，贴吧版本可能不受支持", TbToast.LENGTH_SHORT)
        }
    }

    private fun createRootPreference(activity: Activity): LinearLayout {
        val preferenceLayout = PreferenceLayout(activity)

        preferenceLayout.addView(SwitchButtonHolder(activity, "广告屏蔽", "purge", SwitchButtonHolder.TYPE_SWITCH))

        // 运行日志: 开启时沿用既有目录或调起 SAF 选择器, 关闭即取消记录。
        // 一律按偏好状态显式 turnOn/turnOff 而非 changeState: 内层 BdSwitchView
        // 可能在自身触摸链里已经自行切换过一次, changeState 会再翻一次, 造成状态错乱
        val logSwitch = SwitchButtonHolder(activity, "运行日志", "log", SwitchButtonHolder.TYPE_SWITCH)
        logSwitch.setOnButtonClickListener { _ ->
            if (getBoolean("log")) {
                // 点击监听由宿主 View 框架直接调用, 不经过 hook 包装层, 必须自防护。
                // 显式落盘 false: 不依赖 BdSwitchView 状态回调是否随 turnOff 触发,
                // 确保开关真正关闭且保持关闭, Logger.isEnabled() 立即失效
                // (turnSafely 失败时返回 false, 不落盘, 与原"turnOff 失败则不持久化"一致)
                if (logSwitch.turnSafely(false)) putBoolean("log", false)
            } else {
                // 每次开启都调起 SAF 选择器, 由用户选择日志存放文件夹;
                // 选回同一目录时 Logger.setLogDir 会沿用现有日志文件不归档
                launchLogDirPicker(activity) { dir ->
                    enableLogSwitch(logSwitch, dir)
                }
            }
        }
        preferenceLayout.addView(logSwitch)

        // 导出原生日志: native 崩溃记录(TiebaTS-native.txt)困在 app 私有目录,
        // 无 root 用户拿不出来, 而"反馈说闪退"时它恰是唯一的 native 崩溃证据源
        // (Java 层 CrashLogHandler 只覆盖未捕获异常, 覆盖不了信号类崩溃)。
        // 入口放在"运行日志"行标题与开关之间的空白区, 读尾部经 SAF 分享导出,
        // 与日志开关状态无关(崩溃证据不应受记录开关约束, 与 logSync 豁免同理)
        addExportNativeLogButton(activity, logSwitch)

        // 低配模式: 关闭状态点击弹出配置弹窗(与"自定义评论图标"同款)自定义各子项,
        // 开启状态点击直接关闭; 主键状态 = 任一子项开启, 由弹窗保存时统一落盘;
        // hooker/extra/LowSpecMode 在启动时按主键决定是否注册, 已注册时改子项即时生效。
        // 直接点按开关的拦截由 setOnButtonClickListener 的触摸转发承载
        // (TSPreferenceHelper: 消费触摸 + ACTION_UP 转发 performClick), 无需额外处理
        val lowSpecSwitch = SwitchButtonHolder(activity, "低配模式", "low_spec_mode", SwitchButtonHolder.TYPE_SWITCH)
        lowSpecSwitch.setOnButtonClickListener { _ ->
            if (getBoolean("low_spec_mode")) {
                // 开启状态: 点击直接关闭功能, 不出弹窗
                putBoolean("low_spec_mode", false)
                lowSpecSwitch.turnSafely(false)
            } else {
                // 关闭状态: 点击出弹窗, 保存后按"任一子项开启"落盘并同步视觉
                LowSpecMode.showConfigDialog(activity) { anyOn ->
                    lowSpecSwitch.turnSafely(anyOn)
                }
            }
        }
        preferenceLayout.addView(lowSpecSwitch)

        preferenceLayout.addView(createButton(BLOCK_MENU, null, true) { _ ->
            launchProxyPage(activity, BLOCK_MENU)
        })
        preferenceLayout.addView(createButton(SIMPLIFY_MENU, null, true) { _ ->
            launchProxyPage(activity, SIMPLIFY_MENU)
        })

        preferenceLayout.addView(createButton(EXTEND_MENU, null, true) { _ ->
            launchProxyPage(activity, EXTEND_MENU)
        })

        preferenceLayout.addView(createButton("Git仓库", null, true) { _ ->
            try {
                activity.startActivity(Intent().apply {
                    setAction("android.intent.action.VIEW")
                    setData(Uri.parse("https://github.com/zhujiu1207/TiebaTS"))
                })
            } catch (tr: Throwable) {
                // 与 TG群 同款降级: 无浏览器等场景提示, 不击穿点击监听
                showTbToast("未找到可打开链接的应用", TbToast.LENGTH_SHORT)
            }
        })

        preferenceLayout.addView(createButton("TG群", "及时获取更新", true) { _ ->
            try {
                activity.startActivity(Intent().apply {
                    setAction("android.intent.action.VIEW")
                    setData(Uri.parse("https://t.me/+S93Y8WbDEwczYjU1"))
                })
            } catch (tr: Throwable) {
                // 无浏览器等场景降级提示, 不击穿点击监听
                showTbToast("未找到可打开链接的应用", TbToast.LENGTH_SHORT)
            }
        })

        preferenceLayout.addView(
            createButton("版本", "%s".format(Locale.CHINA, BuildConfig.VERSION_NAME), true) { _ ->
            })
        return preferenceLayout
    }

    private fun createSimplifyMenuPreference(activity: Activity): LinearLayout {
        val preferenceLayout = PreferenceLayout(activity)
        preferenceLayout.addView(SwitchButtonHolder(activity, "净化进吧", "purge_enter", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "净化我的", "purge_my", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "折叠吧内置顶区", "fold_top_card_view", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "只推荐已关注的吧", "follow_filter", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏吧页面签到弹窗", "hide_sign_popup", SwitchButtonHolder.TYPE_SWITCH))
        // 自定义隐藏顶部Tab: 直播/有料/关注/三角洲/S16 等 tab 名称随宿主热更新轮换,
        // 不再逐个硬编码开关(原 hide_live/hide_material/hide_follow/hide_delta/hide_s16
        // 已迁移合并); 关闭状态点击弹窗实时列出当前Tab栏条目勾选隐藏, 开启状态直接关闭;
        // 主键 = 存在已选名称, 由弹窗保存时统一落盘(HomeTopTab.showConfigDialog)
        val homeTopTabSwitch = SwitchButtonHolder(activity, "隐藏顶部Tab", "home_top_tab_hide", SwitchButtonHolder.TYPE_SWITCH)
        homeTopTabSwitch.setOnButtonClickListener { _ ->
            if (getBoolean("home_top_tab_hide")) {
                // 开启状态: 点击直接关闭功能, 不出弹窗
                putBoolean("home_top_tab_hide", false)
                homeTopTabSwitch.turnSafely(false)
            } else {
                // 关闭状态: 点击出弹窗, 保存后按"存在已选名称"落盘并同步视觉
                HomeTopTab.showConfigDialog(activity) { anyOn ->
                    homeTopTabSwitch.turnSafely(anyOn)
                }
            }
        }
        preferenceLayout.addView(homeTopTabSwitch)
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏游戏手柄", "hide_gamepad", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏侧边栏", "hide_sidebar", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "首页搜索栏固定为放大镜", "home_search_icon", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏消息页群聊横幅", "purge_chat_float", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏首页", "home_recommend", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏进吧", "enter_forum", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏发帖", "write_thread", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏消息", "im_message", SwitchButtonHolder.TYPE_SWITCH))

        return preferenceLayout
    }

    private fun createExtendMenuPreference(activity: Activity): LinearLayout {
        val preferenceLayout = PreferenceLayout(activity)

        val autoSign = SwitchButtonHolder(activity, "自动签到", "auto_sign", SwitchButtonHolder.TYPE_SWITCH)
        autoSign.setOnButtonClickListener { _ ->
            if (!getIsAutoSignEnabled()) {
                val alert = AlertDialog.Builder(
                    activity,
                    getDialogTheme(getContext())
                )
                    .setTitle("提示")
                    .setMessage("这是一个需要网络请求并且有封号风险的功能，您需要自行承担使用此功能的风险，请谨慎使用！")
                    .setNegativeButton(activity.getString(android.R.string.cancel), null)
                    .setPositiveButton(activity.getString(android.R.string.ok)) { _, _ ->
                        putAutoSignEnabled()
                        // 同 TYPE_SWITCH 默认路径: 反射漂移时降级提示, 不击穿宿主主线程
                        autoSign.turnSafely(true)
                    }
                    .create()
                alert.show()
                fixAlertDialogWidth(alert)

            } else {
                autoSign.turnSafely()
            }
        }
        preferenceLayout.addView(autoSign)

        // 自动打开签到界面: 无网络请求/无封号风险(仅拉起宿主一键签到页), 开启时
        // 弹提示说明触发时机(仅每天首次启动贴吧), 关闭走默认切换
        val autoOpenSign = SwitchButtonHolder(activity, "自动打开签到界面", "auto_open_sign", SwitchButtonHolder.TYPE_SWITCH)
        autoOpenSign.setOnButtonClickListener { _ ->
            if (!getBoolean("auto_open_sign")) {
                val alert = AlertDialog.Builder(
                    activity,
                    getDialogTheme(getContext())
                )
                    .setTitle("提示")
                    .setMessage("开启后每天首次启动贴吧时自动打开签到界面，当天后续启动不会重复弹出。")
                    .setNegativeButton(activity.getString(android.R.string.cancel), null)
                    .setPositiveButton(activity.getString(android.R.string.ok)) { _, _ ->
                        putBoolean("auto_open_sign", true)
                        // 同自动签到的确认弹窗路径: 反射漂移时降级提示, 不击穿宿主主线程
                        autoOpenSign.turnSafely(true)
                    }
                    .create()
                alert.show()
                fixAlertDialogWidth(alert)
            } else {
                autoOpenSign.turnSafely()
            }
        }
        preferenceLayout.addView(autoOpenSign)

        // 自定义评论图标: 打开开关先弹配置弹窗, 保存后才开启并生效
        val commentToolbar = SwitchButtonHolder(activity, "自定义评论图标", "comment_toolbar", SwitchButtonHolder.TYPE_SWITCH)
        commentToolbar.setOnButtonClickListener { _ ->
            if (!getBoolean("comment_toolbar")) {
                CommentToolbar.showConfigDialog(activity) {
                    commentToolbar.turnSafely(true)
                }
            } else {
                commentToolbar.turnSafely()
            }
        }
        preferenceLayout.addView(commentToolbar)

        preferenceLayout.addView(SwitchButtonHolder(activity, "消息起始页面改为通知", "msg_center_tab", SwitchButtonHolder.TYPE_SWITCH))

        preferenceLayout.addView(SwitchButtonHolder(activity, "吧页面起始页面改为最新", "frs_tab", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "一键抽奖", "one_key_lottery", SwitchButtonHolder.TYPE_SWITCH))

        val originSrcOnlyWifiButton = SwitchButtonHolder(activity, "自动查看原图仅WiFi下生效", "origin_src_only_wifi", SwitchButtonHolder.TYPE_SWITCH)
        val originSrcButton = SwitchButtonHolder(activity, "自动查看原图", "origin_src", SwitchButtonHolder.TYPE_SWITCH)
        originSrcButton.setOnButtonClickListener { _ ->
            originSrcButton.turnSafely()
            originSrcOnlyWifiButton.switchButton.visibility = if (getBoolean("origin_src")) View.VISIBLE else View.GONE
        }
        originSrcOnlyWifiButton.switchButton.visibility = if (getBoolean("origin_src")) View.VISIBLE else View.GONE

        preferenceLayout.addView(originSrcButton)
        preferenceLayout.addView(originSrcOnlyWifiButton)

        preferenceLayout.addView(SwitchButtonHolder(activity, "浏览历史增加搜索", "history_cache", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "长按下载保存全部图片", "save_images", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "楼中楼时间序更改", "sub_pb_latest_sort", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "弹窗自由复制", "select_clipboard", SwitchButtonHolder.TYPE_SWITCH))

        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏小红点", "red_tip", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "禁用更新提示", "remove_update", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "禁用帖子手势", "forbid_gesture", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "禁止检测通知开启状态", "notification_detect", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "禁止首页自动刷新", "auto_refresh", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(createButton(TRACE, "希望有一天不再需要贴吧TS", true) { _ ->
            launchProxyPage(activity, TRACE)
        })

        return preferenceLayout
    }

    // 持久化 log 开关、写会话头并点亮开关。先持久化再写日志: Logger.log 写入前
    // 检查 isEnabled(), turnOn 的持久化发生在这两行之后会让会话头永远落不了盘
    private fun enableLogSwitch(logSwitch: SwitchButtonHolder, dir: String) {
        putBoolean("log", true)
        Logger.logSessionStart()
        Logger.log("日志功能已开启, 保存到: $dir")
        logSwitch.turnSafely(true)
        // 日志写入前实时检查 log 开关且目录已落盘, 开启即生效, 无需重启
        showTbToast("日志将保存到\n$dir", 5000)
    }

    private fun createBlockMenuPreference(activity: Activity): LinearLayout {
        val preferenceLayout = PreferenceLayout(activity)
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽首页视频贴", "purge_video", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽首页求助贴", "purge_help_post", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽首页回复贴", "purge_reply_post", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽热点与热点TOP", "purge_hot_post", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽首页直播贴", "purge_live_post", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽首页悬赏贴", "purge_bounty_post", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽首页游戏推广贴", "purge_game_promo_post", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽首页带货贴", "purge_goods_post", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽首页打分贴", "purge_score_post", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽可能感兴趣的吧", "purge_recommend_forum", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽小卖部", "retail_store", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽消息页邀您回答与吧广播", "msg_center_purge", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "屏蔽帖子内吧友在讨论", "purge_share_chatroom", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "过滤吧内帖子", "frs_page_filter", SwitchButtonHolder.TYPE_DIALOG, "keyword_hint"))
        preferenceLayout.addView(SwitchButtonHolder(activity, "过滤帖子回复", "content_filter", SwitchButtonHolder.TYPE_DIALOG, "content_hint"))
        preferenceLayout.addView(SwitchButtonHolder(activity, "过滤用户", "user_filter", SwitchButtonHolder.TYPE_DIALOG, "id_hint"))

        return preferenceLayout
    }

    private fun createHidePreference(activity: Activity): LinearLayout {
        val preferenceLayout = PreferenceLayout(activity)
        // "隐藏模块"(hide, Java 层 getStackTrace 特征帧过滤)已移除: 对全局超频方法
        // 挂 after 钩的代价与收益不成比, 且原生侧(hide_native)覆盖同一需求;
        // 分组标题一并去除, 原生项改名"隐藏模块"(hide_native 键不变, 老用户开关状态保留)
        preferenceLayout.addView(SwitchButtonHolder(activity, "隐藏模块", "hide_native", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "检测 Xposed", "check_xposed", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "检测模块", "check_module", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(SwitchButtonHolder(activity, "检测堆栈（重启才能真正生效）", "check_stack_trace", SwitchButtonHolder.TYPE_SWITCH))
        preferenceLayout.addView(
            createButton(
                "开始检测",
                Process.myPid().toString(),
                true
            ) { _ -> TraceChecker(preferenceLayout).checkAll() })
        TraceChecker.sChildCount = preferenceLayout.childCount
        return preferenceLayout
    }

    companion object {
        const val MAIN: String = "贴吧TS设置"
        const val BLOCK_MENU: String = "屏蔽菜单"
        const val SIMPLIFY_MENU: String = "精简菜单"
        const val EXTEND_MENU: String = "扩展功能"
        const val TRACE: String = "痕迹"
        private const val PROXY_ACTIVITY = "com.baidu.tieba.setting.forbiddenforum.ForbiddenForumActivity"
        private const val REQUEST_LOG_DIR = 5141

        // 日志目录选择器的待处理回调, 结果从 onActivityResult hook 派发
        private var sLogDirHandler: ((Intent?) -> Unit)? = null
    }
}
