package gm.tieba.tabswitch.bridge

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * CallbackInterceptor 拦截链翻译语义固化: 它把 API 102 的单次 intercept 回调
 * 翻译成旧版 before/origin/after 三阶段, 语义映射错误 = 全部 hooker 的 hook 行为
 * 一起变。类是 private 的, 经真实的 XposedBridge.hookMethod 安装——XposedModule
 * 的 hook() 实现全部委托给 attachFramework 注入的 XposedInterface(官方公开路径),
 * 因此注入一个假 XposedInterface(动态代理, 只需按名分发, 不必实现全部抽象方法)
 * 即可驱动全链。
 *
 * <p>钉死的旧版语义(见 XposedBridge 类注释):
 * <ul>
 *   <li>before 阶段 setResult/setThrowable 即短路, 不执行原方法;</li>
 *   <li>before/after 回调自身异常被吞掉(原方法照常执行, after 异常保留原返回值);</li>
 *   <li>after 阶段 setResult 覆盖返回值, setThrowable 替换上抛异常;</li>
 *   <li>原方法异常路径: after 可改写 throwable/result, 未改写则原样上抛;</li>
 *   <li>before 阶段改 args 元素即改写原方法实参。</li>
 * </ul>
 */
class CallbackInterceptorTest {

    companion object {
        /** 每次安装捕获的 Hooker(interceptor), 由假 XposedInterface 记录。 */
        @Volatile
        private var sLastHooker: XposedInterface.Hooker? = null

        /**
         * 假 XposedInterface 用动态代理: 接口含 Android 侧类型(SharedPreferences/
         * ApplicationInfo)的抽象方法, 匿名对象实现要在 JVM 上拉起这些类引用, 代理
         * 按名分发可完全避开。attachFramework 是官方注入路径, hook() 委托到假接口。
         */
        private fun installAndCapture(callback: XC_MethodHook, member: Method): XposedInterface.Hooker {
            sLastHooker = null
            val fakeApi = Proxy.newProxyInstance(
                XposedInterface::class.java.classLoader,
                arrayOf(XposedInterface::class.java)
            ) { _, method, args ->
                when (method.name) {
                    "hook" -> FakeHookBuilder(args!![0] as Executable)
                    "log" -> null
                    else -> when (method.returnType.name) {
                        "boolean" -> false
                        "int" -> 0
                        "long" -> 0L
                        else -> null
                    }
                }
            }
            val module = object : XposedModule() {}
            module.attachFramework(fakeApi as XposedInterface, {})
            XposedBridge.attach(module)
            XposedBridge.hookMethod(member, callback)
            return sLastHooker ?: error("hook() 未被调用, 假接口配置错误")
        }

        /** 假 HookBuilder: 捕获 interceptor 返回假 handle。 */
        private class FakeHookBuilder(private val member: Executable) : XposedInterface.HookBuilder {
            override fun setId(id: String?): XposedInterface.HookBuilder = this
            override fun setPriority(priority: Int): XposedInterface.HookBuilder = this
            override fun setExceptionMode(mode: XposedInterface.ExceptionMode): XposedInterface.HookBuilder = this
            override fun intercept(hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
                sLastHooker = hooker
                return FakeHookHandle(member)
            }
        }

        private class FakeHookHandle(private val member: Executable) : XposedInterface.HookHandle {
            override fun getExecutable(): Executable = member
            override fun unhook() {}
            override fun getId(): String? = null
            override fun replaceHook(hooker: XposedInterface.Hooker): XposedInterface.HookHandle = this
        }

        /**
         * 假 Chain: proceed 反射调用原方法; getArgs 返回可变副本。接口的
         * proceedWith 系列(框架侧按值继续执行)在本测试不涉及, 抛错即用例。
         */
        private class FakeChain(
            private val method: Method,
            private val thisObject: Any?,
            args: Array<Any?>,
        ) : XposedInterface.Chain {
            private var mArgs: Array<Any?> = args
            var proceedCount = 0
                private set

            override fun getExecutable(): Executable = method
            override fun getThisObject(): Any? = thisObject
            override fun getArgs(): MutableList<Any> = mutableListOf(*mArgs.requireNoNulls())
            override fun getArg(index: Int): Any = mArgs[index]!!

            @Suppress("UNCHECKED_CAST")
            override fun proceed(): Any? = proceed(mArgs as Array<Any>)
            @Suppress("UNCHECKED_CAST")
            override fun proceed(args: Array<Any>): Any? {
                proceedCount++
                if (args != null) mArgs = args as Array<Any?>
                // 真实框架把原方法异常原样上抛(不包 InvocationTargetException),
                // 反射调用需解包对齐该契约
                return try {
                    method.invoke(thisObject, *mArgs)
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    throw e.cause ?: e
                }
            }

            override fun proceedWith(value: Any): Any = value
            override fun proceedWith(value: Any, args: Array<Any>): Any = value
        }
    }

    private fun targetMethod(): Method = FakeTarget::class.java.getDeclaredMethod("greet", String::class.java)

    private fun throwingMethod(): Method = FakeTarget::class.java.getDeclaredMethod("boom")

    @Test
    fun beforeSetResultShortCircuitsOrigin() {
        val hooker = installAndCapture(object : XC_MethodHook() {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                param.setResult("short-circuit")
            }
        }, targetMethod())
        val chain = FakeChain(targetMethod(), FakeTarget(), arrayOf("x"))
        assertEquals("short-circuit", hooker.intercept(chain))
        assertEquals(0, chain.proceedCount)
    }

    @Test
    fun beforeSetThrowableShortCircuitsOrigin() {
        val hooker = installAndCapture(object : XC_MethodHook() {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                param.setThrowable(UnsupportedOperationException("blocked"))
            }
        }, targetMethod())
        val chain = FakeChain(targetMethod(), FakeTarget(), arrayOf("x"))
        val tr = assertThrows(UnsupportedOperationException::class.java) { hooker.intercept(chain) }
        assertEquals("blocked", tr.message)
        assertEquals(0, chain.proceedCount)
    }

    @Test
    fun beforeArgsMutationReachesOrigin() {
        val hooker = installAndCapture(object : XC_MethodHook() {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                param.args[0] = "rewritten"
            }
        }, targetMethod())
        assertEquals("hello rewritten", hooker.intercept(FakeChain(targetMethod(), FakeTarget(), arrayOf("x"))))
    }

    @Test
    fun afterSetResultOverridesOriginResult() {
        val hooker = installAndCapture(object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                param.setResult("overridden")
            }
        }, targetMethod())
        assertEquals("overridden", hooker.intercept(FakeChain(targetMethod(), FakeTarget(), arrayOf("x"))))
    }

    @Test
    fun beforeCallbackExceptionIsSwallowedAndOriginRuns() {
        val hooker = installAndCapture(object : XC_MethodHook() {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                throw IllegalStateException("before boom")
            }
        }, targetMethod())
        assertEquals("hello x", hooker.intercept(FakeChain(targetMethod(), FakeTarget(), arrayOf("x"))))
    }

    @Test
    fun afterCallbackExceptionKeepsOriginResult() {
        val hooker = installAndCapture(object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                throw IllegalStateException("after boom")
            }
        }, targetMethod())
        assertEquals("hello x", hooker.intercept(FakeChain(targetMethod(), FakeTarget(), arrayOf("x"))))
    }

    @Test
    fun originExceptionPropagatesWhenAfterUntouched() {
        val hooker = installAndCapture(object : XC_MethodHook() {}, throwingMethod())
        val tr = assertThrows(IllegalStateException::class.java) {
            hooker.intercept(FakeChain(throwingMethod(), FakeTarget(), arrayOf()))
        }
        assertEquals("origin boom", tr.message)
    }

    @Test
    fun afterCanReplaceOriginException() {
        val hooker = installAndCapture(object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                if (param.hasThrowable()) param.setThrowable(RuntimeException("replaced"))
            }
        }, throwingMethod())
        val tr = assertThrows(RuntimeException::class.java) {
            hooker.intercept(FakeChain(throwingMethod(), FakeTarget(), arrayOf()))
        }
        assertEquals("replaced", tr.message)
    }

    @Test
    fun afterCanSuppressOriginExceptionWithResult() {
        val hooker = installAndCapture(object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                if (param.hasThrowable()) param.setResult("recovered")
            }
        }, throwingMethod())
        assertEquals("recovered", hooker.intercept(FakeChain(throwingMethod(), FakeTarget(), arrayOf())))
    }

    /** 被 hook 的目标类: JVM 上真实可反射调用, 不涉及宿主类。 */
    @Suppress("unused")
    class FakeTarget {
        fun greet(name: String): String = "hello $name"
        fun boom(): String = throw IllegalStateException("origin boom")
    }
}
