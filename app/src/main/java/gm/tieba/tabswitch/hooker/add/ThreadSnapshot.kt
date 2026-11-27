package gm.tieba.tabswitch.hooker.add

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.XposedContext.Companion.findClass
import gm.tieba.tabswitch.XposedContext.Companion.hookAfterMethod
import gm.tieba.tabswitch.XposedContext.Companion.runOnUiThread
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.dao.Preferences.getString
import gm.tieba.tabswitch.dao.ThreadSnapshotStore
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hookSafely
import gm.tieba.tabswitch.util.Logger
import gm.tieba.tabswitch.widget.TbToast
import java.util.concurrent.atomic.AtomicBoolean

// 帖子浏览缓存: 功能开启后每次成功进入帖子, 把该次 PbPage 响应的
// 完整原始字节落入 ThreadSnapshotStore; 帖子被删/失效后再次打开时, 宿主响应
// error!=0, 此时用缓存字节重走宿主自己的解析转换管线——"旧帖还能看"只是数据
// 来源从网络换成本地。
//
// 22.14 实证链路(jadx_2214origin, 2026-11-25 模拟器联调三轮定位):
// - 进帖主链路走 PageBrowser 新 HTTP 栈: xtd(std 实现) → wtd → qud(聚合) →
//   lud(PbFull 首屏请求, kud 基类) → lud$a.convert(byte[],str) 唯一解析点,
//   URL "c/f/pb/firstPage?cmd=309751&format=protobuf"; 翻页/重载走兄弟类
//   mud(URL c/f/pb/page)。响应是 PbListResIdl(非 PbPageResIdl), parse 后
//   经 gud.b() JSON 桥转 PbPageResIdl;
// - 旧管道(cmd 302001 的 pbPageHttpResponseMessage / pbPageSocketResponseMessage
//   decode)在 22.14 实机零首屏流量(2026-11-25 实证), 仅作 12.x~22.13 老宿主
//   兼容腿保留。
//
// 回放机制(核心): convert 的 after 钩子里, 响应 error!=0 且本地有该帖快照 →
// 直接对同一 converter 以缓存字节重调 convert。快照存的就是 PbListResIdl
// 完整响应字节(含 error 头), 喂回后 gud.b() 转换/宿主渲染链零感知——与
// "服务端返回了旧内容"效果完全一致, 无任何字节重组。
//
// 快照语义(一期定版): **首次成功响应为准, 后续响应不覆盖**——进帖首屏页含
// 完整 thread 信息+第一页楼层, 回放展示"进帖那一刻"的定格内容。所有版本统一
// 首屏定格: 后台全量下载机件已整体移除——其翻页请求经 MessageManager 自派
// (AGENTS.md 自派请求教训), 且页 2+ 响应回到 handleLegacyDecode 时被
// contains(tid) 短路、从未落库, 任务循环永不闭环(sActiveDownloads 泄漏),
// 行为等价于只有首屏, 机件纯为空转残件(2026-11-25 审查拆除)。
//
// 模块不编译期依赖宿主类: 全部宿主类型经 XposedHelpers/findClass 反射操作,
// 与 PurgeAdPostPage 同口径。
class ThreadSnapshot : XposedContext(), IHooker {

    override fun key(): String {
        return "thread_snapshot"
    }

    companion object {
        // 宿主锚点(22.14 实证, 2026-11-25 模拟器联调三轮定位; 2026-11-25 反编译核对 22.15):
        // 22.14 首屏 PbPage 数据链 = xtd(std 实现) → wtd → qud(聚合) →
        // lud(PbFull 首屏请求, kud 基类) → lud$a.convert(唯一解析点,
        // parse PbListResIdl → gud.b 转 PbPageResIdl); 22.15 同链路轮换为
        // nvd(mvd 基类, "PbFirstRequestModel", URL c/f/pb/firstPage 逐字同构)。
        // 诊断实证: gud.b 有流量而 ajd$a.convert 零触发——请求模型是 lud 不是 ajd
        // (ajd 是同构的旧变体, 运行时不走)。mud 是 lud 的兄弟变体(重载/翻页),
        // 22.15 对应 ovd("PbFullRequestModel", URL c/f/pb/page), 一并挂上。
        // 混淆类名随版本轮换, 漂移时 hookSafely 留痕、功能降级失效
        private val PB_FULL_CONVERTERS = arrayOf(
            "com.baidu.tieba.nvd\$a", "com.baidu.tieba.lud\$a"
        )
        private val PB_PAGE_CONVERTERS = arrayOf(
            "com.baidu.tieba.ovd\$a", "com.baidu.tieba.mud\$a"
        )

        // 旧管道腿(22.14+ 仅承载少量流量, 兼容 12.x~22.13 全版本主链路):
        private const val PB_HTTP_RESPONSE = "com.baidu.tieba.pb.pb.main.pbPageHttpResponseMessage"
        private const val PB_SOCKET_BASE = "com.baidu.adp.framework.message.SocketResponsedMessage"
        private const val PB_PAGE_REQ = "com.baidu.tieba.pb.PbPageRequestMessage"
        private const val PB_PAGE_CMD = 302001

        // "已展示本地缓存"提示进程级单发: 翻页/重试不重复打扰
        private val sReplayToastShown = AtomicBoolean(false)

        // 回放重入守卫: 重调 convert 会再次进入 after 钩子, 标记位置使
        // 第二次不再触发回放判定(防递归)。get() 在 android.jar 桩里标了可空
        // (同 Fs9Api/CardParamsApi 的 get()!! 口径), 判定点用 == true 兜 null
        private val sInReplay = object : ThreadLocal<Boolean>() {
            override fun initialValue(): Boolean = false
        }
    }

    override fun hook() {
        // 设置侧的容量值在功能开启时对齐一次; 设置弹窗保存时直接改
        // ThreadSnapshotStore.capacity 实时生效
        ThreadSnapshotStore.capacity = resolveCapacity()

        // ---------- 捕获 + 回放: 新栈转换器(22.14/22.15 首屏主链路) ----------
        // tid/标题均从响应体 data.thread 字段直取(proto 公开字段无 getter,
        // 见 captureFull), 不依赖内部类的 synthetic 外部引用字段。
        // 类名按版本逐候选探测: 新名在前, 旧宿主自动落到旧名
        for (conv in PB_FULL_CONVERTERS) {
            hookSafely("thread_snapshot", "pb-full-convert($conv)") {
                hookAfterMethod(conv, "convert",
                    ByteArray::class.java, String::class.java) { param ->
                    handleFullConvert(param)
                }
            }
        }
        for (conv in PB_PAGE_CONVERTERS) {
            hookSafely("thread_snapshot", "pb-page-convert($conv)") {
                hookAfterMethod(conv, "convert",
                    ByteArray::class.java, String::class.java) { param ->
                    handleFullConvert(param)
                }
            }
        }

        // ---------- 兼容腿: 旧管道 HTTP ----------
        hookSafely("thread_snapshot", "pb-http-decode") {
            hookAfterMethod(PB_HTTP_RESPONSE, "decodeInBackGround",
                Int::class.javaPrimitiveType, ByteArray::class.java) { param ->
                handleLegacyDecode(param, isSocket = false)
            }
        }

        // ---------- 兼容腿: 旧管道 Socket ----------
        // pbPageSocketResponseMessage 未覆写 decodeInBackGround(从基类继承),
        // hook 基类公共入口按 cmd 过滤; 子类按名 hook 会 NoSuchMethod 被吞
        // (2026-11-25 实证)
        hookSafely("thread_snapshot", "pb-socket-decode") {
            hookAfterMethod(PB_SOCKET_BASE, "decodeInBackGround",
                Int::class.javaPrimitiveType, ByteArray::class.java) { param ->
                handleLegacyDecode(param, isSocket = true)
            }
        }
    }

    private fun isEnabled(): Boolean = getBoolean("thread_snapshot")

    private fun resolveCapacity(): Int {
        val raw = getString("thread_snapshot_capacity")?.toIntOrNull() ?: return 100
        return raw.coerceIn(1, 1000)
    }

    // ---------- 新栈钩点: nvd$a/lud$a、ovd$a/mud$a.convert ----------
    private fun handleFullConvert(param: XC_MethodHook.MethodHookParam) {
        if (sInReplay.get() == true) return
        if (!isEnabled()) return
        runCatching {
            val rawBytes = param.args[0] as? ByteArray ?: return
            if (rawBytes.isEmpty()) return
            // parse 一次双用: errorno 判定(捕获 vs 回放) + 捕获路径的 tid/标题
            // 提取, 避免整包响应二次 parse(单帖响应百 KB 级)
            val idl = parsePbList(rawBytes)
            if (idl == null) {
                // parse 失败(类漂移/响应形状变化): 既不是成功也不是错误,
                // 放行宿主原结果, 不捕获也不回放——回放判定依赖 errorno,
                // 误判会把正常翻页响应配错快照
                return
            }
            val errorno = peekErrorno(idl)
            if (errorno == 0) {
                captureFull(rawBytes, idl)
            } else {
                replayIfNeeded(param, errorno, idl)
            }
        }.onFailure {
            Logger.log("缓存钩点处理失败(full): $it")
        }
    }

    // Wire 解析器与反射元数据按哨兵负缓存模式缓存(同 PurgeAdPostPage 口径):
    // parsePbList 挂在每个 Pb 响应的 convert after 钩子上(首屏+翻页每响应一次),
    // 每次都 findClass+getDeclaredConstructor 是热路径重复反射。类缺失(老宿主
    // 无 com.squareup.wire.Wire)按"解析失败"置位不再重试, 与宿主升级新进程自然恢复
    private val WIRE_UNRESOLVED_SENTINEL: Class<*> = ThreadSnapshot::class.java

    @Volatile
    private var sWireClass: Class<*>? = WIRE_UNRESOLVED_SENTINEL

    @Volatile
    private var sWireCtor: java.lang.reflect.Constructor<*>? = null

    private fun resolveWireCtor(): java.lang.reflect.Constructor<*>? {
        sWireCtor?.let { return it }
        if (sWireClass === WIRE_UNRESOLVED_SENTINEL) {
            sWireClass = try {
                findClass("com.squareup.wire.Wire")
            } catch (ignored: Throwable) {
                null
            }
        }
        val clazz = sWireClass ?: return null
        return try {
            clazz.getDeclaredConstructor(arrayOf<Class<*>>().javaClass).also { sWireCtor = it }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun parsePbList(bytes: ByteArray): Any? = runCatching {
        val ctor = resolveWireCtor() ?: return null
        XposedHelpers.callMethod(
            ctor.newInstance(arrayOf<Class<*>>()), "parseFrom", bytes,
            findClass("tbclient.PbList.PbListResIdl"))
    }.getOrNull()

    // errorno 取自已 parse 的 PbListResIdl: error 缺失(部分响应无 error 字段)
    // 视为成功; errorno 字段缺失同样按 0(wire 默认值语义)
    private fun peekErrorno(idl: Any): Int {
        return runCatching {
            val error = XposedHelpers.getObjectField(idl, "error") ?: return 0
            (XposedHelpers.getObjectField(error, "errorno") as? Int) ?: 0
        }.getOrDefault(-1)
    }

    // 捕获: 原始 PbListResIdl 字节整块落库(含 error 头, 回放时无需重组)。
    // tid 从响应体 data.thread.id 取(proto 字段直读, 无 getter——
    // ThreadInfo#getId 不存在, XposedHelpers.getObjectField 走字段); 标题同源。
    // 响应体是 tid 的权威来源(该页响应必对应其 thread), 不依赖内部类的
    // synthetic 外部引用字段(jadx 还原名 a/真实 dex 名可能漂移, 不可靠)。
    //
    // 快照语义(一期定版): **首次成功响应为准, 后续响应不覆盖**——进帖首屏页
    // 含完整 thread 信息+第一页楼层, 回放展示的是"进帖那一刻"的定格内容。
    // 用户后续手动翻页的响应(mud$a.convert 同样命中本钩点)不更新快照,
    // 所有版本统一首屏定格(全量后台下载机件已拆除, 见类头注释)。
    // put 的 INSERT OR REPLACE 配合此处的 contains 短路, 首屏后自然幂等
    private fun captureFull(rawBytes: ByteArray, idl: Any) {
        runCatching {
            val data = XposedHelpers.getObjectField(idl, "data") ?: return
            val thread = XposedHelpers.getObjectField(data, "thread")
            val realTid = thread?.let {
                XposedHelpers.getObjectField(it, "id")?.toString()
            }?.takeIf { it.isNotEmpty() && it != "0" } ?: return
            // 已有快照(首屏已落库) → 翻页/刷新响应直接跳过, 不覆盖首屏定格
            if (ThreadSnapshotStore.contains(realTid)) return
            // thread 经 realTid 的空值守卫已证明非空, 直接取标题
            val title = XposedHelpers.getObjectField(thread, "title") as? String ?: ""
            ThreadSnapshotStore.put(realTid, title, rawBytes)
        }.onFailure { Logger.log("缓存捕获失败(full): $it") }
    }

    // 回放: 用缓存字节重调 convert。快照存的就是完整 PbListResIdl 原始字节,
    // 直接喂回; gud.b() 转换与宿主渲染链零感知。
    // tid 从当前响应体取(回放前提是能 parse 出 thread.id 定位快照——
    // 错误响应通常也带 thread 上下文; parse 不出则放行原错误流程)。
    // idl 直接收 handleFullConvert 已 parse 的结果: 此前对同一 rawBytes 二次
    // parse(单帖响应百 KB 级), 纯属重复
    private fun replayIfNeeded(
        param: XC_MethodHook.MethodHookParam,
        errorno: Int,
        idl: Any
    ) {
        val tid = runCatching {
            val data = XposedHelpers.getObjectField(idl, "data") ?: return
            val thread = XposedHelpers.getObjectField(data, "thread")
            thread?.let { XposedHelpers.getObjectField(it, "id")?.toString() }
                ?.takeIf { it.isNotEmpty() && it != "0" }
        }.getOrNull() ?: return
        if (!ThreadSnapshotStore.contains(tid)) return
        runCatching {
            val snapshot = ThreadSnapshotStore.get(tid) ?: return
            sInReplay.set(true)
            try {
                val convert = param.thisObject.javaClass.getMethod(
                    "convert", ByteArray::class.java, String::class.java)
                param.result = convert.invoke(param.thisObject, snapshot.data, param.args[1])
            } finally {
                sInReplay.set(false)
            }
            if (sReplayToastShown.compareAndSet(false, true)) {
                runOnUiThread {
                    TbToast.showTbToast("帖子已失效，已展示本地缓存", TbToast.LENGTH_LONG)
                }
            }
            Logger.log("缓存回放(full) tid=$tid srvErr=$errorno size=${snapshot.data.size}")
        }.onFailure { tr ->
            // 重调失败: 保留宿主原解析结果(错误流程), 仅留痕
            Logger.log("缓存回放失败(full) tid=$tid: $tr")
        }
    }

    // ---------- 兼容腿: 旧管道 decode(cmd 302001) ----------
    // 形状与主链路一致: 完整响应字节落库/回放, 数据形状同为 PbPageResIdl。
    // 22.14+ 首屏不走此链, 但 12.x~22.13 老宿主全靠它
    private fun handleLegacyDecode(param: XC_MethodHook.MethodHookParam, isSocket: Boolean) {
        if (sInReplay.get() == true) return
        if (!isEnabled()) return
        runCatching {
            val cmd = (param.args[0] as? Int) ?: return
            if (cmd != PB_PAGE_CMD) return
            val msg = param.thisObject ?: return
            val original = XposedHelpers.callMethod(msg, "getOrginalMessage") ?: return
            val req = XposedHelpers.callMethod(original, "getExtra") ?: return
            if (req.javaClass.name != PB_PAGE_REQ) return
            val rawBytes = param.args[1] as? ByteArray ?: return
            val kz = XposedHelpers.callMethod(req, "get_kz") as? Long ?: return
            val tid = kz.toString()
            if (tid == "0") return
            val error = XposedHelpers.callMethod(msg, "getError") as? Int ?: return
            if (error != 0) {
                if (!ThreadSnapshotStore.contains(tid)) return
                val snapshot = ThreadSnapshotStore.get(tid) ?: return
                sInReplay.set(true)
                try {
                    val decode = msg.javaClass.getMethod(
                        "decodeInBackGround", Int::class.javaPrimitiveType, ByteArray::class.java)
                    decode.invoke(msg, param.args[0], snapshot.data)
                } finally {
                    sInReplay.set(false)
                }
                if (sReplayToastShown.compareAndSet(false, true)) {
                    runOnUiThread {
                        TbToast.showTbToast("帖子已失效，已展示本地缓存", TbToast.LENGTH_LONG)
                    }
                }
                Logger.log("缓存回放(legacy) tid=$tid socket=$isSocket size=${snapshot.data.size}")
            } else {
                val pbData = XposedHelpers.callMethod(msg, "getPbData") ?: return
                // 首屏优先语义与新栈一致: 已有快照不覆盖(翻页/刷新响应跳过)
                if (ThreadSnapshotStore.contains(tid)) return
                // 标题提取独立防御: PbData 类 P0() 的返回类型随版本漂移
                // (22.12~22.15=ThreadData 有 getTitle; 22.11 的 rsc.P0() 返回
                // ThreadAlbumManage 无 getTitle)——不隔离会让 22.11 的 callMethod
                // 抛 NoSuchMethodException 冲掉外层 runCatching, 整个捕获静默死亡。
                // 提取失败仅损失标题, 落库保住
                val title = runCatching {
                    val threadData = XposedHelpers.callMethod(pbData, "P0")
                    threadData?.let { XposedHelpers.callMethod(it, "getTitle") as? String }
                }.getOrNull() ?: ""
                ThreadSnapshotStore.put(tid, title, rawBytes)
            }
        }.onFailure {
            Logger.log("缓存钩点处理失败(legacy socket=$isSocket): $it")
        }
    }

    object Ui {
        // 设置弹窗: 数量输入(1~1000)。保存时同步运行容量 + 落盘。
        // 由 TSPreference 扩展菜单的开关点击监听调起(开启确认弹窗, 同自动签到范式)
        fun showConfigDialog(activity: android.app.Activity, onSaved: (Int) -> Unit) {
            val current = getString("thread_snapshot_capacity") ?: "100"
            val input = android.widget.EditText(activity).apply {
                hint = "自动保存帖数(1-1000)"
                setText(current)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                if (!gm.tieba.tabswitch.util.isLightMode(getContext())) {
                    setTextColor(android.graphics.Color.WHITE)
                    setHintTextColor(android.graphics.Color.GRAY)
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = gm.tieba.tabswitch.util.dipToPx(activity, 20f)
                    rightMargin = gm.tieba.tabswitch.util.dipToPx(activity, 20f)
                }
            }
            val layout = android.widget.LinearLayout(activity).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                addView(input)
            }
            val alert = android.app.AlertDialog.Builder(
                activity, gm.tieba.tabswitch.util.getDialogTheme(getContext())
            )
                .setTitle("帖子浏览缓存数量")
                .setView(layout)
                .setNegativeButton(activity.getString(android.R.string.cancel), null)
                .setPositiveButton(activity.getString(android.R.string.ok), null)
                .create()
            alert.setOnShowListener {
                alert.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val value = input.text.toString().toIntOrNull()
                    if (value == null || value < 1 || value > 1000) {
                        TbToast.showTbToast("请输入 1-1000 之间的数字", TbToast.LENGTH_SHORT)
                        return@setOnClickListener
                    }
                    gm.tieba.tabswitch.dao.Preferences.putString("thread_snapshot_capacity", value.toString())
                    ThreadSnapshotStore.capacity = value
                    onSaved(value)
                    alert.dismiss()
                }
            }
            alert.show()
            gm.tieba.tabswitch.util.fixAlertDialogWidth(alert)
        }
    }
}
