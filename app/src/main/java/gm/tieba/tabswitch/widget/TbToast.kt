package gm.tieba.tabswitch.widget

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.AcRules
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.hooker.deobfuscation.StringMatcher
import gm.tieba.tabswitch.util.Logger
import gm.tieba.tabswitch.util.callStaticMethod
import gm.tieba.tabswitch.util.findFirstMethodByExactType
import java.lang.reflect.Method

class TbToast : XposedContext(), Obfuscated {

    override fun matchers(): List<Matcher> {
        // setToastString()
        return listOf(StringMatcher(TOAST_RULE_KEY))
    }

    companion object {
        // 反混淆规则键: matcher 注册与运行时 findRule 必须用同一字符串,
        // 单点定义防两处硬编码漂移错位
        private const val TOAST_RULE_KEY = "can not be call not thread! trace = "

        // 宿主 Toast 档位常量语义(仅只读): 值为宿主实现接受的毫秒时长
        @JvmField
        val LENGTH_SHORT = 2000
        @JvmField
        val LENGTH_LONG = 3500

        // 反射结果缓存: 避免每次弹 toast 都查一次规则库。解析失败也记负缓存并按
        // 60s 限流重试——规则库缺失/反扫描不完整期间(恰是 toast 报错最多的时段)
        // 不缓存会让主线程每次弹 toast 都同步查一次 SQLite(AcRules 允许主线程查询)
        @Volatile
        private var sToastMethod: Method? = null
        @Volatile
        private var sToastMethodResolvedAt = 0L
        private const val TOAST_RESOLVE_RETRY_INTERVAL_MS = 60_000L

        // 线程安全性: 反射调用段经 runOnUiThread 切主线程, resolveToastMethod 的
        // 规则库查询在调用线程执行(后台线程查询 SQLite 反而更安全);
        // 不标注 @MainThread——TraceChecker 从后台线程直接调用是受支持的用法
        @JvmStatic
        fun showTbToast(text: String?, duration: Int) {
            // resolveToastMethod 内部走 AcRules.findRule → ruleDao(lateinit): 进程
            // 极早期(初始化前)调用抛 UninitializedPropertyAccessException, DB 异常
            // 同理——toast 是所有错误路径的最后提示通道, 解析阶段异常不能击穿到
            // 调用方, 与下方反射调用一并就地吞掉
            val md = runCatching { resolveToastMethod() }.getOrNull() ?: return
            // toast 是所有错误路径的最后提示通道: 反射/宿主内部异常若在主线程
            // Looper 中未捕获会直接崩宿主, 必须就地吞掉并记日志
            runOnUiThread {
                try {
                    callStaticMethod(md, text, duration, true)
                } catch (tr: Throwable) {
                    Logger.log("showTbToast failed: $tr")
                }
            }
        }

        private fun resolveToastMethod(): Method? {
            sToastMethod?.let { return it }
            val now = System.currentTimeMillis()
            val last = sToastMethodResolvedAt
            sToastMethodResolvedAt = now
            if (last != 0L && now - last < TOAST_RESOLVE_RETRY_INTERVAL_MS) return null
            var found: Method? = null
            AcRules.findRule(TOAST_RULE_KEY) { _, clazz, _ ->
                try {
                    found = findFirstMethodByExactType(
                        clazz,
                        String::class.java,
                        Int::class.javaPrimitiveType!!,
                        Boolean::class.javaPrimitiveType!!
                    )
                } catch (ignored: Throwable) {
                }
            }
            sToastMethod = found
            return found
        }
    }
}
