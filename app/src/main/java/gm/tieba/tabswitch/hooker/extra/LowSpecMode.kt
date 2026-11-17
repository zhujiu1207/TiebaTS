package gm.tieba.tabswitch.hooker.extra

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.dao.Preferences.putBoolean
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.util.dipToPx
import gm.tieba.tabswitch.util.fixAlertDialogWidth
import gm.tieba.tabswitch.util.getDialogTheme
import gm.tieba.tabswitch.widget.Switch
import gm.tieba.tabswitch.widget.TbToast

// 低配模式: 主开关聚合多项性能优化, 降低贴吧在中低端设备上的负载
// 1) 禁视频自动播放   VideoAudioHelper 全局自动播放闸门短路, 点击才播
// 2) 禁视频预加载     PreLoadVideoSwitchManager 总闸判负 + DuMediaPrefetch 媒体预取短路
// 3) GIF 静态化       GifView 改载 CDN 首帧静图, 停止动图循环解码
// 4) 减弱动画         页面过场改 (0,0) 无动画 + 列表滑动入场动画关闭
// 5) 降低图片请求质量 JPEG 质量档降为流量档 + 请求尺寸上限 720, CDN 出小图
// 6) 超低配模式       篡改宿主低分设备判定阈值与配置串, 让官方低分降级机制强制生效
// 所有目标均为宿主非混淆类(com.baidu.tbadk.*/com.baidu.adp.*), 无需反混淆规则;
// 方法名本身可能是混淆产物(如 l0), 随宿主版本漂移时按区静默降级, 互不影响
//
// 激活模型: 主键 low_spec_mode 为 hooker 注册激活门, 各子键为运行时分项开关;
// 子项在回调内实时读取, 模块设置页点击主开关: 关闭状态弹出配置弹窗,
// 开启状态直接关闭; 主键状态 = 任一子项开启, 由弹窗保存时统一落盘
@Suppress("DEPRECATION")
class LowSpecMode : XposedContext(), IHooker {

    override fun key(): String {
        return "low_spec_mode"
    }

    override fun hook() {
        // 注册开关缓存失效监听: SP 内部弱引用持有监听器, 属性强引用防 GC 后缓存永不失效
        try {
            gm.tieba.tabswitch.dao.Preferences.registerOnSharedPreferenceChangeListener(sPrefsListener)
        } catch (ignored: Throwable) {
        }
        // 分区隔离: 单项目标缺失/漂移只损失该项, 不阻断其余各项
        try {
            disableVideoAutoPlay()
        } catch (tr: Throwable) {
            logBoth("LowSpecMode: 禁视频自动播放加载失败: $tr")
        }
        try {
            blockVideoPreload()
        } catch (tr: Throwable) {
            logBoth("LowSpecMode: 禁视频预加载加载失败: $tr")
        }
        try {
            staticizeGif()
        } catch (tr: Throwable) {
            logBoth("LowSpecMode: GIF 静态化加载失败: $tr")
        }
        try {
            weakenAnimation()
        } catch (tr: Throwable) {
            logBoth("LowSpecMode: 减弱动画加载失败: $tr")
        }
        try {
            downgradeImageQuality()
        } catch (tr: Throwable) {
            logBoth("LowSpecMode: 降低图片质量加载失败: $tr")
        }
        try {
            forceHostLowEndMode()
        } catch (tr: Throwable) {
            logBoth("LowSpecMode: 超低配模式(官方低配)加载失败: $tr")
        }
    }

    // 主开关为注册激活门, 子键决定分项是否实际生效; 运行时在弹窗里增减子项
    // 即时生效(缓存经偏好变更监听失效), 无需重启贴吧
    //
    // 子项开关缓存: isEnabled 挂在图片 URL 拼接/资源加载/宿主 SP 读取等高频钩点上,
    // 每次实时读 SP(两次 map 查找)的开销会被乘到最高频点; 缓存后热路径只剩一次
    // ConcurrentHashMap 读。任意偏好键变更即整体失效(设置页改动低频, 清空重建
    // 比逐键比对更简单), 读路径零锁
    private val sSubKeyCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val sPrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            sSubKeyCache.clear()
        }

    private fun isEnabled(subKey: String): Boolean {
        sSubKeyCache[subKey]?.let { return it }
        val enabled = getBoolean("low_spec_mode") && getBoolean(subKey)
        sSubKeyCache[subKey] = enabled
        return enabled
    }

    private fun disableVideoAutoPlay() {
        // VideoAudioHelper 是宿主自动播放的全局单点闸门(首页/进吧/帖子页共用),
        // 点击播放不走 checkNeedAutoPlay, 短路不误伤手动播放
        val helper = findClass("com.baidu.tbadk.util.VideoAudioHelper")
        helper.declaredMethods.forEach { method ->
            if (method.returnType != Boolean::class.javaPrimitiveType) return@forEach
            when (method.name) {
                "checkNeedAutoPlay", "isLocalSettingCanAutoPlay" -> hookBeforeMethod(method) { param ->
                    if (isEnabled("low_spec_video")) {
                        param.setResult(false)
                    }
                }
            }
        }
    }

    // 禁视频预加载: 自动播放被禁后宿主仍会在后台预载视频(流量+内存), 补上才闭环。
    // PreLoadVideoSwitchManager.isOpen() 是预载总闸, DuMediaPrefetch.prefetch/preConnect
    // 是 CyberPlayer 播放器的媒体预取入口(静态 void, 直接短路)
    private fun blockVideoPreload() {
        val switchManager = findClass("com.baidu.tbadk.core.util.videoPreload.PreLoadVideoSwitchManager")
        switchManager.declaredMethods.forEach { method ->
            if (method.name == "isOpen" &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Boolean::class.javaPrimitiveType
            ) {
                hookBeforeMethod(method) { param ->
                    if (isEnabled("low_spec_video_preload")) {
                        param.setResult(false)
                    }
                }
            }
        }

        val prefetch = findClass("com.baidu.cyberplayer.sdk.DuMediaPrefetch")
        val configClass = prefetch.declaredClasses.firstOrNull {
            it.name.endsWith("DuMediaPrefetch\$PrefetchConfig")
        }
        if (configClass == null) return
        prefetch.declaredMethods.forEach { method ->
            if (method.name in setOf("prefetch", "preConnect") &&
                java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                method.parameterTypes[2] == configClass
            ) {
                hookBeforeMethod(method) { param ->
                    if (isEnabled("low_spec_video_preload")) {
                        param.setResult(null)
                    }
                }
            }
        }
    }

    private fun staticizeGif() {
        // GifView.l0(GifInfo) 内部按宿主动图开关二选一加载 mDynamicUrl/mStaticUrl:
        // 开启时把动态 URL 改写为静态 URL(CDN GIF 首帧), 网络体积与解码内存同降;
        // mStaticUrl 为空(小尾巴表情等无静图来源)则放行, 避免白图
        val gifView = findClass("com.baidu.tbadk.gif.GifView")
        gifView.declaredMethods.forEach { method ->
            if (method.name == "l0" && method.parameterTypes.size == 1 &&
                method.parameterTypes[0].name == "com.baidu.tbadk.gif.GifInfo"
            ) {
                hookBeforeMethod(method) { param ->
                    if (!isEnabled("low_spec_gif")) return@hookBeforeMethod
                    val info = param.args[0] ?: return@hookBeforeMethod
                    val staticUrl = XposedHelpers.getObjectField(info, "mStaticUrl") as? String
                    if (!staticUrl.isNullOrEmpty()) {
                        XposedHelpers.setObjectField(info, "mDynamicUrl", staticUrl)
                    }
                }
            }
        }
    }

    private fun weakenAnimation() {
        // 页面过场: 与 auto/TransitionAnimation hook 同两个方法。该 hooker 以
        // XC_MethodReplacement 挂钩, 同方法上先行回调短路后(returnEarly)后续回调
        // 不再执行, 故这里必须以 PRIORITY_HIGHEST 的 before 钩子先行:
        // 开启时改参为 (0,0) 并短路, 双开时覆盖其自定义过场; 关闭时不触碰参数,
        // TransitionAnimation 行为完全不受影响, 无需代码互斥。
        // 不沿用 TransitionAnimation 的版本门槛, overridePendingTransition(0,0) 全版本可用
        val factory = findClass("com.baidu.tbadk.ActivityPendingTransitionFactory")
        listOf("enterExitAnimation", "closeAnimation").forEach { name ->
            val transitionMethod = XposedHelpers.findMethodExactIfExists(
                factory, name,
                "com.baidu.tbadk.TbPageContext", Int::class.javaPrimitiveType
            ) ?: return@forEach
            hookBeforeHighest(transitionMethod) { param ->
                if (!isEnabled("low_spec_anim")) return@hookBeforeHighest
                val pageContext = param.args[0] ?: return@hookBeforeHighest
                // getPageActivity 在页面切换/销毁窗口期可为 null
                val activity =
                    XposedHelpers.callMethod(pageContext, "getPageActivity") as? Activity
                        ?: return@hookBeforeHighest
                activity.overridePendingTransition(0, 0)
                param.setResult(null)
            }
        }

        // 列表滑动入场动画: 宿主云控开关的本地判定, 直接判负
        val singleton = findClass("com.baidu.tbadk.TbSingleton")
        singleton.declaredMethods.forEach { method ->
            if (method.name == "isSlideAnimEnable" &&
                method.parameterTypes.isEmpty() &&
                method.returnType == Boolean::class.javaPrimitiveType
            ) {
                hookBeforeMethod(method) { param ->
                    if (isEnabled("low_spec_anim")) {
                        param.setResult(false)
                    }
                }
            }
        }
    }

    private fun downgradeImageQuality() {
        // JPEG 质量档: 宿主按网络类型返回 "45"/"80", 统一压到流量档 "45"
        // (作用于图片 URL 拼接的 &qulity=, 宿主原始拼写, 点开大图查看器同样生效,
        // 属低配模式的预期取舍)
        val imageHelper = findClass("com.baidu.tbadk.core.util.TbImageHelper")
        imageHelper.declaredMethods.forEach { method ->
            if (method.name == "getUrlQuality" &&
                method.parameterTypes.isEmpty() &&
                method.returnType == String::class.java
            ) {
                hookBeforeMethod(method) { param ->
                    if (isEnabled("low_spec_image")) {
                        param.setResult("45")
                    }
                }
            }
        }

        // 请求尺寸: loadResourceHighPriority/loadResource 的第 4/5 参为期望宽高,
        // 进入 URL 的 &width=/&height= 由 CDN 服务端裁剪出图, 同时降低网络体积
        // 与解码位图内存; 0 表示无约束不改动
        val resourceLoader = findClass("com.baidu.adp.lib.resourceLoader.BdResourceLoader")
        val maxSide = 720
        resourceLoader.declaredMethods.forEach { method ->
            val types = method.parameterTypes
            if (method.name in setOf("loadResourceHighPriority", "loadResource") &&
                types.size >= 5 &&
                types[3] == Int::class.javaPrimitiveType &&
                types[4] == Int::class.javaPrimitiveType
            ) {
                hookBeforeMethod(method) { param ->
                    if (!isEnabled("low_spec_image")) return@hookBeforeMethod
                    for (i in 3..4) {
                        val side = param.args[i] as? Int ?: continue
                        if (side > maxSide) {
                            param.args[i] = maxSide
                        }
                    }
                }
            }
        }
    }

    // XposedContext 的 hook 辅助不开放优先级, 这里直接构造高优先级 before 钩子;
    // 异常处理与辅助封装一致: 落盘后吞掉, 不改变宿主原调用流程
    private fun hookBeforeHighest(
        method: java.lang.reflect.Method,
        beforeHook: (XC_MethodHook.MethodHookParam) -> Unit
    ) {
        XposedBridge.hookMethod(
            method,
            object : XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        beforeHook(param)
                    } catch (tr: Throwable) {
                        logHookError("before", tr)
                    }
                }
            }
        )
    }

    // 官方低配策略: 宿主自带低分设备降级机制(LowScoreScheduler), 正常仅低分设备触发。
    // 这里把判定阈值读成 100.0(任何设备都低于阈值)并改写低分配置串, 官方整套降级
    // 逻辑(任务延迟、预加载裁剪、动画简化等)随之全量生效, 相当于逐点 hook 的兜底网。
    // MultiSharedPrefHelper 的静态 get 有多个重载且被全应用高频调用, 因此只对
    // 目标 key 短路, 其余调用一律放行, 修饰符按 public static 校验
    // (另: 设备分强制判负需 DexKit 才能安全定位 ScheduleStrategy 目标方法, 不做,
    // 阈值拉满已足够触发低分判定)
    private fun forceHostLowEndMode() {
        val helper = findClass("com.baidu.adp.baes.sharedperf.MultiSharedPrefHelper")
        helper.declaredMethods.forEach { method ->
            val modifiers = method.modifiers
            if (!java.lang.reflect.Modifier.isStatic(modifiers) ||
                !java.lang.reflect.Modifier.isPublic(modifiers)
            ) {
                return@forEach
            }
            val types = method.parameterTypes
            // 阈值: float get(String, float) -> SharedPreferences.getFloat
            if (method.returnType == Float::class.javaPrimitiveType &&
                types.size == 2 && types[0] == String::class.java &&
                types[1] == Float::class.javaPrimitiveType
            ) {
                hookBeforeMethod(method) { param ->
                    // 先做 key 比对再读开关: 该 get 重载被全应用高频调用,
                    // 不相关 key 的读取不应替模块白付开关查询开销
                    if (param.args[0] == "sp_mid_score_device_config" &&
                        isEnabled("low_spec_host_low_end")
                    ) {
                        param.setResult(100.0f)
                    }
                }
            }
            // 低分配置串: String get(String, String) -> SharedPreferences.getString
            if (method.returnType == String::class.java &&
                types.size == 2 && types[0] == String::class.java &&
                types[1] == String::class.java
            ) {
                hookBeforeMethod(method) { param ->
                    // 同上: 先 key 比对(最廉价的字符串引用比较)再读开关
                    if (param.args[0] != "low_score_block_list" &&
                        param.args[0] != "low_score_kv_config"
                    ) return@hookBeforeMethod
                    if (!isEnabled("low_spec_host_low_end")) return@hookBeforeMethod
                    when (param.args[0]) {
                        "low_score_block_list" -> param.setResult(
                            blockList(param.args[1] as? String ?: "")
                        )
                        "low_score_kv_config" -> param.setResult(
                            keyValues(param.args[1] as? String ?: "")
                        )
                    }
                }
            }
        }
    }

    // low_score_block_list 为字符串数组: 保留宿主原有降级项并追加图片预加载禁用
    // 与 WebView 代理禁用, 幂等(重复注入前先按已有元素去重)
    private fun blockList(original: String): String {
        val source = try {
            org.json.JSONArray(original)
        } catch (_: Exception) {
            org.json.JSONArray()
        }
        val result = org.json.JSONArray()
        val existing = HashSet<String>()
        for (i in 0 until source.length()) {
            val item = source.opt(i)
            result.put(item)
            if (item is String) existing.add(item)
        }
        if (existing.add("disable_preload_feed_image")) result.put("disable_preload_feed_image")
        if (existing.add("disable_webview_proxy")) result.put("disable_webview_proxy")
        return result.toString()
    }

    // low_score_kv_config 为键值对象: 禁止空闲任务重跑 + 视频自动播放延迟 5 秒
    // (与"禁视频自动播放"互补: 全禁短路失败的路径退化为延迟自动播放)
    private fun keyValues(original: String): String {
        val result = try {
            org.json.JSONObject(original)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        result.put("disable_re_run_idle", "true")
        result.put("defer_video_autoplay_ms", "5000")
        return result.toString()
    }

    companion object {

        // 子功能注册表: key/标签, 顺序即弹窗展示顺序
        private val SUB_FEATURES = listOf(
            "low_spec_video" to "禁视频自动播放",
            "low_spec_video_preload" to "禁视频预加载",
            "low_spec_gif" to "GIF 静态化",
            "low_spec_anim" to "减弱动画",
            "low_spec_image" to "降低图片质量",
            "low_spec_host_low_end" to "超低配模式"
        )

        // 配置弹窗: 与"自定义评论图标"同款(模块 Switch 行 + 保存/取消)。
        // 保存时统一落盘: 各子键 + 主键(任一子项开启), onSaved 回传主键新状态,
        // 供设置页同步主开关视觉(内层 BdSwitchView 触摸已被行点击消费, 不会自行切换)
        fun showConfigDialog(activity: Activity, onSaved: (Boolean) -> Unit) {
            val switchByKey = HashMap<String, Switch>()
            val listLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                // 上下留白对齐框架 setMessage 布局的文本区节奏(标题/按钮不贴行)
                setPadding(0, dipToPx(activity, 8f), 0, dipToPx(activity, 8f))
            }

            SUB_FEATURES.forEach { (key, label) ->
                val switch = Switch().apply {
                    // 首次配置(从未保存过)默认全关, 之后沿用上次保存的各子项状态
                    if (getBoolean(key)) turnOn() else turnOff()
                }
                switchByKey[key] = switch
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dipToPx(activity, 48f)
                    // 24dp: 与框架 setMessage 文本内边距一致(框架窗口背景自带 16dp,
                    // 行再加 24dp 后文字恰好对齐标题/反混淆弹窗消息文本的 40dp 起点)
                    setPaddingRelative(dipToPx(activity, 24f), 0, dipToPx(activity, 24f), 0)
                }
                row.addView(TextView(activity).apply {
                    text = label
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
                .setTitle("低配模式")
                .setView(listLayout)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create()
            alert.setOnShowListener {
                // 正向按钮点击置空后重挂, 避免未校验时系统默认直接 dismiss
                alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    var anyOn = false
                    val wasOn = getBoolean("low_spec_mode")
                    SUB_FEATURES.forEach { (key, _) ->
                        val on = switchByKey.getValue(key).isOn()
                        putBoolean(key, on)
                        if (on) anyOn = true
                    }
                    putBoolean("low_spec_mode", anyOn)
                    alert.dismiss()
                    // 主键从关到开: 本进程 hooker 可能未注册, 子项未必即时生效
                    if (!wasOn && anyOn) {
                        TbToast.showTbToast("已开启，如未生效请重启贴吧", TbToast.LENGTH_SHORT)
                    }
                    onSaved(anyOn)
                }
            }
            alert.show()
            fixAlertDialogWidth(alert)
        }
    }
}
