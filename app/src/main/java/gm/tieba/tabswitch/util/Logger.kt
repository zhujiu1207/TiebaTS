package gm.tieba.tabswitch.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import gm.tieba.tabswitch.BuildConfig
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.dao.Preferences.getString
import gm.tieba.tabswitch.dao.Preferences.remove
import gm.tieba.tabswitch.dao.Preferences.putString
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper
import java.text.SimpleDateFormat
import java.io.IOException
import java.util.Date
import java.util.Locale

/**
 * 模块日志: 开启"运行日志"开关后写入用户通过系统文件管理器选定的文件夹,
 * 方便用户反馈问题时提供日志。所有写入操作静默失败, 不影响宿主运行。
 * 通过 SAF(DocumentsContract) 写入, 无需存储权限, 任意可选目录均可写。
 */
object Logger {
    private const val TAG = "TiebaTS"
    private const val LOG_FILE_NAME = "TiebaTS.txt"
    private const val MAX_LOG_SIZE = 1024L * 1024
    // 会话边界: 轮转以会话为单位删除最旧数据
    private const val SESSION_HEADER = "===== 会话开始 ====="
    private val sTimeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)

    // 日志开关缓存: isEnabled 在 log()/每页 feed 诊断/JankMonitor 等热路径高频调用,
    // 直接读 SP 带 synchronized 锁开销; 缓存为 volatile 布尔, 热路径只剩一次读。
    // 监听器失效: 用户在设置页切换开关后即时生效。Preferences.init 之前查询
    // (极早期日志)按关闭处理, 与原实现读未初始化 SP 返回 false 的行为一致
    @Volatile
    private var sEnabledCache: Boolean? = null

    fun isEnabled(): Boolean {
        // 双重判定: 缓存为 null 且未初始化 SP(Preferences.init 之前的极早期日志)时
        // 直接按关闭处理且不写缓存——异常路径写缓存会把 false 永久钉死,
        // 之后 Preferences.init 完成也不会恢复
        sEnabledCache?.let { return it }
        return try {
            val enabled = getBoolean("log")
            sEnabledCache = enabled
            enabled
        } catch (ignored: Throwable) {
            false
        }
    }

    // Preferences.init 后调用: 登记开关变化监听, 保持缓存与 SP 一致。
    // SharedPreferences 内部弱引用持有监听器, 必须保存强引用防 GC 后缓存永不失效
    private val sPrefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "log") {
                sEnabledCache = try {
                    gm.tieba.tabswitch.dao.Preferences.getBoolean("log")
                } catch (ignored: Throwable) {
                    // SP 键被以非 Boolean 类型写入: 按关闭处理
                    false
                }
            }
        }

    fun registerPrefsListener() {
        try {
            gm.tieba.tabswitch.dao.Preferences.registerOnSharedPreferenceChangeListener(sPrefsListener)
        } catch (ignored: Throwable) {
        }
    }

    // 反馈导出用: 读取日志尾部(文件最大 1MB, 全量读取既慢又超出 Intent 传输上限)。
    // 尾部截断按整行对齐, 不吐半行。未开启日志/文件不存在/读取失败返回 null
    fun readLogTail(maxBytes: Long = 256L * 1024): String? {
        return try {
            val context = XposedContext.getContext()
            val fileUri = getLogFileUri() ?: return null
            context.contentResolver.openFileDescriptor(fileUri, "r")?.use { pfd ->
                val size = pfd.statSize
                if (size <= 0L) return@use ""
                val skip = if (size > maxBytes) size - maxBytes else 0L
                java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                    // skip 契约允许少跳, 循环补足到目标偏移
                    var toSkip = skip
                    while (toSkip > 0) {
                        val skipped = fis.skip(toSkip)
                        if (skipped <= 0L) break
                        toSkip -= skipped
                    }
                    val reader = fis.bufferedReader()
                    if (skip > 0L) reader.readLine() // 丢弃被截断的半行, 对齐到完整行
                    reader.readText()
                }
            }
        } catch (ignored: Throwable) {
            null
        }
    }

    fun getLogTreeUri(): Uri? = try {
        getString("log_dir")?.takeIf { it.startsWith("content:") }?.let { Uri.parse(it) }
    } catch (ignored: Throwable) {
        null
    }

    private fun getLogFileUri(): Uri? = try {
        getString("log_file")?.takeIf { it.startsWith("content:") }?.let { Uri.parse(it) }
    } catch (ignored: Throwable) {
        null
    }

    // 原生崩溃日志的导出文件: 与 TiebaTS.txt 同目录同授权(log_dir 的持久化
    // SAF 写权限对整棵树生效, 原生日志无需单独选目录)。
    // 不复用"运行日志"的日志文档: 原生日志是独立证据源, 混写会互相污染轮转
    private const val NATIVE_EXPORT_NAME = "TiebaTS-native.txt"

    /**
     * 把原生日志尾部导出为"运行日志"目录下的 TiebaTS-native.txt(整文档覆写)。
     *
     * 返回值: 成功返回展示用目录路径; 未配置日志目录返回 null 由调用方提示;
     * 写失败抛异常由调用方留痕。
     *
     * 覆写而非追加: 该文档是"导出时刻的原生日志快照", 每次导出取最新尾部,
     * 用户把它发回来即可, 不承担累积职责。
     */
    fun exportNativeLogToLogDir(context: Context): String? {
        val treeUri = getLogTreeUri()
            ?: throw IllegalStateException("未配置日志目录, 请先开启运行日志")
        val tail = NativeCrashGuard.readTail()
            ?: throw IllegalStateException(
                // 区分提示: "捕获未安装"给出可行动指引(LSPatch 下 hide_native 关闭时
                // 文件永远不存在, 实机排查结论 2026-11-13); 已安装则文件必然非空
                // (install 成功即落盘 mark), 读不到只剩"记录被清除数据删除"一种解释
                NativeCrashGuard.unavailableReason()
                    ?: "暂无原生日志(未发生原生崩溃, 或记录已被宿主清除数据删除)"
            )
        val resolver = context.contentResolver
        // 沿用已有导出文档; 缺失时在日志目录下新建(与日志文件同一棵树)
        val existing = findDirDocument(resolver, treeUri, NATIVE_EXPORT_NAME)
        val target = existing ?: run {
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            DocumentsContract.createDocument(
                resolver, rootDocUri, "text/plain", NATIVE_EXPORT_NAME
            ) ?: throw IOException("createDocument failed")
        }
        rewriteDocument(resolver, target, tail)
        return displayPath(treeUri)
    }

    // 在目录树中按显示名查找一级子文档(供同名导出文档复用, 避免每次导出
    // 都新建出 TiebaTS (1).txt)。按 documentId 构建子文档 uri——COLUMN_URI
    // 列在部分提供器上不返回, documentId 是 SAF 的稳定契约
    private fun findDirDocument(resolver: ContentResolver, treeUri: Uri, name: String): Uri? = try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    val docId = cursor.getString(0) ?: continue
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                }
            }
            null
        }
    } catch (ignored: Throwable) {
        null
    }

    // 用户在文件管理器选定文件夹后调用: 持久化授权并创建日志文件,
    // 成功返回用于展示的文件夹路径, 失败返回 null
    fun setLogDir(context: Context, treeUri: Uri): String? {
        return try {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (ignored: Throwable) {
            }
            // 与既有目录相同且日志文件仍登记: 沿用现有文件, 不重建——
            // 否则同文件夹重复开启会丢失历史日志或产生 "TiebaTS (1).txt" 重复文档
            if (getString("log_dir") == treeUri.toString() && getLogFileUri() != null) {
                return displayPath(treeUri)
            }
            putString("log_dir", treeUri.toString())
            if (recreateLogFile(context) == null) {
                remove("log_dir")
                null
            } else {
                displayPath(treeUri)
            }
        } catch (tr: Throwable) {
            Log.e(TAG, "setLogDir failed", tr)
            null
        }
    }

    fun log(message: String) {
        // isEnabled 前置: 关闭日志时不再构造 logcat 写入与时间戳/格式化等
        // 必要开销; logcat ERROR 常显改由 logSync/崩溃路径保留
        if (!isEnabled()) return
        Log.e(TAG, message)
        try {
            val time = synchronized(sTimeFormat) { sTimeFormat.format(Date()) }
            enqueue("[${time}] ${sContextPrefix()}${message.trimEnd()}\n")
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 每条日志的上下文前缀。
     *
     * <p>贴吧是多进程应用，模块会同时装进主进程与各子进程（日志里出现过 10 个
     * 不同 pid），而所有进程写同一个 SAF 文档。没有 pid/进程名/线程名时，
     * 日志会交错成一团，无法判断"哪条属于哪个进程、哪个线程"——这正是排查
     * 随机闪退时最缺的信息。
     *
     * <p>前缀开销必须极低（每条日志都会调用）：pid 与进程名各缓存一次，
     * 线程名只在非主线程时取（主线程最常见，省字节）。
     */
    private fun sContextPrefix(): String {
        val sb = StringBuilder(48)
        sb.append("pid=").append(sPid)
        val process = sProcessName
        if (process != null) sb.append(' ').append(process)
        sb.append(" tid=").append(android.os.Process.myTid())
        val thread = Thread.currentThread()
        // 主线程不标名（最常见，省字节）；其余线程标名字便于区分
        if (thread !== android.os.Looper.getMainLooper().thread) {
            sb.append(' ').append(thread.name)
        }
        sb.append(" | ")
        return sb.toString()
    }

    /** 当前进程 pid（常量，缓存避免每条日志一次 syscall）。 */
    private val sPid: Int by lazy { android.os.Process.myPid() }

    /** 当前进程名；取不到时为 null（不阻断日志）。实现见 ProcessUtils（三处共用）。 */
    private val sProcessName: String? by lazy { currentProcessName() }

    // 同步落盘: 崩溃日志等进程即将死亡的路径使用, 异步队列来不及写会丢。
    // 崩溃路径豁免"运行日志"开关: 用户最需要崩溃现场的时刻往往恰是没开日志时,
    // 开关关闭也应落盘(与 native 侧 NativeCrashGuard 不受开关控制的行为对齐);
    // 未配置日志目录时 appendLine 自然无处可写, 不产生额外副作用
    fun logSync(message: String) {
        Log.e(TAG, message)
        try {
            val time = synchronized(sTimeFormat) { sTimeFormat.format(Date()) }
            appendLine(XposedContext.getContext(), "[${time}] ${sContextPrefix()}${message.trimEnd()}\n")
        } catch (ignored: Throwable) {
        }
    }

    fun logSync(tr: Throwable) {
        logSync(Log.getStackTraceString(tr))
    }

    // 异步写入: log() 可能来自主线程 hook 回调, SAF 打开/写入有毫秒级~几十毫秒
    // 延迟, 同步写会把 I/O 延迟直接计入主线程。单工作线程串行落盘(保序)并按批
    // 合并写(一次 openOutputStream 写多条), 洪峰时超限丢弃计数, 不阻塞调用线程
    private val sLogQueue = java.util.concurrent.LinkedBlockingQueue<String>(2000)
    private val sDroppedLogLines = java.util.concurrent.atomic.AtomicLong()

    private fun enqueue(line: String) {
        if (!sLogQueue.offer(line)) {
            sDroppedLogLines.incrementAndGet()
        }
    }

    init {
        Thread({
            while (true) {
                try {
                    // 必须先阻塞取队列再取 context: context 在 Application attach 前
                    // 不可用, 若先取 context 会抛异常被吞后立即重试, 从 handleLoadPackage
                    // 到 attach 的窗口内纯忙转(单核满载); 而日志只有在 context 可用后
                    // 才可能被 enqueue, take() 必然阻塞到 attach 之后
                    val first = sLogQueue.take()
                    val context = XposedContext.getContext()
                    val batch = StringBuilder(first)
                    val rest = ArrayList<String>()
                    sLogQueue.drainTo(rest)
                    for (extra in rest) batch.append(extra)
                    val dropped = sDroppedLogLines.getAndSet(0)
                    if (dropped > 0) {
                        batch.insert(0, "[洪峰丢弃${dropped}条日志]\n")
                    }
                    try {
                        appendLine(context, batch.toString())
                    } catch (ignored: Throwable) {
                    }
                } catch (ignored: InterruptedException) {
                } catch (ignored: Throwable) {
                }
            }
        }, "TiebaTS-Log").apply {
            isDaemon = true
            start()
        }
    }

    fun log(tr: Throwable) {
        // 开关前置: 关闭时不再付完整堆栈字符串构造(热路径异常日志的主要开销)
        if (!isEnabled()) return
        log(Log.getStackTraceString(tr))
    }

    // 开启日志后每次启动写一条环境信息, 方便反馈定位问题
    fun logSessionStart() {
        log("===== 会话开始 =====")
        log(
            "模块: ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}), " +
                "贴吧: ${DeobfuscationHelper.sCurrentTbVersion}"
        )
        log(
            "设备: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})"
        )
        logEnvironment()
        try {
            // 只列功能开关, 剔除配置类 SP 键(日志目录/文件 uri、顶部Tab隐藏名单等
            // String/StringSet 直写键): 此前整表过滤后混入配置键, 读日志时无法
            // 与真实功能开关区分。黑名单与 hooker 激活集合保持弱同步——新增
            // 非 boolean 配置键时应在此登记。
            // auto_deobf_<versionCode> 是历史版本遗留的自愈记录键(现行代码不读
            // 不写, XposedInit 的迁移会一次性清空), 按前缀剔除防同类键再混入
            val configKeys = setOf("log_dir", "log_file", "home_top_tab_hide_names")
            val enabled = gm.tieba.tabswitch.dao.Preferences.getAll()
                .filterValues { it != false }
                .keys
                .filter { it !in configKeys && !it.startsWith("auto_deobf_") }
                .joinToString(",")
            if (enabled.isNotEmpty()) log("已开启功能: $enabled")
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 记录排查 native 随机闪退所必需的环境信息。
     *
     * <p>这些字段在"崩溃点位于 libart.so、与模块代码毫无关联"的场景里是判断
     * 依据：ABI/位数决定 ART 内部布局，页面大小决定 so 能否被加载，
     * 进程名决定模块是否装进了不该装的进程。
     */
    private fun logEnvironment() {
        try {
            log(
                "进程: ${sProcessName ?: "unknown"} pid=$sPid " +
                    "64bit=${android.os.Process.is64Bit()}"
            )
        } catch (ignored: Throwable) {
        }
        try {
            log("ABI: 支持=${Build.SUPPORTED_ABIS.joinToString(",")} " +
                "64位集合=${Build.SUPPORTED_64_BIT_ABIS.joinToString(",")}")
        } catch (ignored: Throwable) {
        }
        try {
            // 16KB 页设备上仅 4KB 对齐的 so 会被链接器拒载, 是"功能静默失效"的常见原因
            val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            log("页大小: $pageSize")
        } catch (ignored: Throwable) {
        }
        try {
            log("指纹: ${Build.FINGERPRINT}")
        } catch (ignored: Throwable) {
        }
        try {
            val runtime = Runtime.getRuntime()
            log(
                "内存: max=${runtime.maxMemory() / 1024 / 1024}MB " +
                    "total=${runtime.totalMemory() / 1024 / 1024}MB " +
                    "free=${runtime.freeMemory() / 1024 / 1024}MB"
            )
        } catch (ignored: Throwable) {
        }
        try {
            log("原生日志: ${NativeCrashGuard.nativeLogPath() ?: "unavailable"}")
        } catch (ignored: Throwable) {
        }
    }

    /**
     * 记录一条 hook 注册审计。
     *
     * <p>本模块有 50+ 个 hooker、数百个 hook 点。出现"某个功能突然不生效"时，
     * 唯一能快速定位的是"哪些 hook 注册成功、哪些失败、失败原因是什么"。
     * 原实现只在成功时打一行 `hooker X hooked`，失败原因被各自的 catch 吞掉。
     */
    fun logHookAudit(hooker: String, ok: Boolean, detail: String? = null) {
        if (ok) {
            log("hook 注册成功: $hooker")
        } else {
            log("hook 注册失败: $hooker${if (detail != null) " -- $detail" else ""}")
        }
    }

    // 重建日志文件: 用于初始化与裁剪/写入失败兜底。旧文档尽力删除,
    // 避免同名文档累计成 "TiebaTS (1).txt"
    private fun recreateLogFile(context: Context): Uri? {
        getLogFileUri()?.let { old ->
            try {
                DocumentsContract.deleteDocument(context.contentResolver, old)
            } catch (ignored: Throwable) {
            }
        }
        remove("log_file")
        val treeUri = getLogTreeUri() ?: return null
        return try {
            // createDocument 需要文档 uri, 树 uri 必须先按其 docId 转换
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val fileUri = DocumentsContract.createDocument(
                context.contentResolver, rootDocUri, "text/plain", LOG_FILE_NAME
            ) ?: return null
            putString("log_file", fileUri.toString())
            fileUri
        } catch (tr: Throwable) {
            Log.e(TAG, "create log document failed", tr)
            null
        }
    }

    // 超过大小上限的就地轮转: 从最旧的会话开始逐个删除, 直到文件回到上限以内,
    // 不再生成归档文件。最近一个会话自己就超限(裁剪无法收敛)或读写异常时退回重建文件
    private fun rotateLogFile(context: Context, old: Uri) {
        try {
            val resolver = context.contentResolver
            val existing = resolver.openInputStream(old)?.use {
                it.bufferedReader().use { r -> r.readText() }
            } ?: throw IOException("Failed to read log document")
            val trimmed = dropOldestSessions(existing)
            if (trimmed == null) {
                recreateLogFile(context)
                return
            }
            rewriteDocument(resolver, old, trimmed)
            if (isOversize(resolver, old)) recreateLogFile(context)
        } catch (ignored: Throwable) {
            recreateLogFile(context)
        }
    }

    // 会话以 "===== 会话开始 =====" 为界: 从最旧的会话开始删除, 直到剩余内容的
    // UTF-8 编码不超过 MAX_LOG_SIZE(至少保留最近一个会话)。
    // 首个标记之前的内容(无会话头的残留)视为最旧数据一并丢弃。
    // 返回 null 表示最近一个会话自己就超过上限, 由调用方重建文件
    private fun dropOldestSessions(content: String): String? {
        val starts = ArrayList<Int>()
        var idx = content.indexOf(SESSION_HEADER)
        while (idx >= 0) {
            // 回退到行首, 保留 "[time] " 前缀
            starts.add(content.lastIndexOf('\n', idx) + 1)
            idx = content.indexOf(SESSION_HEADER, idx + SESSION_HEADER.length)
        }
        if (starts.isEmpty()) return content
        // 每个会话的字节长度一次算好, 从最新往回累加, 求上限内能保留的最多会话数
        val sizes = IntArray(starts.size)
        for (i in starts.indices) {
            val end = if (i + 1 < starts.size) starts[i + 1] else content.length
            sizes[i] = content.substring(starts[i], end).toByteArray(Charsets.UTF_8).size
        }
        var kept = 0
        var total = 0
        for (i in starts.indices.reversed()) {
            total += sizes[i]
            kept++
            if (total > MAX_LOG_SIZE) {
                total -= sizes[i]
                kept--
                break
            }
        }
        if (kept < 1) return null
        if (kept >= starts.size) return content
        return content.substring(starts[starts.size - kept])
    }

    // 串行互斥改为限时获取: logSync 运行在崩溃线程上, 若异步线程持锁阻塞在慢的
    // SAF binder 调用上, 无限等待会把"闪退"拖成"卡死"(原崩溃处理器迟迟不执行);
    // 争锁超时后放弃写入(丢该批日志), 保证进程按原崩溃流程退出
    private val sAppendLock = java.util.concurrent.locks.ReentrantLock()

    // 主进程判定(结果缓存): 日志文件是单 SAF 文档, 只允许主进程写。贴吧多进程
    // 并发追加本就会交叉损坏; 更糟的是轮转/重建把新 URI 写进本进程缓存的 SP,
    // 其它进程既感知不到也不失效旧 URI, 会在已删除的文档上反复写失败并各自
    // 重建出 "TiebaTS (1).txt" 重复文档。检测结果无法取得时按允许处理,
    // 保留旧行为(误判损失远小于主进程日志整体丢失)
    @Volatile
    private var sIsMainProcess: Boolean? = null

    private fun isMainProcess(context: Context): Boolean {
        sIsMainProcess?.let { return it }
        val processName = currentProcessName()
        val main = processName == null || processName == context.packageName
        sIsMainProcess = main
        return main
    }

    private fun appendLine(context: Context, line: String) {
        if (!isMainProcess(context)) return
        // log() 可能来自任意线程(hook 回调/后台线程): append 与轮转判断必须串行,
        // 否则并发 openOutputStream("wa") 会交叉写入损坏日志
        val acquired = try {
            sAppendLock.tryLock(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (ignored: InterruptedException) {
            false
        }
        if (!acquired) {
            sDroppedLogLines.addAndGet(line.count { it == '\n' }.coerceAtLeast(1).toLong())
            return
        }
        try {
            val resolver = context.contentResolver
            var fileUri = getLogFileUri()
            if (fileUri != null && isOversize(resolver, fileUri)) {
                rotateLogFile(context, fileUri)
                fileUri = getLogFileUri()
            }
            val target = fileUri ?: recreateLogFile(context) ?: return
            if (sNoAppendUris.contains(target.toString())) {
                // 该提供器已确认不支持追加模式: 整体重写, 避免每行重建文件
                rewriteWithLine(resolver, target, line)
            } else {
                writeUriLine(resolver, target, line)
            }
        } catch (tr: Throwable) {
            try {
                val resolver = context.contentResolver
                val target = getLogFileUri()
                // 仅"提供器不支持 wa 追加"时改走整体重写并记忆;
                // 其余异常(瞬时写失败/授权失效等)仍走重建文件重试
                if (tr is AppendUnsupportedException && target != null &&
                    sNoAppendUris.add(target.toString())
                ) {
                    rewriteWithLine(resolver, target, line)
                    return
                }
                val fresh = recreateLogFile(context)
                if (fresh == null) {
                    // recreateLogFile 内部吞掉所有异常返回 null: 授权失效/目录被删时
                    // 必须在此主动自报告, 否则用户以为在记录实际一行没写进去
                    reportWriteFailureOnce()
                    return
                }
                writeUriLine(context.contentResolver, fresh, line)
            } catch (ignored: Throwable) {
                reportWriteFailureOnce()
            }
        } finally {
            sAppendLock.unlock()
        }
    }

    // 自报告一次: 用户以为在记录实际一行没写进去是最浪费时间的排查场景
    private fun reportWriteFailureOnce() {
        if (sWriteFailedReported.compareAndSet(false, true)) {
            Log.w(TAG, "模块日志写入失败(SAF 授权可能已失效)，请到设置中重新选择日志文件夹；本次进程内不再重复提示")
        }
    }

    private val sWriteFailedReported = java.util.concurrent.atomic.AtomicBoolean(false)

    // 已确认不支持 "wa" 追加模式的文档 uri: 后续写入改走整体重写, 防止每行
    // 触发一次重建/归档造成文件爆炸
    private val sNoAppendUris = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // "提供器不支持追加模式"专用异常: 与瞬时写失败区分, 只有它才触发永久降级
    private class AppendUnsupportedException(message: String) : IOException(message)

    // 读出已有内容后整体重写(在 appendLine 持有 sAppendLock 期间调用, 无并发写风险)
    private fun rewriteWithLine(resolver: ContentResolver, uri: Uri, line: String) {
        val existing = try {
            resolver.openInputStream(uri)?.use { it.bufferedReader().use { r -> r.readText() } } ?: ""
        } catch (ignored: Throwable) {
            ""
        }
        rewriteDocument(resolver, uri, existing + line)
    }

    private fun rewriteDocument(resolver: ContentResolver, uri: Uri, content: String) {
        val stream = resolver.openOutputStream(uri, "w")
        if (stream == null) throw IOException("Failed to open log document")
        stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    // 部分文档提供器不支持 "wa" 追加模式: 文件非空时抛专用异常(由调用方记忆并
    // 降级为整体重写), 空文件时回退到 "w" 覆盖写(丢弃内容无损失)
    private fun writeUriLine(resolver: ContentResolver, uri: Uri, line: String) {
        val stream = try {
            resolver.openOutputStream(uri, "wa")
        } catch (ignored: Throwable) {
            val size = runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            }.getOrDefault(-1L)
            if (size > 0L) throw AppendUnsupportedException("Document provider does not support append mode")
            resolver.openOutputStream(uri)
        }
        if (stream == null) throw IOException("Failed to open log document")
        // write 失败不抛 AppendUnsupportedException: 瞬时 IO 失败不应把提供器
        // 永久标记为"不支持追加", 由调用方走重建重试
        stream.use { it.write(line.toByteArray(Charsets.UTF_8)) }
    }

    private fun isOversize(resolver: ContentResolver, uri: Uri): Boolean = try {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize > MAX_LOG_SIZE } ?: false
    } catch (ignored: Throwable) {
        false
    }

    // tree uri 转用户可读路径, 如 primary:Download/logs -> /storage/emulated/0/Download/logs
    private fun displayPath(treeUri: Uri): String = try {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val volume = docId.substringBefore(":")
        val sub = docId.substringAfter(":", "")
        if (volume == "primary") {
            "/storage/emulated/0" + if (sub.isEmpty()) "" else "/$sub"
        } else {
            docId
        }
    } catch (ignored: Throwable) {
        treeUri.toString()
    }
}
