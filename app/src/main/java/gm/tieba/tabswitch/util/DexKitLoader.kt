package gm.tieba.tabswitch.util

import android.content.Context
import android.os.Build
import gm.tieba.tabswitch.BuildConfig
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.logBoth
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * libdexkit.so 的多级加载兜底(借鉴 ForbidAd4TieBa 的 DexKitBridgeProvider, 按
 * TiebaTS 的运行现实裁剪)。
 *
 * <p>libdexkit.so 是反混淆的唯一第三方原生库, 而反混淆是全部规则依赖功能的地基;
 * 此前只有 [XposedContext] 的 load() 一条路径, 单点失败即反混淆整体失效, 且
 * 失败只能在日志的链接异常里翻找。加载链按"命中概率递减、兜底代价递增"排列:
 * <ol>
 *   <li>模块 APK 直载: 未压缩 so 条目的常规路径(LSPosed/Vector 下即此路径),
 *       复用 load() 的 ABI 遍历实现;</li>
 *   <li>模块 nativeLibraryDir 直载: extractNativeLibs=true 的安装器把 so 解包
 *       到该目录, APK 直载失败时这里是天然备份;</li>
 *   <li>从模块 APK 的 so 条目解包到 codeCache 后加载: so 条目被二次压缩等
 *       直载必败场景的最终兜底, 代价是一次文件 IO。</li>
 * </ol>
 *
 * <p>负缓存: 全链失败只尝试一次。反混淆失败走"失败计数→熔断"链路, 每个启动
 * 周期都会重试触发; 不缓存的话, 必败的三级尝试(含解包 IO)会被原样重复。
 */
object DexKitLoader : XposedContext() {

    private const val TAG = "DexKitLoader"

    // codeCache 下的解包目录: 模块解包数据自成一目录, 便于定位与清理
    private const val EXTRACT_DIR_NAME = "dexkit-lib"
    private const val LIBRARY_FILE_NAME = "libdexkit.so"
    private const val MODULE_PACKAGE_NAME = BuildConfig.APPLICATION_ID

    private const val STATE_UNTRIED = 0
    private const val STATE_LOADED = 1
    private const val STATE_FAILED = 2

    // 扫描线程与翻译运行时预载可能并发进入, 状态读写全部走同把锁串行化
    @Volatile
    private var sState = STATE_UNTRIED

    @Volatile
    private var sFailure: IllegalStateException? = null

    /**
     * 确保库已加载。全链失败时抛 [IllegalStateException](suppressed 携带各级
     * 失败原因), 由反混淆既有失败链路(失败计数/熔断/用户提示)接管。
     */
    fun ensureLoaded(context: Context) {
        if (sState == STATE_LOADED) return
        synchronized(this) {
            if (sState == STATE_LOADED) return
            if (sState == STATE_FAILED) {
                throw IllegalStateException("libdexkit.so 不可用(已缓存失败)", sFailure)
            }
            val errors = mutableListOf<Throwable>()
            if (tryLoadFromModuleApk(errors)
                || tryLoadFromNativeLibraryDir(context, errors)
                || tryLoadFromExtractedCopy(context, errors)
            ) {
                sState = STATE_LOADED
                return
            }
            val failure = IllegalStateException("libdexkit.so 全部 ${errors.size} 条加载路径均失败")
            errors.forEach(failure::addSuppressed)
            sFailure = failure
            sState = STATE_FAILED
            logBoth(failure)
            throw failure
        }
    }

    /**
     * x86_64 翻译运行时(模拟器 arm 转译)的预载: ABI 表首位为 x86_64 且含 arm64
     * 说明本进程跑在翻译层上, 此时原生库必须抢在宿主 SDK 建立自己的翻译命名空间
     * 之前加载, 否则扫描期的加载可能落入不可用命名空间(失败推迟到反混淆才暴露)。
     *
     * <p>只试直载路径: 失败不置负缓存也不打失败标记, 完整链路留给扫描期的
     * [ensureLoaded]。仅主进程调用(库只在主进程的反混淆里使用)。
     */
    fun preloadForTranslatedRuntime() {
        val abis = Build.SUPPORTED_ABIS
        if (abis.firstOrNull() != "x86_64" || "arm64-v8a" !in abis) return
        if (sState != STATE_UNTRIED) return
        synchronized(this) {
            if (sState != STATE_UNTRIED) return
            try {
                load("dexkit")
                sState = STATE_LOADED
                XposedBridge.log("$TAG: x86_64 翻译运行时预载完成")
            } catch (tr: Throwable) {
                XposedBridge.log("$TAG: 翻译运行时预载失败(扫描期将走完整链路): $tr")
            }
        }
    }

    /** 路径 1: 模块 APK 直载, 与 hide/check 共用 XposedContext.load() 的实现。 */
    private fun tryLoadFromModuleApk(errors: MutableList<Throwable>): Boolean = try {
        load("dexkit")
        true
    } catch (tr: Throwable) {
        // sPath 未初始化(模块路径解析失败)等一切失败, 全部归入下一级
        errors.add(tr)
        false
    }

    /** 路径 2: 模块 nativeLibraryDir 直载。 */
    private fun tryLoadFromNativeLibraryDir(context: Context, errors: MutableList<Throwable>): Boolean {
        val dir = try {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(MODULE_PACKAGE_NAME, 0)
                .nativeLibraryDir?.takeIf { it.isNotBlank() }
        } catch (tr: Throwable) {
            errors.add(tr)
            null
        } ?: run {
            errors.add(IllegalStateException("模块 nativeLibraryDir 不可用"))
            return false
        }
        val file = File(dir, LIBRARY_FILE_NAME)
        if (!file.isFile) {
            errors.add(IllegalStateException("nativeLibraryDir 下无 $LIBRARY_FILE_NAME: $dir"))
            return false
        }
        return try {
            System.load(file.absolutePath)
            XposedBridge.log("$TAG: 已从 nativeLibraryDir 加载: $dir")
            true
        } catch (tr: Throwable) {
            errors.add(tr)
            false
        }
    }

    /** 路径 3: 从模块 APK 解包 so 到 codeCache 后加载。 */
    private fun tryLoadFromExtractedCopy(context: Context, errors: MutableList<Throwable>): Boolean {
        var sawApk = false
        for (apk in resolveModuleApkPaths(context)) {
            sawApk = true
            try {
                ZipFile(apk).use { zip ->
                    for (abi in Build.SUPPORTED_ABIS) {
                        val entry = zip.getEntry("lib/$abi/$LIBRARY_FILE_NAME") ?: continue
                        System.load(extractEntry(context, apk, zip, entry, abi).absolutePath)
                        XposedBridge.log("$TAG: 已从模块 APK 解包加载 lib/$abi/$LIBRARY_FILE_NAME")
                        return true
                    }
                    errors.add(IllegalStateException("$apk 中无任何设备 ABI 的 $LIBRARY_FILE_NAME 条目"))
                }
            } catch (tr: Throwable) {
                errors.add(tr)
            }
        }
        if (!sawApk) errors.add(IllegalStateException("模块 APK 路径均不可用"))
        return false
    }

    /** 模块 APK 候选路径: sPath(框架提供的模块路径) + 包管理器解析的 sourceDir/split。 */
    private fun resolveModuleApkPaths(context: Context): List<File> {
        val paths = LinkedHashSet<String>()
        // sPath 即 onModuleLoaded 提供的模块主包路径; lateinit 未初始化说明模块路径
        // 解析已失败, 跳过交给包管理器兜底
        try {
            if (sPath.isNotBlank()) paths.add(sPath)
        } catch (_: Throwable) {
        }
        try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(MODULE_PACKAGE_NAME, 0)
            paths.add(info.sourceDir)
            info.splitSourceDirs?.forEach(paths::add)
        } catch (_: Throwable) {
            // Android 11+ 包可见性下宿主查询模块包可能被拒(NameNotFound), 这是
            // 预期降级而非异常: LSPosed/Vector 会把模块路径注入 sPath, sPath 分支
            // 已覆盖; 两处都拿不到才走"模块 APK 路径均不可用"的记录
        }
        return paths.map(::File).filter(File::isFile).distinct()
    }

    /**
     * 解包单个 so 条目。目标文件名带 APK 指纹(长度-修改时间): 模块更新后自然
     * 换名重解, 旧指纹文件被顺带清理, codeCache 不会无限增长。
     */
    private fun extractEntry(
        context: Context,
        apk: File,
        zip: ZipFile,
        entry: ZipEntry,
        abi: String,
    ): File {
        val dir = File(context.codeCacheDir, EXTRACT_DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) {
            error("codeCache 解包目录不可用: ${dir.absolutePath}")
        }
        val stamp = "${apk.length()}-${apk.lastModified()}"
        val target = File(dir, "$LIBRARY_FILE_NAME.$abi.$stamp.so")
        if (target.isFile && (entry.size < 0L || target.length() == entry.size)) {
            cleanupStaleExtracts(dir, stamp)
            return target
        }
        // 临时文件名带 pid: 版本变化路径的反混淆没有主进程守卫, 多进程可能并发
        // 解包同一 APK(同指纹同名目标), 确定性 .tmp 名会互相覆盖导致落盘损坏,
        // 且负缓存会把失败进程整个启动周期锁死在无库状态
        val temp = File(dir, "${target.name}.p${android.os.Process.myPid()}.tmp")
        try {
            zip.getInputStream(entry).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            // 尺寸校验: 坏块/截断的 so 交给动态链接器只会得到更难排查的链接错误
            if (entry.size >= 0L && temp.length() != entry.size) {
                error("解包尺寸不符: ${temp.length()} != ${entry.size}")
            }
            if (target.exists() && !target.delete()) error("旧解包清理失败: $target")
            if (!temp.renameTo(target)) error("解包落盘失败: $target")
        } finally {
            if (temp.exists()) temp.delete()
        }
        cleanupStaleExtracts(dir, stamp)
        return target
    }

    /**
     * 清掉非当前 APK 指纹的旧解包文件(模块更新遗留); 失败静默: 只是空间回收。
     *
     * <p>按"文件名不含当前指纹"判定而非按 keepName: 当前启动周期的一切产物
     * (目标与各进程的 .tmp)都嵌有当前指纹, 按指纹过滤天然不会碰并发进程的
     * 在写文件; 旧版本遗留必然不含新指纹, 下次加载即被回收。已知有界残留:
     * 解包中途崩溃遗留的同指纹 .tmp 永不清理, 单文件约百 KB 量级。
     */
    private fun cleanupStaleExtracts(dir: File, currentStamp: String) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isFile && !file.name.contains(currentStamp)) file.delete()
        }
    }
}
