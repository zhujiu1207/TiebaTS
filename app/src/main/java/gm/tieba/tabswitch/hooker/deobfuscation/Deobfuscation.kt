package gm.tieba.tabswitch.hooker.deobfuscation

import android.content.Context
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.dao.AcRules.countAll
import gm.tieba.tabswitch.dao.AcRule
import gm.tieba.tabswitch.dao.AcRule.Companion.create
import gm.tieba.tabswitch.dao.AcRules.putRules
import gm.tieba.tabswitch.dao.Preferences.putSignature
import gm.tieba.tabswitch.util.DexKitLoader
import gm.tieba.tabswitch.util.NativeCrashGuard
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.result.MethodDataList
import java.util.Objects
import java.util.zip.ZipFile

// 单 matcher 默认允许写入的最大规则数: 实测 22.12.1.0 上合法 matcher 的命中
// 基线最高约 6 个方法(如 key_home_insert_ad_show_time/forum_tab_current_list),
// 10 留有版本漂移余量, 同时足以拦截宿主结构变化导致的失控命中。个别特征
// 天然多发(按构造器签名找全部调用方)的 matcher 用 Matcher.maxRules 显式放宽
private const val DEFAULT_MAX_RULES_PER_MATCHER = 10

class Deobfuscation : XposedContext() {

    private val matchers: MutableList<Matcher> = ArrayList()
    private lateinit var packageResource: String

    fun setMatchers(matchers: List<Matcher>) {
        this.matchers.clear()
        this.matchers.addAll(matchers)
    }

    private fun <T> forEachProgressed(
        hooker: DeobfuscationHooker,
        collection: Collection<T>,
        action: (T) -> Unit
    ) {
        val size = collection.size
        collection.forEachIndexed { index, item ->
            hooker.progress = (index + 1).toFloat() / size
            action(item)
        }
    }

    fun dexkit(context: Context, hooker: DeobfuscationHooker) {
        // 打点: 反混淆是用户怀疑的 native 崩溃源头。DexKit 走 JNI + 独立 native
        // 内存, 一旦崩溃点落在 libdexkit.so 或 libart.so, 只有这些 mark 能证明
        // "崩溃发生在反混淆阶段"
        NativeCrashGuard.mark("deobf-start", "matchers=${matchers.size}")
        // 多级加载兜底(APK 直载/nativeLibraryDir/解包), 全链失败抛出,
        // 由 DeobfuscationHooker 既有的失败计数与熔断链路接管
        DexKitLoader.ensureLoaded(context)
        packageResource = context.packageResourcePath
        NativeCrashGuard.mark("deobf-dexkit-load", packageResource)
        val bridge = DexKitBridge.create(packageResource)
        Objects.requireNonNull(bridge)
        NativeCrashGuard.mark("deobf-bridge-created", null)
        // 成功写入至少一条规则的 matcher 数: DexKit 环境异常(so 加载失败/查询全挂)时
        // 每个 matcher 都被逐条 catch 吞掉, 若仍按成功收尾, saveDexSignatureHashCode
        // 会落盘签名+ruleCount(0), 下次启动判"规则库完整"永久跳过重建且熔断不触发,
        // 用户陷入"规则异常"弹窗死循环。全零时抛出走失败计数/熔断路径
        var writtenMatchers = 0
        // 规则统一收口到扫描结束后单次批量写入: 逐条 insertAll 是每条一个 Room
        // 事务, 约百条规则即百次事务, 重建耗时线性放大
        val rulesToWrite = ArrayList<AcRule>()
        try {
            forEachProgressed(hooker, matchers) { matcher: Matcher ->
                var wroteAny = false
                // 逐 matcher 打点: 崩溃时能定位到具体特征, 而不是"反混淆整体"
                NativeCrashGuard.mark("deobf-matcher", matcher.toString())
                try {
                    val methodDataList = MethodDataList()

                matcher.classMatcher?.let { classMatcher ->
                    val classNames = bridge.findClass(FindClass.create().matcher(classMatcher))
                        .map { it.name }
                    if (classNames.isNotEmpty()) {
                        // 单次全量查询 + 内存过滤, 替代原先"每个命中类一次按类名前缀
                        // searchPackages"的逐类查询: 同一 bridge 的每次查询都要过全量
                        // dex 索引, 命中类一多查询数线性放大, 是反混淆阶段耗时与内存
                        // 峰值的主要放大点。过滤保留原前缀匹配中的有效语义(命中类
                        // 本身 + 其内部类 Foo$Bar), 不再附带 FooBar 这类仅共享前缀的
                        // 偶然误配
                        for (methodData in findMethod(bridge, FindMethod.create(), matcher)) {
                            val cn = methodData.className
                            if (classNames.any { cn == it || cn.startsWith("$it$") }) {
                                methodDataList.add(methodData)
                            }
                        }
                    }
                } ?: methodDataList.addAll(findMethod(bridge, FindMethod.create(), matcher))

                // 去重防御: 键含参数签名, 同类同名重载是合法的不同 hook 点,
                // 不能按"类名#方法名"丢弃
                val distinct = methodDataList
                    .distinctBy { "${it.className}#${it.name}#${it.paramTypes}" }
                val maxRules = matcher.maxRules ?: DEFAULT_MAX_RULES_PER_MATCHER
                if (distinct.size > maxRules) {
                    // 歧义整条丢弃: 特征命中远超上限说明宿主结构变化或特征过宽,
                    // 命中集里必然混有错误目标; 对错误目标的 hook 会在运行期注入
                    // 错误行为, 比单功能缺失更危险也更难排查。丢弃后规则缺失由既有
                    // 的 findMissing/补扫/规则异常弹窗链路呈现, 日志保留命中数供定位
                    logBoth(
                        "Deobfuscation: matcher ${matcher} 命中 ${distinct.size} 个方法" +
                            "(上限 $maxRules), 疑似特征歧义, 已整条丢弃"
                    )
                } else {
                    distinct.forEach { methodData ->
                        rulesToWrite.add(create(matcher.toString(), methodData.className, methodData.name))
                        wroteAny = true
                    }
                }
                } catch (tr: Throwable) {
                    // 逐 matcher 隔离: 单个特征查询失败(db 写入异常/DexKit 查询非法)
                    // 只损失该条规则, 不能中止剩余 50+ 条扫描 —— 否则整轮计入熔断,
                    // 缺一条规则就退回"规则异常"全量重建
                    logBoth("Deobfuscation: matcher ${matcher} 扫描失败, 跳过 -- $tr")
                }
                if (wroteAny) {
                    writtenMatchers++
                }
            }
        } finally {
            // native 内存随 bridge 释放, 失败路径(如规则解析异常)同样要归还
            bridge.close()
            NativeCrashGuard.mark("deobf-bridge-closed", null)
        }
        putRules(rulesToWrite)
        if (matchers.isNotEmpty() && writtenMatchers == 0) {
            throw IllegalStateException(
                "全部 ${matchers.size} 个 matcher 均未产出规则, 疑似 DexKit 环境异常"
            )
        }
        if (writtenMatchers * 2 < matchers.size) {
            // 非致命但值得留痕: 大面积未命中可能是宿主大改或环境半失效,
            // 落盘的规则条数偏少, 便于用户反馈时定位
            logBoth("Deobfuscation: 仅 ${writtenMatchers}/${matchers.size} 个 matcher 命中规则")
        }
    }

    private fun findMethod(bridge: DexKitBridge, baseMethodQuery: FindMethod, matcher: Matcher): MethodDataList {
        return bridge.findMethod(baseMethodQuery.matcher(matcher.methodMatcher))
    }

    fun saveDexSignatureHashCode() {
        ZipFile(packageResource).use { apk ->
            // 对所有 classes*.dex 计算组合摘要，避免只修改 classes2.dex 时漏检。
            // durable: 写入后用户确认重启即杀进程, apply 的异步落盘可能丢失,
            // 丢失会让重启后误判 dex 变更再走一遍反混淆
            putSignature(DeobfuscationHelper.dexSignatureHash(apk), durable = true)
        }
        // 同时记录本次写入的规则条数, 供"dex 未变更"捷径做规则库完整性校验
        DeobfuscationHelper.saveRuleCount(getContext(), countAll())
    }
}
