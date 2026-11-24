package gm.tieba.tabswitch.hooker.add

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.util.Logger
import gm.tieba.tabswitch.util.dipToPx
import gm.tieba.tabswitch.util.fixAlertDialogWidth
import gm.tieba.tabswitch.util.getCurrentActivity
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.util.getTbadkCoreApplicationInst
import gm.tieba.tabswitch.widget.TbToast
import gm.tieba.tabswitch.widget.TbToast.Companion.showTbToast
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

// 主页"获取ID"按钮: 在个人主页屏幕右缘居中注入一个蓝色悬浮按钮, 点击弹窗展示
// 当前用户的 用户名/统一ID 并支持复制。目的: 辅助查看——未启用新版统一 ID
// (tieba_uid)的用户在主页不显示数字 ID, 该按钮把 H5 DOM 里的统一 ID/用户名
// 取出来供复制; 名单填写仍在"过滤用户"设置对话框(保存时确认并解析)。
// 原生绑定链(低版本宿主)可直接拿到 UserData 内部编号。
//
// 按钮挂在 Activity 内容视图而非头部卡内: 头部卡顶部延伸到状态栏之后、页顶工具栏
// 悬浮其上, 卡内叠加位置随版本布局漂移难以稳定; 屏幕级悬浮只依赖"绑定回调到达时
// 能拿到 Activity"这一事实, 位置固定右缘居中。
//
// 钩点按形状探测而非写死混淆名: 绑定方法名与 UI 态类名(qhe/yce)均随版本轮换,
// 但形状稳定——一参 void 公开方法, 参数类型有字段 d, d 的类型有字段 a 且 a 为
// UserData(或其子类)。探测失败仅损失本功能, 不抛异常。
class ProfileUserId : XposedContext(), IHooker {

    override fun key(): String {
        return "get_user_id"
    }

    override fun hook() {
        // 22.14 实测: 别人主页已 H5 化(TBWebContainerActivity 承载, 原生
        // PersonPageInfoView 只在"我的" tab 出现), 因此钩点改为稳定类名
        // TBWebContainerActivity.onResume: 从 BaseWebViewActivity.f 字段(当前页
        // URL)识别个人主页页, 借 Activity 字段 L(TbWebView)的内层 WebView
        // evaluateJavascript 读 DOM 拿用户数据; 原生绑定链保留兜底(低版本宿主)
        runCatching {
            val webContainer = findClass("com.baidu.tbadk.browser.TBWebContainerActivity")
            val base = findClass("com.baidu.tbadk.browser.BaseWebViewActivity")
            val urlField = base.getDeclaredField("f").apply { isAccessible = true }
            val webField = webContainer.getDeclaredField("L").apply { isAccessible = true }
            val innerGetter = webField.type.getMethod("getInnerWebView")
            hookAfterMethod(webContainer, "onResume") { param ->
                runCatching {
                    val activity = param.thisObject as Activity
                    val url = urlField.get(activity) as? String
                    if (url == null || !isProfileUrl(url)) return@hookAfterMethod
                    scheduleDomProbe(activity, url, webField.get(activity), innerGetter)
                }.onFailure { Logger.log("ProfileUserId: onResume 处理失败 $it") }
            }
        }.onFailure { Logger.log("ProfileUserId: H5 链路注册失败 $it") }

        val bindMethod = resolveBindMethod() ?: run {
            Logger.log("ProfileUserId: 未找到主页头部绑定方法(原生兜底跳过)")
            return
        }
        hookAfterMethod(bindMethod) { param ->
            runCatching {
                val userData = boundUserData(param.args[0]) ?: return@hookAfterMethod
                val view = param.thisObject as? View
                val activity = activityOf(view?.context ?: return@hookAfterMethod)
                    ?: return@hookAfterMethod
                if (isSelfUser(userData)) {
                    removeButton(activity)
                    return@hookAfterMethod
                }
                attachButton(activity, userData)
            }.onFailure { Logger.log("ProfileUserId: 绑定回调处理失败 $it") }
        }
    }

    // 个人主页 H5 页 URL 形状(22.14 实测): usercenter 路由落到
    // tieba.baidu.com 域的 main_new_personal 页; 用宽松特征匹配
    private fun isProfileUrl(url: String): Boolean {
        val lower = url.lowercase()
        return ("main_new_personal" in lower) ||
            ("usercenter" in lower) ||
            (("portrait" in lower || "user_id" in lower) && "tieba" in lower)
    }

    // 页面渲染完成后探测 DOM; 防抖按 Activity 维度: 同一 Activity 实例的重复
    // onResume(焦点切换/无障碍回切)只探一轮; 退出重进是新 Activity, 必须重新
    // 探测并挂按钮(按钮表 sButtons 也按 Activity 记忆, 两者口径一致)
    private val sProbedActivities = WeakHashMap<Activity, MutableSet<String>>()

    private fun scheduleDomProbe(activity: Activity, url: String, tbWebView: Any?, innerGetter: Method) {
        val alreadyProbed = synchronized(sProbedActivities) {
            val urls = sProbedActivities[activity]
            if (urls == null) {
                sProbedActivities[activity] = mutableSetOf(url)
                false
            } else {
                !urls.add(url)
            }
        }
        if (alreadyProbed) return
        if (tbWebView == null) return
        // WebView 加载是异步的, 分几次延迟探测。WebView 引用必须在每轮回调内现取:
        // 此前在循环体外一次性解析, 首轮为 null(内核尚未挂载)即 return,
        // 其余延迟轮次全部丢失, 慢网下整轮探测失效。
        // 各轮独立执行不因命中提前终止: handleProbeResult/按钮挂载幂等,
        // 重复探测只多付一次 DOM 文本读取, 4 轮耗尽自然停止
        for (delay in longArrayOf(1500L, 3500L, 6000L, 9000L)) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching {
                    // 页面可能已切换: 仍要求当前 Activity 处于前台
                    if (getCurrentActivity() !== activity) return@runCatching
                    val webView = runCatching { innerGetter.invoke(tbWebView) as? android.webkit.WebView }
                        .getOrNull() ?: return@runCatching
                    webView.evaluateJavascript(JS_PROBE) { json ->
                        handleProbeResult(activity, json)
                    }
                }.onFailure { Logger.log("ProfileUserId: DOM 探测失败 $it") }
            }, delay)
        }
    }

    private companion object {
        // H5 主页 DOM 结构(22.14 实测): 启用统一 ID 的用户头部卡有 "ID<数字>"
        // 文本节点, 用户名在 ID 节点之前; 未启用的用户无 ID 行, 用户名取页面
        // 头部(关注/发私信按钮之前的文本行, 通常为用户名本身)
        const val JS_PROBE = """
        (function(){
            try {
                var bodyText = document.body ? document.body.innerText : '';
                var m = bodyText.match(/ID[:：\s]*(\d{4,})/);
                var out = {id: m ? m[1] : null};
                var head = m ? bodyText.substring(0, m.index) : bodyText.substring(0, 400);
                var lines = head.split(/\n+/).filter(function(s){
                    var t = s.trim();
                    return t.length > 0 && t.length < 30 && t.indexOf('SVIP') < 0;
                });
                if (m) {
                    out.name = lines.length ? lines[lines.length-1] : null;
                } else {
                    out.name = lines.length ? lines[0] : null;
                }
                out.loc = (location.href || '').substring(0, 500);
                return JSON.stringify(out);
            } catch(e) { return JSON.stringify({err: String(e)}); }
        })()
    """
    }

    // 探测结果处理: 命中用户数据即挂蓝色悬浮按钮。未启用统一 ID 的用户
    // (DOM 无 ID 行)正是过滤需求的主要场景, 按钮照常出现, 弹窗内引导
    // 以用户名入名单(名字→内部编号映射依赖带 User 数据的响应被动补充)
    private fun handleProbeResult(activity: Activity, json: String?) {
        runCatching {
            val payload = json?.trim()
                ?.removePrefix("\"")?.removeSuffix("\"")
                ?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            if (payload.isNullOrEmpty() || !payload.startsWith("{")) return
            val id = Regex("\"id\":\"(\\d+)\"").find(payload)?.groupValues?.get(1)
            val name = Regex("\"name\":\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
            val loc = Regex("\"loc\":\"([^\"]*)\"").find(payload)?.groupValues?.get(1)
            // WebView 复用: 轮询早段可能读到上一个页面残留 DOM。主页 SPA 的
            // loc 有两种形态(22.14 实测): 入口 userCenter/hybrid 与 SPA 内部
            // 导航后的 hybrid-main-common/common, 两者都采信; 其余(其它功能
            // 页残留)丢弃等后续轮次
            val isProfileLoc = loc != null &&
                ("userCenter" in loc || "hybrid-main-common" in loc)
            if (!isProfileLoc) return
            if (id == null && name.isNullOrEmpty()) {
                // 既无 ID 也无用户名(数据未渲染完成等): 移除残留按钮,
                // 后续延迟轮次会继续探测
                removeButton(activity)
                return
            }
            if (isSelfH5User(name)) {
                // 自己的主页(名字/昵称与当前登录账号一致): 不挂按钮
                removeButton(activity)
                return
            }
            attachH5Button(activity, id, name)
        }.onFailure { Logger.log("ProfileUserId: 探测结果处理失败 $it") }
    }

    // H5 链自身判定: DOM 拿不到内部编号, 无法像原生链那样与 getCurrentAccount
    // (内部编号)直接比对, 退而与当前账号的用户名/昵称比对(AccountData 无
    // tieba_uid 字段, 数字 ID 无从比对)。命中即视为自己; DOM 残留数据(上一页
    // 的名字)可能造成漏判挂到别人页上, 但绝不会反向误伤——按钮挂在自己页上
    // 只是多余, 不挂/被移除只会损失入口
    private fun isSelfH5User(name: String?): Boolean = runCatching {
        if (name.isNullOrEmpty()) return@runCatching false
        val inst = getTbadkCoreApplicationInst()
        val currentName = runCatching {
            XposedHelpers.callMethod(inst, "getCurrentAccountName") as? String
        }.getOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
        val currentNameShow = runCatching {
            XposedHelpers.callMethod(inst, "getCurrentAccountNameShow") as? String
        }.getOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
        val pageName = name.trim()
        pageName == currentName || (currentNameShow != null && pageName == currentNameShow)
    }.getOrDefault(false)

    // 蓝色小药丸按钮构造(H5 链与原生绑定链共用): 屏幕右缘垂直居中悬浮,
    // 两条链的差异仅在 tag 数据与点击弹窗, 由调用方各自接线
    private fun buildPillButton(activity: Activity, data: Any?): TextView = TextView(activity).apply {
        text = "获取ID"
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(
            dipToPx(activity, 10f), dipToPx(activity, 6f),
            dipToPx(activity, 10f), dipToPx(activity, 6f)
        )
        background = GradientDrawable().apply {
            cornerRadius = dipToPx(activity, 14f).toFloat()
            setColor(0xFF2E7CF6.toInt())
        }
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = dipToPx(activity, 6f)
        }
        tag = data
    }

    private fun attachH5Button(activity: Activity, id: String?, name: String?) {
        runCatching {
            val content = activity.findViewById<View>(android.R.id.content) as? FrameLayout
                ?: return
            sButtons.remove(activity)?.let { (it.parent as? ViewGroup)?.removeView(it) }
            val button = buildPillButton(activity, H5UserInfo(id, name))
            button.setOnClickListener { v ->
                (v.tag as? H5UserInfo)?.let { showH5IdDialog(it) }
            }
            content.addView(button)
            sButtons[activity] = button
        }.onFailure { Logger.log("ProfileUserId: H5 按钮注入失败 $it") }
    }

    // H5 页面数据: 统一ID(启用统一 ID 的用户必有)或用户名二者取其一;
    // 内部编号 H5 不下发, 无 ID 用户经名字映射换算参与匹配
    private class H5UserInfo(val tiebaUid: String?, val name: String?)

    private fun showH5IdDialog(info: H5UserInfo) {
        runCatching {
            val activity = getCurrentActivity() ?: return
            // portrait 从映射表反查: 本会话收割过的用户才有(如先打开过其帖子),
            // 无记录则不显示该行——H5 页不下发内部编号, 无法主动查
            val portrait = info.name?.let {
                gm.tieba.tabswitch.hooker.eliminate.UserFilter.lookupPortraitByName(it)
            }
            val msg = buildString {
                if (!info.name.isNullOrEmpty()) append("用户名：").append(info.name).append('\n')
                append("统一ID：").append(info.tiebaUid ?: "未启用").append('\n')
                append("头像ID：").append(portrait ?: "暂无（打开其帖子后可获取）")
            }
            val alert = AlertDialog.Builder(activity, getDialogTheme(getContext()))
                .setTitle("获取用户ID")
                .setMessage(msg)
                .setNegativeButton("关闭", null)
                .setPositiveButton("复制ID", null)
                .create()
            alert.setOnShowListener {
                // 各按钮回调由宿主对话框框架直接调用, 就地隔离不击穿宿主主线程
                alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    runCatching {
                        val clipboard = getTbadkCoreApplicationInst().getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as ClipboardManager
                        // 复制统一 ID; 未启用时回退复制 portrait(同全局唯一),
                        // 不再回退用户名
                        val text = info.tiebaUid ?: portrait ?: ""
                        clipboard.setPrimaryClip(ClipData.newPlainText("tieba", text))
                        showTbToast(
                            when {
                                info.tiebaUid != null -> "已复制"
                                portrait != null -> "已复制头像ID（该用户未启用统一ID）"
                                else -> "无可复制内容（先打开其帖子建立标识）"
                            },
                            TbToast.LENGTH_SHORT
                        )
                    }.onFailure { Logger.log("ProfileUserId: H5 复制失败 $it") }
                }
            }
            alert.show()
            fixAlertDialogWidth(alert)
        }.onFailure { Logger.log("ProfileUserId: H5 弹窗失败 $it") }
    }

    // 形状探测绑定方法: 一参 void 公开方法 + 参数类型 d 字段 -> a 字段为 UserData
    private fun resolveBindMethod(): Method? = runCatching {
        val viewClass = findClass("com.baidu.tieba.personpage.view.PersonPageInfoView")
        val userDataClass = findClass("com.baidu.tbadk.core.data.UserData")
        viewClass.declaredMethods.firstOrNull { m ->
            m.parameterCount == 1 && m.returnType == Void.TYPE &&
                Modifier.isPublic(m.modifiers) &&
                runCatching {
                    val cardField = m.parameterTypes[0].getDeclaredField("d")
                    val dataField = cardField.type.getDeclaredField("a")
                    userDataClass.isAssignableFrom(dataField.type)
                }.getOrDefault(false)
        }?.apply { isAccessible = true }
    }.getOrNull()

    // qhe.d(yce).a(UserData): 主页头部卡的完整用户数据
    private fun boundUserData(uiState: Any?): Any? {
        if (uiState == null) return null
        val card = runCatching { XposedHelpers.getObjectField(uiState, "d") }.getOrNull() ?: return null
        return runCatching { XposedHelpers.getObjectField(card, "a") }.getOrNull()
    }

    // 当前登录账号内部编号(TbadkCoreApplication.getCurrentAccount)与绑定用户一致
    // 即视为自身主页; 未登录/取不到时按他人主页处理
    private fun isSelfUser(userData: Any): Boolean {
        val userId = callString(userData, "getUserId") ?: return false
        if (userId.isEmpty()) return false
        val currentAccount = runCatching {
            XposedHelpers.callStaticMethod(
                findClass("com.baidu.tbadk.core.TbadkCoreApplication"), "getCurrentAccount"
            ) as? String
        }.getOrNull() ?: return false
        return userId.equals(currentAccount, ignoreCase = true)
    }

    // View.context 可能是 ContextThemeWrapper 等包装, 沿 base 链找宿主 Activity
    private fun activityOf(context: Context): Activity? {
        var current: Context = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext ?: return null
        }
        return null
    }

    // 按钮按 Activity 记忆: 个人主页是独立 Activity, 一页一按钮;
    // WeakHashMap 随页面销毁自动清理
    private val sButtons = WeakHashMap<Activity, TextView>()

    // 蓝色小药丸, 屏幕右缘垂直居中悬浮(内容视图层叠在最上)
    private fun attachButton(activity: Activity, userData: Any) {
        runCatching {
            sButtons[activity]?.let { existing ->
                // 页面内切换用户(极少, 同 Activity 复用): 更新按钮携带的数据
                existing.tag = userData
                return
            }
            val content = activity.findViewById<View>(android.R.id.content) as? FrameLayout
                ?: return
            val button = buildPillButton(activity, userData)
            // 点击监听由宿主 View 框架直接调用, 不经 hook 包装层: 弹窗链路失败
            // 时就地吞掉记日志, 不沿宿主主线程击穿
            button.setOnClickListener { v ->
                (v.tag ?: return@setOnClickListener).let { showIdDialog(it) }
            }
            content.addView(button)
            sButtons[activity] = button
        }.onFailure { Logger.log("ProfileUserId: 按钮注入失败 $it") }
    }

    private fun removeButton(activity: Activity) {
        sButtons.remove(activity)?.let { stale ->
            runCatching { (stale.parent as? ViewGroup)?.removeView(stale) }
        }
    }

    private fun showIdDialog(userData: Any) {
        runCatching {
            val activity = getCurrentActivity() ?: return
            val userId = callString(userData, "getUserId")
            if (userId.isNullOrEmpty()) {
                showTbToast("未获取到用户数据，请重新进入主页", TbToast.LENGTH_SHORT)
                return
            }
            val tiebaUid = callString(userData, "getmTiebaUid")
                ?.trim()?.takeUnless { it.isEmpty() }
            val userName = callString(userData, "getUserName")
            val nameShow = callString(userData, "getName_show")
            // portrait(官方唯一头像ID, tb.1.开头): UserData 里有; 字段值可能带
            // 头像缓存参数后缀("?t=..."), 展示/复制统一裁掉
            val portrait = callString(userData, "getPortrait")
                ?.trim()?.takeUnless { it.isEmpty() }?.substringBefore('?')

            val info = buildString {
                if (!userName.isNullOrEmpty()) append("用户名：").append(userName).append('\n')
                if (!nameShow.isNullOrEmpty() && nameShow != userName) {
                    append("昵称：").append(nameShow).append('\n')
                }
                append("统一ID：").append(tiebaUid ?: "未启用").append('\n')
                append("头像ID：").append(portrait ?: "未获取")
            }

            val alert = AlertDialog.Builder(activity, getDialogTheme(getContext()))
                .setTitle("获取用户ID")
                .setMessage(info)
                .setNegativeButton("关闭", null)
                .setNeutralButton("复制全部", null)
                .setPositiveButton("复制ID", null)
                .create()
            alert.setOnShowListener {
                // 各按钮回调由宿主对话框框架直接调用, 就地隔离不击穿宿主主线程
                alert.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    runCatching {
                        val clipboard = getTbadkCoreApplicationInst().getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("tieba", info))
                        showTbToast("已复制", TbToast.LENGTH_SHORT)
                    }.onFailure { Logger.log("ProfileUserId: 复制失败 $it") }
                }
                alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    runCatching {
                        val clipboard = getTbadkCoreApplicationInst().getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as ClipboardManager
                        // 未启用统一 ID 的用户回退复制 portrait(同样唯一可反查)
                        val text = tiebaUid ?: portrait ?: ""
                        clipboard.setPrimaryClip(ClipData.newPlainText("tieba", text))
                        showTbToast(
                            when {
                                tiebaUid != null -> "已复制"
                                portrait != null -> "已复制头像ID（该用户未启用统一ID）"
                                else -> "无可复制内容"
                            },
                            TbToast.LENGTH_SHORT
                        )
                    }.onFailure { Logger.log("ProfileUserId: 复制失败 $it") }
                }
            }
            alert.show()
            fixAlertDialogWidth(alert)
        }.onFailure { Logger.log("ProfileUserId: 弹窗展示失败 $it") }
    }

    private fun callString(target: Any, method: String): String? =
        runCatching { XposedHelpers.callMethod(target, method) as? String }.getOrNull()
}
