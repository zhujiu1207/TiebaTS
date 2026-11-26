package gm.tieba.tabswitch.hooker.deobfuscation

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.BuildConfig
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.dao.AcRules
import gm.tieba.tabswitch.dao.Preferences.getSignature
import gm.tieba.tabswitch.util.restart
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.max

object DeobfuscationHelper {

    private const val SIGNATURE_DATA_START_OFFSET = 32
    private const val SIGNATURE_SIZE = 20
    private val DEX_ENTRY = Regex("""classes(\d*)?\.dex""")

    // 规则内容抽样验证的样本量: 全表约 50 条, 抽 8 条的漏检概率对"个别脏规则"
    // 是可接受的(脏规则由 hooker 消费侧逐条隔离兜底), 验证本身只在"跳过重建"
    // 捷径前跑一次, 8 次 ClassLoader 查找约毫秒级
    private const val SAMPLE_SIZE = 8

    lateinit var sCurrentTbVersion: String

    fun calcSignature(dataStoreInput: InputStream): ByteArray {
        val md: MessageDigest = try {
            MessageDigest.getInstance("SHA-1")
        } catch (ex: NoSuchAlgorithmException) {
            throw RuntimeException(ex)
        }

        // skip 契约允许少跳, 循环补足到目标偏移
        var toSkip = SIGNATURE_DATA_START_OFFSET.toLong()
        while (toSkip > 0) {
            val skipped = dataStoreInput.skip(toSkip)
            if (skipped <= 0) break
            toSkip -= skipped
        }
        val buffer = ByteArray(4 * 1024)

        dataStoreInput.use { input ->
            generateSequence { input.read(buffer).takeIf { it >= 0 } }
                .forEach { bytesRead -> md.update(buffer, 0, bytesRead) }
        }

        return md.digest().also { signature ->
            check(signature.size == SIGNATURE_SIZE) { "unexpected digest write: ${signature.size} bytes" }
        }
    }

    fun isVersionChanged(context: Context): Boolean {
        val tsConfig = context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
        return tsConfig.getString("deobfs_version", "unknown") != getTbVersion(context)
    }

    // 反混淆连续失败计数(熔断用): DexKit 对某版本宿主彻底失效时避免每次启动重试。
    // 计数绑定失败时的贴吧版本: 版本变化(升级贴吧/换回受支持版本)即自动清零重试;
    // 失败+1, 成功或 dex 未变更时清零
    fun getDeobfsFailCount(context: Context): Int {
        val config = context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
        if (config.getString("deobfs_fail_tb", null) != getTbVersion(context)) {
            return 0
        }
        return config.getInt("deobfs_fail_count", 0)
    }

    fun incDeobfsFailCount(context: Context): Int {
        val count = getDeobfsFailCount(context) + 1
        context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
            .edit()
            .putInt("deobfs_fail_count", count)
            .putString("deobfs_fail_tb", getTbVersion(context))
            .commit()
        return count
    }

    fun resetDeobfsFailCount(context: Context) {
        context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
            .edit()
            .putInt("deobfs_fail_count", 0)
            .putString("deobfs_fail_tb", getTbVersion(context))
            .commit()
    }

    fun isDexChanged(context: Context): Boolean {
        return try {
            ZipFile(File(context.packageResourcePath)).use { zipFile ->
                dexSignatureHash(zipFile) != getSignature()
            }
        } catch (e: IOException) {
            // 读不了 APK 时按"已变更"处理(走完整反混淆并在失败时计入熔断),
            // 与调用方的异常口径一致; 返回 false 会沿"未变更"捷径跳过自愈
            logBoth(e)
            true
        }
    }

    // 规则库完整性防护: "dex 未变更→跳过重建"捷径存在结构性漏洞——库被清空/删除而
    // 签名偏好仍在时, 捷径永远跳过重建, 规则永久缺失, 每次启动弹"规则异常"死循环。
    // 反混淆成功时记录当时写入的规则条数, 走捷径前核对库中实际条数即可堵住该状态。
    // 条数不匹配(库被清空/上次写入中断)→ 回落完整反混淆自愈;
    // 条数一致→ 维持捷径, 部分特征漂移仍按既有"规则异常"弹窗路径提示。
    private const val KEY_RULE_COUNT = "deobfs_rule_count"

    fun saveRuleCount(context: Context, count: Int) {
        context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RULE_COUNT, count)
            .commit()
    }

    fun getRuleCount(context: Context): Int {
        return context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
            .getInt(KEY_RULE_COUNT, -1)
    }

    // 反混淆时(或本版本模块完成完整 hooker 加载时)写入的模块身份。
    // 模块更新引入新 matcher 后, 启动时据此判定"需要自动补扫": 与当前身份
    // 不一致即允许补扫; 反混淆完成或本版本完成完整加载后落盘, 天然防循环
    // (补扫后规则仍缺失则身份已一致, 回落对话框)。
    // 用 versionName(versionCode) 复合串而非裸 versionCode: 发版环境无 git,
    // versionCode 恒定, 裸 versionCode 无法感知版本更新
    private const val KEY_MODULE_ID = "deobfs_module_id"

    fun currentModuleId(): String {
        return "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
    }

    fun getDeobfsModuleId(context: Context): String? {
        return try {
            context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
                .getString(KEY_MODULE_ID, null)
        } catch (ignored: Throwable) {
            null
        }
    }

    fun saveDeobfsModuleId(context: Context, moduleId: String) {
        try {
            context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODULE_ID, moduleId)
                .commit()
        } catch (ignored: Throwable) {
        }
    }

    fun isRuleStoreIntact(context: Context): Boolean {
        val expected = getRuleCount(context)
        val actual = AcRules.countAll()
        // 旧版本未记录预期条数: 降级为"非空即通过", 只拦截库被清空的场景
        if (if (expected < 0) actual > 0 else actual == expected) {
            // 条数完整再验内容: 抽样规则的 class 必须能在宿主 ClassLoader 里加载、
            // method 必须在该类(含父类)上真实存在——拦"条数对但内容错"(特征歧义
            // 写入错误目标/历史脏规则)。类加载失败不判无效: 宿主 dex 校验已保证
            // 签名一致, 类缺失只可能是抽样抽到了版本门槛外的旧规则, 属正常降级
            // (findMissing 会在主流程逐条隔离), 不作为重建触发条件
            val broken = runCatching { sampleBrokenRules() }.getOrNull()
            if (broken != null && broken > 0) {
                logBoth("规则库内容抽样异常: $broken 条规则的定位目标不存在")
                return false
            }
            return true
        }
        return false
    }

    // 抽样验证规则内容: 类可加载时核对其方法存在性(按名不按签名——规则的 method
    // 就是裸方法名, hooker 消费侧按名+参数形状自行过滤, 这里同口径)。验证失败
    // (sClassLoader 未初始化等)抛给调用方 runCatching, 按"无法验证"处理放行
    private fun sampleBrokenRules(): Int {
        val sampled = AcRules.sampleRules(SAMPLE_SIZE)
        var broken = 0
        for (rule in sampled) {
            val valid = runCatching {
                val clazz = XposedHelpers.findClassIfExists(rule.clazz, XposedContext.sClassLoader)
                    ?: return@runCatching null // 类不存在: 版本门槛外的旧规则, 不算内容错
                clazz.declaredMethods.any { it.name == rule.method } ||
                    clazz.methods.any { it.name == rule.method }
            }.getOrNull()
            if (valid == false) broken++
        }
        return broken
    }

    // 反混淆关键事件记录(与日志开关无关, 与失败计数同类): 开始/跳过/自愈/成败
    // 覆盖式写入 TS_config, 导出日志时随诊断摘要输出, 补上"日志开关未开导致
    // 反混淆过程完全不可见"的排查盲区。只记事件不记日志, 不违反开关约定
    fun recordDeobfsRun(context: Context, event: String) {
        try {
            val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date())
            context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
                .edit()
                .putString("deobfs_last_run", "$time $event")
                .commit()
        } catch (ignored: Throwable) {
        }
    }

    fun getDeobfsRun(context: Context): String {
        return try {
            context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
                .getString("deobfs_last_run", "none") ?: "none"
        } catch (ignored: Throwable) {
            "none"
        }
    }

    fun getDeobfsVersion(context: Context): String {
        return try {
            context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
                .getString("deobfs_version", "none") ?: "none"
        } catch (ignored: Throwable) {
            "none"
        }
    }

    /** Produces one stable signature for every classes*.dex entry in the APK. */
    fun dexSignatureHash(zipFile: ZipFile): Int {
        val entries = zipFile.entries().asSequence()
            .filter { it.name.matches(DEX_ENTRY) }
            .sortedBy { it.name }
            .toList()
        if (entries.isEmpty()) return 0
        return entries.fold(1) { acc, entry ->
            zipFile.getInputStream(entry).use { input ->
                31 * acc + calcSignature(input).contentHashCode()
            }
        }
    }

    @Suppress("DEPRECATION")
    fun getTbVersion(context: Context): String {
        // 结果按包名缓存: 启动/日志/失败计数等多处反复调用, 每次 PackageManager
        // binder 查询不值得; 版本在进程内不变
        sTbVersionCache[context.packageName]?.let { return it }
        val pm = context.packageManager
        val version: String = try {
            val applicationInfo = pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            // compileSdk 37 起 versionName 标注为 @Nullable（Android 15 起
            // PackageInfo.versionName 允许为 null），这里显式收敛为非空：
            // 取不到版本号时降级为 "unknown"，避免把 null/"null" 写进规则版本门槛比较
            val resolved: String? = when (applicationInfo.metaData["versionType"] as Int?) {
                3 -> pm.getPackageInfo(context.packageName, 0).versionName
                2 -> applicationInfo.metaData["grayVersion"]?.toString()
                1 -> applicationInfo.metaData["subVersion"]?.toString()
                else -> throw PackageManager.NameNotFoundException("unknown tb version")
            }
            resolved ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            logBoth(e)
            "unknown"
        } catch (e: NullPointerException) {
            // metaData 缺少 versionType/grayVersion/subVersion 键时(平台类型为 null)降级, 不阻断宿主启动
            logBoth(e)
            "unknown"
        }
        sTbVersionCache[context.packageName] = version
        return version
    }

    private val sTbVersionCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    @SuppressLint("ApplySharedPref")
    fun markDeobfsVersion(context: Context, version: String) {
        context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
            .edit()
            .putString("deobfs_version", version)
            .commit()
    }

    // "重试并导出日志"路径专用: 落盘版本标记(触发下次启动的反混淆判定)后立即重启。
    // 常规反混淆流程的版本落盘改由 markDeobfsVersion 在扫描完成时执行,
    // 重启改由用户在完成对话框确认
    fun saveAndRestart(activity: Activity, version: String) {
        markDeobfsVersion(activity, version)
        restart(activity)
    }

    // Adapted from https://stackoverflow.com/questions/198431/how-do-you-compare-two-version-strings-in-java
    fun isTbSatisfyVersionRequirement(requiredVersion: String): Boolean {
        val currParts = sCurrentTbVersion.split(".")
        val reqParts = requiredVersion.split(".")

        val length = max(currParts.size, reqParts.size)
        for (i in 0 until length) {
            try {
                val currPart = currParts.getOrNull(i)?.toInt() ?: 0
                val reqPart = reqParts.getOrNull(i)?.toInt() ?: 0
                if (currPart != reqPart) {
                    return currPart > reqPart
                }
            } catch (e: NumberFormatException) {
                return false
            }
        }
        return true
    }

    // Inclusive of both ends
    fun isTbBetweenVersionRequirement(lower: String, upper: String): Boolean {
        return (isTbSatisfyVersionRequirement(lower)
                && (!isTbSatisfyVersionRequirement(upper) || sCurrentTbVersion == upper))
    }
}
