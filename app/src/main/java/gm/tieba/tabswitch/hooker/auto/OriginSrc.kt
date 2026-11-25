package gm.tieba.tabswitch.hooker.auto

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.logBoth
import org.json.JSONObject

class OriginSrc : XposedContext(), IHooker {

    override fun key(): String {
        return "origin_src"
    }

    @SuppressLint("MissingPermission")
    override fun hook() {
        if (getBoolean("origin_src_only_wifi")) {
            // 注册去重: 回调伴随进程生命周期, hook() 重复调用会叠加注册多个
            // 回调实例(网络事件逐个派发, doHook/doUnHook 被重复执行)
            if (!sNetworkCallbackRegistered.compareAndSet(false, true)) return
            val networkCallback = NetworkCallbackImpl()
            val builder = NetworkRequest.Builder()
            val request = builder.build()
            val connMgr = getContext().getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as ConnectivityManager
            // 注册前先按当前网络状态同步一次: 已处于 WiFi 时不必等下一次网络事件才生效
            try {
                connMgr.activeNetwork?.let { network ->
                    connMgr.getNetworkCapabilities(network)?.let { caps ->
                        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                            && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        ) {
                            doHook()
                        } else {
                            doUnHook()
                        }
                    }
                }
            } catch (tr: Throwable) {
                // 初始状态判定失败时既不 hook 也不 unhook, 功能停在无关状态直到
                // 下一次网络事件, 与全文件"日志留痕"风格保持一致
                logBoth("OriginSrc: 初始网络状态判定失败 -- $tr")
            }
            connMgr.registerNetworkCallback(request, networkCallback)
        } else {
            doHook()
        }
    }

    private class NetworkCallbackImpl : NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            ) {
                doHook()
            } else {
                doUnHook()
            }
        }
    }

    companion object {

        private val unhookList = mutableListOf<XC_MethodHook.Unhook>()
        // NetworkCallback 是否已注册(hook 重复调用去重, 注册后伴随进程生命周期)
        private val sNetworkCallbackRegistered = java.util.concurrent.atomic.AtomicBoolean(false)
        // WiFi 回调运行在系统线程，doHook/doUnHook 需要互斥，避免竞态下重复 hook 或 ConcurrentModificationException。
        // unhookList 的增删必须与 sHooked 判定同锁, 否则网络抖动时 doUnHook 清空列表
        // 与 doHook 的 add 交错, 产生"WiFi 已断钩子仍生效"或反向的错位
        private val sLock = Any()
        private var sHooked = false

        // 钩点首次失败(宿主字段漂移)后永久跳过: 否则每次调用都抛
        // NoSuchFieldError, 被包装层逐条记录, 刷屏且持续付出反射开销
        private val sBrokenHooks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        private fun guard(key: String, block: () -> Unit) {
            if (sBrokenHooks.contains(key)) return
            try {
                block()
            } catch (t: Throwable) {
                sBrokenHooks.add(key)
                logBoth("OriginSrc: $key 失效降级 -- $t")
            }
        }

        private fun doHook() {
            synchronized(sLock) {
                if (sHooked) return
                // 网络回调(binder 线程)无外层兜底: 注册失败(类/签名漂移)的 Error
                // 若在此抛出会未捕获击穿宿主进程, 且每次 WiFi 状态变化重复触发。
                // 整体与逐点双重防护: 单个钩点漂移只损失自身, 其余照常生效;
                // 全部失败时不置位, 保留网络回调再触发时的重试能力
                fun register(tag: String, body: () -> XC_MethodHook.Unhook) {
                    try {
                        unhookList.add(body())
                    } catch (t: Throwable) {
                        logBoth("OriginSrc: $tag 注册失败(跳过, 其余钩点不受影响) -- $t")
                    }
                }

                register("pb_viewer_url") {
                    // 查看器取图函数: 静态方法 c(PbContent), 按 cdn_src > src >
                    // big_cdn_src > origin_src 的优先级返回查看器主图 URL, 仅被
                    // 查看器数据构建的同文件 a(PostData, int) 调用, 楼层不经过它。
                    // 类名随版本漂移(f7e/kwd/u2e/y4e, 22.15 起为 l8e, 方法名与签名
                    // 22.11-22.15 一致, 已逐一在 jadx 反编译源核对), 这里逐个探测;
                    // findAndHookMethod 按参数类型精确匹配, 类存在但签名漂移时抛异常落到下一个候选
                    val pbContentClass = XposedHelpers.findClass("tbclient.PbContent", sClassLoader)
                    var lastFailure: Throwable? = null
                    for (candidate in PB_VIEWER_URL_CLASS_CANDIDATES) {
                        val clazz = XposedHelpers.findClassIfExists(candidate, sClassLoader)
                            ?: continue
                        try {
                            return@register hookBeforeMethod(
                                clazz, "c", pbContentClass
                            ) { param ->
                                guard("pb_viewer_url") {
                                    // 返回值换成 origin_src 即"点开即原图"。宿主原生
                                    // cdn_src 是服务端预缩放变体(如 whfpf=720,4096;q=80),
                                    // 只适合楼层; 全尺寸原图在查看器加载链上可正常显示
                                    val originSrc =
                                        XposedHelpers.getObjectField(param.args[0], "origin_src")
                                            as? String
                                    if (!originSrc.isNullOrEmpty()) {
                                        param.setResult(originSrc)
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            lastFailure = t
                        }
                    }
                    throw IllegalStateException(
                        "查看器取图函数未定位到(l8e/f7e/kwd/u2e/y4e 均未命中), 疑似宿主漂移 -- $lastFailure"
                    )
                }

                register("pic_info") {
                    val builderClass = XposedHelpers.findClass(
                        "tbclient.PicInfo\$Builder", sClassLoader
                    )
                    val originPicUrl = XposedHelpers.findField(builderClass, "origin_pic_url")
                    val targets = arrayOf("small_pic_url", "big_pic_url")
                        .map { XposedHelpers.findField(builderClass, it) }
                    hookBeforeMethod(
                        builderClass,
                        "build", Boolean::class.javaPrimitiveType,
                    ) { param ->
                        guard("pic_info") {
                            // origin_pic_url 未下发(部分图源无原图)时置空会弄丢缩略/大图,
                            // 与 pb_viewer_url 钩点一致: 空值跳过覆盖
                            val src = originPicUrl.get(param.thisObject) as? String
                            if (!src.isNullOrEmpty()) {
                                targets.forEach { it.set(param.thisObject, src) }
                            }
                        }
                    }
                }

                register("feed_pic_component") {
                    val builderClass = XposedHelpers.findClass(
                        "tbclient.FeedPicComponent\$Builder", sClassLoader
                    )
                    val schemaField = XposedHelpers.findField(builderClass, "schema")
                    hookBeforeMethod(
                        builderClass,
                        "build", Boolean::class.javaPrimitiveType,
                    ) { param ->
                        // 该 hook 位于信息流解析路径，任何异常都会击穿宿主，整体包裹
                        try {
                            val schema = schemaField.get(param.thisObject) as? String
                            // 廉价预筛: 无 params 参数的 schema 直接跳过 Uri 解析与
                            // JSON 重建(绝大多数 feed 项), 字面量 "params=" 是
                            // query 键的实际存在形态(键不做百分号编码)
                            val paramsJson = schema
                                ?.takeIf { it.contains("params=") }
                                ?.let { Uri.parse(it).getQueryParameter("params") }

                            paramsJson?.let { schemaParams ->
                                try {
                                    val jsonObject = JSONObject(schemaParams)
                                    val pageParams = jsonObject.optJSONObject("pageParams")
                                        ?: return@let
                                    val picDataList = pageParams.optJSONArray("pic_data_list")
                                        ?: return@let
                                    for (i in 0 until picDataList.length()) {
                                        val picData = picDataList.optJSONObject(i) ?: continue
                                        // 缺 origin_pic_url 键时 optString 返回 "", 用它覆盖
                                        // big/small_pic_url 会把图源清成空白, 跳过该条目
                                        val originPicUrl = picData.optString("origin_pic_url")
                                        if (originPicUrl.isEmpty()) continue
                                        picData.put("big_pic_url", originPicUrl)
                                        picData.put("small_pic_url", originPicUrl)
                                        picData.put("is_show_origin_btn", 0)
                                    }
                                    // 保留原 schema 基底与除 params 外的全部 query 参数, 只替换 params。
                                    // Uri.Builder 没有 setQueryParameter, 只能清空 query 后按原参数
                                    // 重建; appendQueryParameter 写回会重新编码, 否则 JSON 内的
                                    // &/+/# 会破坏路由参数
                                    val oldUri = Uri.parse(schema)
                                    val newBuilder = oldUri.buildUpon().query(null)
                                    for (key in LinkedHashSet(oldUri.queryParameterNames)) {
                                        if (key == "params") continue
                                        for (value in oldUri.getQueryParameters(key)) {
                                            newBuilder.appendQueryParameter(key, value)
                                        }
                                    }
                                    newBuilder.appendQueryParameter("params", jsonObject.toString())
                                    schemaField.set(
                                        param.thisObject, newBuilder.build().toString()
                                    )
                                } catch (_: Exception) {
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                // 仅在实际挂上钩子时置位: 保留网络回调再触发时的重试能力
                if (unhookList.isNotEmpty()) {
                    sHooked = true
                }
            }
        }

        private fun doUnHook() {
            synchronized(sLock) {
                if (!sHooked) return
                sHooked = false
                unhookList.forEach { runCatching { it.unhook() } }
                unhookList.clear()
            }
        }
    }
}

private val PB_VIEWER_URL_CLASS_CANDIDATES = listOf(
    // 22.11~22.15 的查看器取图类(仅类名漂移, 22.15=f7e→l8e, a(PostData,int)/
    // c(PbContent) 方法布局逐字同构); 旧名(如 22.14 的 y4e)被宿主回收复用为
    // 无关节点时, 类存在但 c(PbContent) 已不存在,
    // findAndHookMethod 按参数精确匹配自动落空到下一候选)
    "com.baidu.tieba.l8e",
    "com.baidu.tieba.f7e",
    "com.baidu.tieba.kwd",
    "com.baidu.tieba.u2e",
    "com.baidu.tieba.y4e"
)
