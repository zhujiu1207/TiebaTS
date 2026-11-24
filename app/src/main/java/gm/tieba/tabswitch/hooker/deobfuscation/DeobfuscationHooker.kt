package gm.tieba.tabswitch.hooker.deobfuscation

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.dao.AcRules.countAll
import gm.tieba.tabswitch.dao.AcRules.dropAllRules
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.MAIN_TAB_ACTIVITY
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.getDeobfsFailCount
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.getTbVersion
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.isDexChanged
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.isRuleStoreIntact
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.markDeobfsVersion
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper.recordDeobfsRun
import gm.tieba.tabswitch.util.Logger
import gm.tieba.tabswitch.util.dipToPx
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.util.getDisplayWidth
import gm.tieba.tabswitch.util.isLightMode
import gm.tieba.tabswitch.util.restart
import gm.tieba.tabswitch.widget.TbToast
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.properties.Delegates

class DeobfuscationHooker(private val mMatchers: List<Matcher>) : XposedContext(), IHooker {

    var progress : Float by Delegates.observable(0f) { _, _, new ->
        updateProgress(new)
    }

    private val deobfuscation = Deobfuscation()

    // 进度面板与其视图: 只在主线程创建/访问, 扫描线程经 setMessage/updateProgress
    // 的 runOnUiThread 更新。按钮是面板自绘 TextView 而非 AlertDialog 按钮栏——
    // 部分 ROM 上 setButton 在 show() 之后添加不生效(按钮栏未随无按钮对话框安装)
    private var mDialog: AlertDialog? = null
    private var mTitleText: TextView? = null
    private var mMessageText: TextView? = null
    private var mProgressRow: View? = null
    private var mProgressBar: ProgressBar? = null
    private var mPercentText: TextView? = null
    private var mButtonRow: LinearLayout? = null

    // 进度面板是否已挂载到当前 Activity: 挂载失败后反混淆继续(headless),
    // 结束态改用 toast 提示, 访问面板字段前必须先判此标志
    @Volatile
    private var mUiReady = false

    // 反混淆确认框: 仅在 PENDING 态存在, Activity 重建后由 onResume 重挂;
    // 用户确认后交由 beginScan 进入扫描
    @Volatile
    private var mConfirmDialog: AlertDialog? = null

    // 用户本会话主动关闭过确认框: 不再重复弹出, 重启贴吧后重新询问
    @Volatile
    private var mPromptDeclined = false

    private val isConfirmDialogShowing: Boolean
        get() = mConfirmDialog?.isShowing == true

    // 最近一条进度文案: Activity 重建(旋转/切后台被回收)后重挂面板时恢复显示
    @Volatile
    private var mLatestMessage: String = ""

    override fun key(): String {
        return "deobfs"
    }

    override fun hook() {
        // 开启净化的会话抑制贴吧本版本的定位权限弹窗: 此时尚在 Application 初始化,
        // 抢在首页展示前写入, 避免与反混淆进度面板在首页叠加。
        // apply 即可: 同进程内宿主后续读取走框架内存缓存, 立即可见; 首次加载宿主
        // settings XML 的同步开销照付(框架行为), 但省掉一次主线程同步磁盘写
        if (getBoolean("purge")) {
            getContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit()
                .putString("key_location_request_dialog_last_show_version", getTbVersion(getContext()))
                .apply()
        }

        Logger.log("Deobfuscation 待触发, 贴吧版本: ${getTbVersion(getContext())}")

        // 延迟触发: 不再重定向 Logo 页, 等首页(MainTab)渲染稳定后弹确认框, 由用户
        // 决定是否立即执行反混淆; 扫描期间不冻结宿主导航/不改写 framework 方法,
        // 完成后由用户确认重启
        hookAfterMethod(
            MAIN_TAB_ACTIVITY,
            "onResume"
        ) { param ->
            val activity = param.thisObject as Activity
            when (sScanState) {
                STATE_IDLE -> {
                    var shouldSchedule = false
                    synchronized(sStateLock) {
                        if (sScanState == STATE_IDLE) {
                            sScanState = STATE_PENDING
                            shouldSchedule = true
                        }
                    }
                    if (shouldSchedule) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            startDeobfuscation(activity)
                        }, TRIGGER_DEBOUNCE_MS)
                    }
                }
                // 待确认(旋转重建/切后台返回): 确认框随原窗口销毁, 未主动关闭过则
                // 重新弹出; 已关闭过则本轮不再打扰, 重启贴吧后会再次询问
                STATE_PENDING ->
                    if (!mPromptDeclined && !isConfirmDialogShowing) showConfirmDialog(activity)
                // 扫描进行中(旋转重建/切后台返回): 重新挂载进度面板, 扫描线程不受影响
                STATE_SCANNING -> if (!mUiReady) attachDialogTo(activity)
            }
        }
        // Activity 销毁时先收回面板/确认框窗口避免窗口泄漏; 扫描继续, 下次 onResume 重挂
        hookAfterMethod(
            MAIN_TAB_ACTIVITY,
            "onDestroy"
        ) {
            if (sScanState == STATE_SCANNING && mUiReady) {
                dismissDialog()
            }
            dismissConfirmDialog()
        }
    }

    // 触发入口(主线程, 去抖到期后调用): 熔断检查 → 确认框 → (用户确认)挂载进度面板 → 后台扫描
    private fun startDeobfuscation(activity: Activity) {
        if (sScanState != STATE_PENDING) return
        // 用户在去抖窗口内已主动关闭过确认框时, 到点后不再二次弹框打扰
        // (onScanDeclined 保持 PENDING, 仅凭状态判断拦不住这条路径)
        if (mPromptDeclined) return
        // 连续失败熔断: 不打扰用户, 保留旧规则让模块按既有规则继续工作。
        // 给一次性提示, 避免用户面对"部分功能静默失效"却毫无线索
        if (getDeobfsFailCount(activity) >= MAX_FAIL_COUNT) {
            if (transitionState(STATE_PENDING, STATE_FAILED)) {
                XposedBridge.log(
                    "Deobfuscation: 连续失败已达 $MAX_FAIL_COUNT 次, 跳过反混淆(保留旧规则)"
                )
                Logger.log("Deobfuscation 连续失败已达上限, 跳过(建议更新模块)")
                recordDeobfsRun(activity, "熔断跳过: 连续失败${MAX_FAIL_COUNT}次")
                thread {
                    // 整体防护: prefs commit/Logger 异常不应沿未捕获链击穿宿主。
                    // dex 签名校验较重放后台: 若 dex 实际未变(如此前失败是 DexKit
                    // 偶发 OOM 等瞬时原因), 清零计数让下一次冷启动可以重试,
                    // 否则熔断会一直封死到贴吧版本变化
                    runCatching {
                        if (!isDexChanged(activity)) {
                            DeobfuscationHelper.resetDeobfsFailCount(activity)
                            Logger.log("Deobfuscation: dex 未变更, 熔断计数已清零(下次启动重试)")
                        }
                    }
                }
                if (!sBreakerToastShown) {
                    sBreakerToastShown = true
                    TbToast.showTbToast("贴吧版本更新，部分功能暂时失效\n建议更新贴吧TS模块", TbToast.LENGTH_LONG)
                }
            }
            return
        }
        Logger.log("Deobfuscation 待确认, 贴吧版本: ${getTbVersion(activity)}")
        showConfirmDialog(activity)
    }

    // 反混淆确认框: 反混淆是启动路径上最重的一步(DexKit native 全量扫描), 其
    // 崩溃面(native SIGSEGV/OOM)无法被 runCatching 兜住, 改为用户确认后执行,
    // 用户可选择在方便重启的时间点再触发。窗口 UI 与反混淆进行中面板保持一致
    // (同款圆角卡片/配色/胶囊按钮, 不走 AlertDialog 按钮栏), 左"关闭" = 本轮
    // 跳过, 右"开始反混淆"
    private fun showConfirmDialog(activity: Activity) {
        if (isConfirmDialogShowing) return
        try {
            val colors = PanelColors(isLightMode(activity))
            val dp = { v: Float -> dipToPx(activity, v) }
            val title = TextView(activity).apply {
                textSize = 17f
                setTextColor(colors.title)
                typeface = Typeface.DEFAULT_BOLD
                text = "检测到贴吧更新"
            }
            val message = TextView(activity).apply {
                textSize = 13f
                setTextColor(colors.secondary)
                text = "模块需要重新进行反混淆"
            }
            val buttonRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    textButton(activity, colors, "关闭", false) { onScanDeclined() },
                    LinearLayout.LayoutParams(0, dp(42f), 1f)
                )
                addView(
                    textButton(activity, colors, "开始反混淆", true) { beginScan(activity) },
                    LinearLayout.LayoutParams(0, dp(42f), 1f).apply { marginStart = dp(10f) }
                )
            }
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                // 同进度面板: 圆角卡片底色, 窗口背景透明后由本面板承担视觉容器
                background = GradientDrawable().apply {
                    setColor(colors.card)
                    cornerRadius = dp(22f).toFloat()
                }
                setPaddingRelative(dp(24f), dp(22f), dp(24f), dp(20f))
                addView(title)
                addView(message, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) })
                addView(buttonRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(24f) })
            }
            val dialog = AlertDialog.Builder(activity, getDialogTheme(activity))
                .setView(panel)
                .setCancelable(true)
                .create()
            // 返回键/触外部取消 = 与"关闭"同义: 本轮跳过, 不强制打断用户
            dialog.setOnCancelListener { onScanDeclined() }
            dialog.show()
            // 与进度面板一致的卡片化窗口: 背景透明露出面板圆角, 宽度收窄至屏宽 82% 居中
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                val width = getDisplayWidth(activity)
                    ?.let { (it * 0.82f).toInt() } ?: WindowManager.LayoutParams.WRAP_CONTENT
                setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            mConfirmDialog = dialog
        } catch (e: Throwable) {
            // 确认框弹不出来时不能让扫描永久无人触发: Activity 仍存活就直接开始
            // (进度面板自身挂载失败会 headless 降级), 已销毁则留给下次 onResume 重试
            logBoth("Deobfuscation 确认框创建失败 -- $e")
            if (!activity.isFinishing && !activity.isDestroyed) {
                beginScan(activity)
            }
        }
    }

    // 用户放弃本轮反混淆: 会话内保持 PENDING 不再打扰, 功能以旧规则(或无规则)
    // 裸跑; 落盘的版本号未更新, 下次冷启动会再次弹出确认框
    private fun onScanDeclined() {
        mPromptDeclined = true
        dismissConfirmDialog()
        Logger.log("Deobfuscation: 用户选择暂不反混淆, 本会话跳过")
        TbToast.showTbToast("已跳过反混淆，功能暂未生效\n重启贴吧后会再次询问", TbToast.LENGTH_LONG)
    }

    // 确认后的扫描启动(确认框按钮回调): 状态机切换 + 挂进度面板 + 后台扫描
    private fun beginScan(activity: Activity) {
        dismissConfirmDialog()
        Logger.log("Deobfuscation 开始, 贴吧版本: ${getTbVersion(activity)}")
        if (!transitionState(STATE_PENDING, STATE_SCANNING)) return
        // 确认框存续期间 Activity 已销毁: 面板无处挂载, 直接走 headless 扫描,
        // 结束态经 toastTerminal 提示; 避免对已销毁窗口创建面板白走一轮失败路径
        if (!activity.isFinishing && !activity.isDestroyed) {
            attachDialogTo(activity)
        }
        thread {
            runCatching { runDeobfuscation(activity) }.onFailure {
                // runDeobfuscation 内部已按状态机兜底, 此处仅拦截意外抛出:
                // 任何异常都不能留在扫描线程上击穿宿主默认异常处理器
                XposedBridge.log(it)
                Logger.log("Deobfuscation 意外异常")
                Logger.log(it)
                if (transitionState(STATE_SCANNING, STATE_FAILED)) {
                    val failCount = DeobfuscationHelper.incDeobfsFailCount(activity)
                    recordDeobfsRun(activity, "失败#$failCount: ${it.javaClass.simpleName}")
                    showTerminal(activity, "反混淆失败", "处理失败(第${failCount}次)\n建议重启贴吧重试", false)
                }
            }
        }
    }

    private fun dismissConfirmDialog() {
        val dialog = mConfirmDialog
        mConfirmDialog = null
        // 不置 mPromptDeclined: 销毁路径(旋转/切后台)不是用户拒绝, 下次 onResume 重挂
        if (dialog != null && dialog.isShowing) {
            runCatching { dialog.dismiss() }
        }
    }

    private fun runDeobfuscation(activity: Activity) {
        val dexChanged = try {
            isDexChanged(activity)
        } catch (e: Throwable) {
            XposedBridge.log(e)
            true // 判定异常按"已变更"处理, 走反混淆并在失败时计入熔断
        }
        if (!dexChanged) {
            // 规则库完整性校验: 库被清空/上次写入中断而签名未变时, 不能走
            // "跳过重建"捷径, 否则规则永久缺失、每次启动弹"规则异常"死循环;
            // 校验失败(db 异常)按不完整处理, 回落完整反混淆尝试自愈
            val ruleStoreIntact = runCatching { isRuleStoreIntact(activity) }
                .onFailure { logBoth(it) }
                .getOrDefault(false)
                if (ruleStoreIntact) {
                    recordDeobfsRun(activity, "跳过: dex未变更且规则库完整")
                    DeobfuscationHelper.saveDeobfsModuleId(activity, DeobfuscationHelper.currentModuleId())
                    DeobfuscationHelper.resetDeobfsFailCount(activity)
                    // 落盘贴吧版本, 避免下次启动重复触发(用户不点重启直接杀进程时也生效)
                    markDeobfsVersion(activity, getTbVersion(activity))
                    // 规则已在库中但本会话未加载任何 hooker, 仍需重启激活
                    if (transitionState(STATE_SCANNING, STATE_DONE)) {
                        showTerminal(activity, "反混淆完成", "无需重新定位\n重启贴吧以启用新功能", true)
                    }
                    return
                }
            recordDeobfsRun(activity, "自愈: dex未变更但规则库不完整, 重建")
            Logger.log("Deobfuscation: dex 未变更但规则库不完整, 重新执行反混淆")
        }
        try {
            setMessage("搜索资源，字符串和方法调用")
            dropAllRules()

            // 扫描看门狗: DexKit native 层无超时机制, 极端情况下(损坏的 dex/
            // 厂商 ROM 差异)扫描永久挂起会把用户困死在模态面板里。超时后
            // 释放面板并计入熔断, 持续挂起 3 次后彻底停止尝试;
            // 挂起的扫描线程若事后完成, 状态机已切换, 其 UI/计数/落盘均不生效
            val watchdog = ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "deobfs-watchdog").apply { isDaemon = true }
            }
            try {
                watchdog.schedule({
                    if (transitionState(STATE_SCANNING, STATE_FAILED)) {
                        Logger.log("Deobfuscation 看门狗: 扫描超过 ${DEOBFUSCATION_TIMEOUT_MINUTES} 分钟未完成, 释放界面")
                        recordDeobfsRun(activity, "失败: 扫描超时${DEOBFUSCATION_TIMEOUT_MINUTES}分钟(疑似挂起)")
                        DeobfuscationHelper.incDeobfsFailCount(activity)
                        showTerminal(
                            activity,
                            "反混淆超时",
                            "反混淆超过${DEOBFUSCATION_TIMEOUT_MINUTES}分钟未完成\n建议重启贴吧重试",
                            false
                        )
                    }
                }, DEOBFUSCATION_TIMEOUT_MINUTES, TimeUnit.MINUTES)

                performDeobfuscation(activity, mMatchers)

                // 看门狗超时(状态已切 FAILED)后迟到的完成不得撤销熔断: 不重置失败计数、
                // 不落盘版本号/模块ID(否则版本标记会阻止下次启动重扫), 只保留规则已
                // 落盘的事实; UI 切换由 transitionState 守护自然失效
                if (sScanState != STATE_SCANNING) {
                    Logger.log("Deobfuscation: 扫描在超时熔断后迟到完成, 忽略其收尾")
                    return
                }
                XposedBridge.log("Deobfuscation complete, current version: ${getTbVersion(activity)}")
                Logger.log("Deobfuscation 完成, 当前版本: ${getTbVersion(activity)}")
                recordDeobfsRun(activity, "完成: 规则${countAll()}条")
                DeobfuscationHelper.resetDeobfsFailCount(activity)
                // 反混淆完成即落盘模块版本号(自动补扫的循环屏障)与贴吧版本
                // (下次启动不再触发): 用户不点重启直接杀进程时同样生效
                DeobfuscationHelper.saveDeobfsModuleId(activity, DeobfuscationHelper.currentModuleId())
                markDeobfsVersion(activity, getTbVersion(activity))
                if (transitionState(STATE_SCANNING, STATE_DONE)) {
                    showTerminal(activity, "反混淆完成", "重启贴吧后新功能生效", true)
                }
            } finally {
                watchdog.shutdownNow()
            }
        } catch (e: Throwable) {
            XposedBridge.log(e)
            Logger.log("Deobfuscation 处理失败")
            Logger.log(e)
            // 看门狗已先行判超时(状态已切走)时不再重复计数
            if (transitionState(STATE_SCANNING, STATE_FAILED)) {
                val failCount = DeobfuscationHelper.incDeobfsFailCount(activity)
                recordDeobfsRun(
                    activity,
                    "失败#${failCount}: ${e.javaClass.simpleName}: ${e.message?.take(100) ?: "无信息"}"
                )
                showTerminal(
                    activity,
                    "反混淆失败",
                    "处理失败(第${failCount}次)\n${Log.getStackTraceString(e).take(600)}",
                    false
                )
            }
        }
    }

    private fun performDeobfuscation(context: Context, matchers: List<Matcher>) {
        deobfuscation.setMatchers(matchers)
        deobfuscation.dexkit(context, this)
        deobfuscation.saveDexSignatureHashCode()
    }

    // 结束态 toast 降级: 面板不在(挂载失败/Activity 已销毁)时通知用户,
    // 规则与版本号均已落盘, 下次冷启动自然生效
    private fun toastTerminal(message: String, showRestart: Boolean) {
        val toast = if (showRestart) {
            "反混淆完成\n重启贴吧后生效"
        } else {
            message.lineSequence().firstOrNull()?.take(40) ?: "反混淆失败"
        }
        TbToast.showTbToast(toast, TbToast.LENGTH_LONG)
    }

    // 主线程调用: 构建并显示模态进度面板; 失败降级为 headless(返回 false)。
    // 重挂仅发生在 SCANNING 态, 按扫描中布局构建, 进度/文案恢复到当前值
    private fun attachDialogTo(activity: Activity): Boolean {
        return try {
            val colors = PanelColors(isLightMode(activity))
            val dp = { v: Float -> dipToPx(activity, v) }

            val title = TextView(activity).apply {
                textSize = 17f
                setTextColor(colors.title)
                typeface = Typeface.DEFAULT_BOLD
                text = "检测到贴吧更新"
            }
            val message = TextView(activity).apply {
                textSize = 13f
                setTextColor(colors.secondary)
                text = mLatestMessage
            }
            // 自绘圆角进度条: 默认 horizontal 样式观感差,
            // 用 圆角轨道+圆角进度 的 LayerDrawable 替换, 统一高度 8dp。
            // 必须保留 progressBarStyleHorizontal 构造参数: 缺省样式是圆形 spinner,
            // 且 progressDrawable 替换依赖该样式的确定模式初始化
            val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = false
                progress = (this@DeobfuscationHooker.progress * 100).toInt().coerceIn(0, 100)
                progressDrawable = LayerDrawable(
                    arrayOf(
                        GradientDrawable().apply {
                            setColor(colors.track); cornerRadius = dp(4f).toFloat()
                        },
                        ClipDrawable(
                            GradientDrawable().apply {
                                setColor(colors.accent); cornerRadius = dp(4f).toFloat()
                            }, Gravity.START, ClipDrawable.HORIZONTAL
                        ),
                    )
                ).apply {
                    setId(0, android.R.id.background)
                    setId(1, android.R.id.progress)
                }
            }
            val percent = TextView(activity).apply {
                textSize = 12f
                setTextColor(colors.secondary)
                text = "${bar.progress}%"
            }
            val progressRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(bar, LinearLayout.LayoutParams(0, dp(8f), 1f))
                addView(percent, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(10f) })
            }
            // 全宽竖排按钮行: 完成/失败态由 showTerminal 填充并显示,
            // 扫描态保持 GONE(否则空行也会占掉 topMargin 的空白)
            val buttonRow = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                // 圆角卡片底色: 窗口背景透明后由本面板承担视觉容器
                background = GradientDrawable().apply {
                    setColor(colors.card)
                    cornerRadius = dp(22f).toFloat()
                }
                setPaddingRelative(dp(24f), dp(22f), dp(24f), dp(20f))
                addView(title)
                addView(message, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8f) })
                addView(progressRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(22f) })
                addView(buttonRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(24f) })
            }

            val dialog = AlertDialog.Builder(activity, getDialogTheme(activity))
                .setView(panel)
                .setCancelable(true)
                .create()
            // 返回键 = 主动放弃等待: 扫描继续(headless), 完成态下规则/版本号已落盘,
            // 下次冷启动自然生效, 不提供任何打断扫描的手段
            dialog.setOnCancelListener { dismissDialog() }
            dialog.show()
            // 卡片化窗口: 背景透明露出面板圆角, 宽度收窄至屏宽 82% 居中
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                val width = getDisplayWidth(activity)
                    ?.let { (it * 0.82f).toInt() } ?: WindowManager.LayoutParams.WRAP_CONTENT
                setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            mDialog = dialog
            mTitleText = title
            mMessageText = message
            mProgressRow = progressRow
            mProgressBar = bar
            mPercentText = percent
            mButtonRow = buttonRow
            mUiReady = true
            true
        } catch (e: Throwable) {
            mUiReady = false
            logBoth("Deobfuscation 进度面板创建失败, 降级为无界面继续 -- $e")
            false
        }
    }

    private fun dismissDialog() {
        val dialog = mDialog
        mUiReady = false
        mDialog = null
        mTitleText = null
        mMessageText = null
        mProgressRow = null
        mProgressBar = null
        mPercentText = null
        mButtonRow = null
        if (dialog != null && dialog.isShowing) {
            runCatching { dialog.dismiss() }
        }
    }

    private fun setMessage(message: String) {
        mLatestMessage = message
        if (!mUiReady) return
        runOnUiThread {
            runCatching { mMessageText?.text = message }
        }
    }

    private fun updateProgress(progress: Float) {
        if (!mUiReady) return
        runOnUiThread {
            val value = (progress * 100).toInt().coerceIn(0, 100)
            runCatching {
                mProgressBar?.progress = value
                mPercentText?.text = "$value%"
            }
        }
    }

    // 结束态(完成/失败/超时): 面板就地切换标题/文案, 隐藏进度行, 显示自绘按钮
    private fun showTerminal(activity: Activity, title: String, message: String, showRestart: Boolean) {
        if (!mUiReady) {
            toastTerminal(message, showRestart)
            return
        }
        runOnUiThread {
            val dialog = mDialog
            val row = mButtonRow
            if (dialog == null || row == null || !dialog.isShowing) {
                toastTerminal(message, showRestart)
                return@runOnUiThread
            }
            runCatching {
                val colors = PanelColors(isLightMode(activity))
                val dp = { v: Float -> dipToPx(activity, v) }
                mTitleText?.text = title
                mMessageText?.text = message
                mProgressRow?.visibility = View.GONE
                row.removeAllViews()
                val buttonParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(42f)
                )
                if (showRestart) {
                    // 完成态仅提供重启一个出口: 不想立刻重启可按返回键放弃,
                    // 规则/版本号已落盘, 下次冷启动自然生效
                    row.addView(textButton(activity, colors, "重启贴吧", true) { restart(activity) }, buttonParams)
                } else {
                    row.addView(textButton(activity, colors, "关闭", false) { dismissDialog() }, buttonParams)
                }
                row.visibility = View.VISIBLE
            }
        }
    }

    // 面板自绘按钮: 主按钮胶囊填充, 副按钮文字式, 均为全宽。不走 AlertDialog 按钮
    // 栏, 规避部分 ROM 上 show() 后 setButton 不渲染的问题
    private fun textButton(
        activity: Activity,
        colors: PanelColors,
        text: String,
        primary: Boolean,
        onClick: () -> Unit,
    ): TextView {
        val dp = { v: Float -> dipToPx(activity, v) }
        return TextView(activity).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            if (primary) {
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(colors.accent)
                    cornerRadius = dp(22f).toFloat()
                }
            } else {
                setTextColor(colors.secondary)
                background = GradientDrawable().apply {
                    setColor(colors.track)
                    cornerRadius = dp(22f).toFloat()
                }
            }
            setOnClickListener { onClick() }
        }
    }

    // 面板配色: 跟随宿主日夜模式
    private class PanelColors(light: Boolean) {
        val card = if (light) 0xFFFFFFFF.toInt() else 0xFF1E1F22.toInt()
        val title = if (light) 0xDE000000.toInt() else 0xDEFFFFFF.toInt()
        val secondary = if (light) 0xFF7A7A7A.toInt() else 0xFF9E9E9E.toInt()
        val track = if (light) 0x14000000.toInt() else 0x1FFFFFFF.toInt()
        val accent = 0xFF3388FF.toInt()
    }

    companion object {
        // 触发去抖: 首页渲染稳定并停留片刻后再弹面板, 避免与首屏布局/数据加载竞争,
        // 也减少对刚进入贴吧用户的打扰
        private const val TRIGGER_DEBOUNCE_MS = 6_000L

        // 反混淆扫描看门狗时限: 正常全量扫描(约 50 个 matcher)在主流设备上
        // 数十秒内完成, 弱设备放宽到 5 分钟仍远超正常耗时, 仅拦截真挂起
        private const val DEOBFUSCATION_TIMEOUT_MINUTES = 5L

        // 反混淆连续失败熔断阈值(持久化于 TS_config, 按贴吧版本绑定, 跨重启累计);
        // 供 XposedInit 的补扫分支判读, 必须公开
        const val MAX_FAIL_COUNT = 3

        // 熔断后的一次性提示标志(每进程一次)
        @Volatile
        private var sBreakerToastShown = false

        // 触发/扫描状态机: IDLE→(首次 MainTab.onResume)→PENDING→(去抖到期)→
        // SCANNING→DONE/FAILED, 每进程至多完整走一次; SCANNING 态的 onResume
        // 只做面板重挂, 不重复触发。状态切换统一经 transitionState,
        // 看门狗与扫描线程竞争终态时只有先到者生效
        private const val STATE_IDLE = 0
        private const val STATE_PENDING = 1
        private const val STATE_SCANNING = 2
        private const val STATE_DONE = 3
        private const val STATE_FAILED = 4
        @Volatile
        private var sScanState = STATE_IDLE
        private val sStateLock = Any()

        fun isBreakerTripped(context: Context): Boolean =
            DeobfuscationHelper.getDeobfsFailCount(context) >= MAX_FAIL_COUNT

        private fun transitionState(expect: Int, newState: Int): Boolean =
            synchronized(sStateLock) {
                if (sScanState == expect) {
                    sScanState = newState
                    true
                } else {
                    false
                }
            }
    }
}
