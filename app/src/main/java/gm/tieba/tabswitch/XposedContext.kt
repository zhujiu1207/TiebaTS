package gm.tieba.tabswitch

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XC_MethodHook.XC_MethodReplacement
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.util.Logger
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

abstract class XposedContext {

    companion object {
        val isModuleBetaVersion = BuildConfig.VERSION_NAME.contains("alpha") || BuildConfig.VERSION_NAME.contains("beta")
        // 并发容器: 写入在初始化(主线程), 读取在设置页(主线程), 当前实际收敛于
        // 主线程——但该约束从未被类型/注释固化, hooker 回调线程一旦回写即裸
        // HashMap 并发。用 ConcurrentHashMap 消除对口头约定的依赖
        val exceptions: MutableMap<String, Throwable> = ConcurrentHashMap(8)

        lateinit var sClassLoader: ClassLoader
        lateinit var sPath: String
        lateinit var sAssetManager: AssetManager

        private lateinit var sHandler: Handler
        private lateinit var sContextRef: WeakReference<Context>

        @JvmStatic
        fun getContext(): Context = checkNotNull(sContextRef.get()) { "ApplicationContext is null" }

        fun attachBaseContext(context: Context) {
            sContextRef = WeakReference(context.applicationContext)
            sHandler = Handler(Looper.getMainLooper())
        }

        // 加载顺序兜底: 设备支持的 ABI 依序尝试。双 ABI 打包(32 位宿主兼容)曾于
        // 2026-11-25 恢复过一天后整体移除(v7a 的 libhide/libcheck 产物未经真机
        // 32 位进程验证, 用户真机暴露闪退), 候选遍历结构保留: 单 ABI 打包时列表
        // 退化为单条, 行为与直接单路径加载等价, suppressed 链仍完整可排查
        @JvmStatic
        fun abiCandidatesForCurrentProcess(): List<String> = Build.SUPPORTED_ABIS.toList()

        @JvmStatic
        protected fun load(filename: String) {
            // 遍历设备 ABI 候选依次尝试(当前单 arm64 打包, 首位即命中); 不匹配
            // 位宽/缺失条目的失败以 UnsatisfiedLinkError 归入 errors 继续下一候选,
            // 全败时 suppressed 链完整落盘供排查
            val soPaths = abiCandidatesForCurrentProcess().map { abi -> "$sPath!/lib/$abi/lib$filename.so" }
            val errors = mutableListOf<Throwable>()

            for (soPath in soPaths) {
                try {
                    System.load(soPath)
                    return
                } catch (e: UnsatisfiedLinkError) {
                    errors.add(e)
                }
            }

            val linkError = UnsatisfiedLinkError("Failed to load native library: $filename")
            errors.forEach { linkError.addSuppressed(it) }
            XposedBridge.log(linkError)
            throw linkError
        }

        fun runOnUiThread(r: Runnable) {
            sHandler.post(r)
        }

        @JvmStatic
        fun findClass(className: String): Class<*> = XposedHelpers.findClass(className, sClassLoader)

        // hook 回调运行时异常统一落盘模块日志:
        // before/after 异常 XposedBridge 本就会吞掉并继续原方法, 这里仅补记录, 不改变语义;
        // replace 异常被 bridge 的 before 拦截层吞掉后回退执行宿主原方法(与旧版
        // Xposed 行为一致), 并不会向宿主调用方传播——replace 钩子不要依赖异常
        // 传播实现兜底, 失败路径须在回调体内自行处理。
        // 异常限流: 按 key(阶段+异常类) 记录最近落盘时间, 60s 内同 key 只记一次。
        // key 不含 message: 动态 message(携带解析值/索引/时间戳)会让每次调用都成为
        // 新 key, 60s 限流被完全击穿成刷屏。单槽"仅比对上一条"去重会被交替异常击穿,
        // 这里用有界 map; 超限按"最旧落盘时间"逐条淘汰而不是整表 clear——
        // 整表清空会把其他异常的限流窗口连带重置, 让它们也重新放行一轮洪峰
        private val sHookErrorLogTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private const val HOOK_ERROR_LOG_INTERVAL_MS = 60_000L
        private const val HOOK_ERROR_LOG_MAX_KEYS = 64

        @JvmStatic
        @PublishedApi
        internal fun logHookError(phase: String, tr: Throwable) {
            val key = phase + ':' + tr.javaClass.name
            val now = System.currentTimeMillis()
            val last = sHookErrorLogTimes[key]
            if (last != null && now - last < HOOK_ERROR_LOG_INTERVAL_MS) return
            if (sHookErrorLogTimes.size > HOOK_ERROR_LOG_MAX_KEYS) {
                // 并发下的重复淘汰/漏淘汰均无害, 最多多放行一轮, 下一轮触发再补
                var oldestKey: String? = null
                var oldestAt = Long.MAX_VALUE
                for ((k, t) in sHookErrorLogTimes) {
                    if (t < oldestAt) {
                        oldestAt = t
                        oldestKey = k
                    }
                }
                if (oldestKey != null) sHookErrorLogTimes.remove(oldestKey)
            }
            sHookErrorLogTimes[key] = now
            Logger.log("hook 回调异常($phase): ${tr.javaClass.name}: ${tr.message}")
            Logger.log(tr)
        }

        // 按方法实际返回类型派发安全常量: hookAllMethods 按方法名匹配全部重载,
        // returnConstant 的固定装箱值在返回类型漂移(基本类型互换/新增重载)时会在
        // 宿主调用点拆箱崩溃, hook 层无法兜底。这里把期望值转换为目标类型可安全
        // 拆箱的值; void 与无法转换的引用类型返回 null(与 returnConstant(null) 同义)
        @JvmStatic
        fun safeConstantFor(type: Class<*>, number: Long, bool: Boolean): Any? = when (type) {
            Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> bool
            Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> number.toByte()
            Short::class.javaPrimitiveType, Short::class.javaObjectType -> number.toShort()
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> number.toInt()
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> number
            Char::class.javaPrimitiveType, Char::class.javaObjectType -> number.toInt().toChar()
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> number.toFloat()
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> number.toDouble()
            else -> null
        }

        // safeConstantFor 的替换器形态: 注册给 hookAllMethods 时按每个命中方法的
        // 实际返回类型返回安全常量, 替代 returnConstant(固定值)
        @JvmStatic
        fun safeConstantReplacement(number: Long, bool: Boolean): XC_MethodReplacement =
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? =
                    safeConstantFor((param.method as Method).returnType, number, bool)
            }

        // replace 屏蔽钩点的类型安全零值(void/引用 → null): safeConstantFor 的零值
        // 形态, 唯一分歧是 Char 取 ' '(0x20)而非 0x0000, 沿用 Purge/ForbidGesture 原
        // safeZeroValue 的取值(此前两文件各持一份逐字相同的私有实现)
        @JvmStatic
        fun safeZeroValueFor(type: Class<*>): Any? = when (type) {
            Char::class.javaPrimitiveType, java.lang.Character::class.java -> ' '
            else -> safeConstantFor(type, 0, false)
        }

        // ---------------------------------------------------------------- 回调工厂
        // 阶段包装(异常吞掉+限流落盘 / replace 记录后重抛回退原方法)单点维护:
        // 此前 12 份逐字复制的 inline 样板, 修正(如 replace 重抛击穿限流)只能逐份
        // 同步, 漏一处即埋雷。工厂 internal + @PublishedApi(包装去 inline 化后
        // 该注解已非必需, 保留无害)。
        // 调用方兼容性: 旧签名的 crossinline 与新普通 fun 的 lambda 参数同样禁止
        // lambda 体内非局部 return, 既有 return@标签 用法不受影响。

        @PublishedApi
        internal fun beforeCallback(
            beforeHook: (XC_MethodHook.MethodHookParam) -> Unit
        ): XC_MethodHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    beforeHook(param)
                } catch (tr: Throwable) {
                    logHookError("before", tr)
                }
            }
        }

        @PublishedApi
        internal fun afterCallback(
            priority: Int = XC_MethodHook.PRIORITY_DEFAULT,
            afterHook: (XC_MethodHook.MethodHookParam) -> Unit
        ): XC_MethodHook = object : XC_MethodHook(priority) {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    afterHook(param)
                } catch (tr: Throwable) {
                    logHookError("after", tr)
                }
            }
        }

        @PublishedApi
        internal fun replaceCallback(
            replaceHook: (XC_MethodHook.MethodHookParam) -> Any?
        ): XC_MethodReplacement = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Any? {
                try {
                    return replaceHook(param)
                } catch (tr: Throwable) {
                    logHookError("replace", tr)
                    throw tr
                }
            }
        }

        // ------------------------------------------------------------- 按类名 hook
        fun hookBeforeMethod(
            className: String,
            methodName: String,
            vararg parameterTypes: Any?,
             beforeHook: (XC_MethodHook.MethodHookParam) -> Unit
        ): XC_MethodHook.Unhook = XposedHelpers.findAndHookMethod(
            className, sClassLoader, methodName, *parameterTypes,
            beforeCallback(beforeHook)
        )

        fun hookAfterMethod(
            className: String,
            methodName: String,
            vararg parameterTypes: Any?,
             afterHook: (XC_MethodHook.MethodHookParam) -> Unit
        ): XC_MethodHook.Unhook = XposedHelpers.findAndHookMethod(
            className, sClassLoader, methodName, *parameterTypes,
            afterCallback(afterHook = afterHook)
        )

        fun hookAfterMethodPriority(
            className: String,
            methodName: String,
            vararg parameterTypes: Any?,
             afterHook: (XC_MethodHook.MethodHookParam) -> Unit
        ): XC_MethodHook.Unhook = XposedHelpers.findAndHookMethod(
            className, sClassLoader, methodName, *parameterTypes,
            afterCallback(XC_MethodHook.PRIORITY_LOWEST, afterHook)
        )

        fun hookReplaceMethod(
            className: String,
            methodName: String,
            vararg parameterTypes: Any?,
             replaceHook: (XC_MethodHook.MethodHookParam) -> Any?
        ): XC_MethodHook.Unhook = XposedHelpers.findAndHookMethod(
            className, sClassLoader, methodName, *parameterTypes,
            replaceCallback(replaceHook)
        )

        // ------------------------------------------------------------- 按 Class hook
        fun hookBeforeMethod(
            clazz: Class<*>,
            methodName: String,
            vararg parameterTypes: Any?,
             beforeHook: (XC_MethodHook.MethodHookParam) -> Unit
        ): XC_MethodHook.Unhook = XposedHelpers.findAndHookMethod(
            clazz, methodName, *parameterTypes,
            beforeCallback(beforeHook)
        )

        fun hookAfterMethod(
            clazz: Class<*>,
            methodName: String,
            vararg parameterTypes: Any?,
             afterHook: (XC_MethodHook.MethodHookParam) -> Unit
        ): XC_MethodHook.Unhook = XposedHelpers.findAndHookMethod(
            clazz, methodName, *parameterTypes,
            afterCallback(afterHook = afterHook)
        )

        fun hookReplaceMethod(
            clazz: Class<*>,
            methodName: String,
            vararg parameterTypes: Any?,
             replaceHook: (XC_MethodHook.MethodHookParam) -> Any?
        ): XC_MethodHook.Unhook = XposedHelpers.findAndHookMethod(
            clazz, methodName, *parameterTypes,
            replaceCallback(replaceHook)
        )

        // ------------------------------------------------------------- 按 Method hook
        fun hookBeforeMethod(
            method: Method,
             beforeHook: (XC_MethodHook.MethodHookParam) -> Unit
        ): XC_MethodHook.Unhook = XposedBridge.hookMethod(
            method,
            beforeCallback(beforeHook)
        )

        fun hookAfterMethod(
            method: Method,
             afterHook: (XC_MethodHook.MethodHookParam) -> Unit
        ): XC_MethodHook.Unhook = XposedBridge.hookMethod(
            method,
            afterCallback(afterHook = afterHook)
        )

        fun hookReplaceMethod(
            method: Method,
             replaceHook: (XC_MethodHook.MethodHookParam) -> Any?
        ): XC_MethodHook.Unhook = XposedBridge.hookMethod(
            method,
            replaceCallback(replaceHook)
        )

        // ------------------------------------------------------------- 构造器 hook
        fun hookAfterConstructor(
            className: String,
            vararg parameterTypes: Any?,
             afterHook: (XC_MethodHook.MethodHookParam) -> Unit
        ): XC_MethodHook.Unhook = XposedHelpers.findAndHookConstructor(
            className, sClassLoader, *parameterTypes,
            afterCallback(afterHook = afterHook)
        )

        fun hookAfterConstructor(
            clazz: Class<*>,
            vararg parameterTypes: Any?,
             afterHook: (XC_MethodHook.MethodHookParam) -> Unit
        ): XC_MethodHook.Unhook = XposedHelpers.findAndHookConstructor(
            clazz, *parameterTypes,
            afterCallback(afterHook = afterHook)
        )
    }
}

// 同步写入 LSPosed 日志与模块日志文件: 异常/降级/失败类信息两处都要可见,
// 避免用户只提供模块日志文件时缺失关键线索(正常流程性日志保持单写, 避免文件 IO 放大)
fun logBoth(message: String) {
    XposedBridge.log(message)
    Logger.log(message)
}

fun logBoth(tr: Throwable) {
    XposedBridge.log(tr)
    Logger.log(tr)
}

// hooker 内注册点隔离: 单点漂移(类缺失/签名漂移)只损失该点并双写日志,
// 不让 hook() 其后的全部注册点一并失效(Purge/UserFilter/ContentFilter 同范式)
fun hookSafely(owner: String, tag: String, register: () -> Unit) {
    runCatching(register).onFailure { logBoth("$owner: [$tag] 注册失败 -- $it") }
}
