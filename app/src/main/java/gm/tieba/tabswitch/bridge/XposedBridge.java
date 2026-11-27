package gm.tieba.tabswitch.bridge;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * libxposed API 102 之上的旧版 XposedBridge 兼容层。
 *
 * <p>API 102 明确禁止 libxposed 模块调用 {@code de.robv.android.xposed} 系列 API，
 * 而本模块的历史代码全部建立在那套 API 之上。与其把 49 个文件的回调签名逐个改写
 * （改写面越大，回归风险越高），这里把旧 API 的语义逐条映射到 API 102 的拦截链上，
 * 业务代码保持原样。
 *
 * <p>语义映射要点（必须与旧版一致，否则宿主行为会变）：
 * <ul>
 *   <li>{@code beforeHookedMethod} 中调用 {@code setResult}/{@code setThrowable}
 *       即短路：不再执行原方法与后续回调。</li>
 *   <li>{@code afterHookedMethod} 中调用 {@code setResult}/{@code setThrowable}
 *       覆盖返回值/异常。</li>
 *   <li>回调抛异常：before 阶段异常被吞掉并继续走原方法，after 阶段异常同样被吞掉
 *       并保留原返回值（旧版行为，保证 hook 自身故障不放大成宿主崩溃）。</li>
 * </ul>
 *
 * <p>本类不得直接引用 {@code de.robv.android.xposed}：那个包在 API 102 下由框架
 * 主动拒绝，引用即抛异常。
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    /** 当前进程的模块入口实例；由 {@code XposedInit} 在入口回调中登记。 */
    private static final AtomicReference<XposedModule> sModule = new AtomicReference<>(null);

    /** 框架未就绪（入口尚未登记）时的兜底日志出口。 */
    private static volatile LogSink sFallbackLogSink;

    /**
     * 日志出口：模块入口登记前，{@code XposedBridge.log} 需要一个可用落点。
     * 由 Logger 实现并注入，避免本类反向依赖 Logger（会形成初始化环）。
     */
    public interface LogSink {
        void log(String message);

        void log(Throwable tr);
    }

    public static void setLogSink(LogSink sink) {
        sFallbackLogSink = sink;
    }

    /**
     * 高频打点出口：每次 hook 回调进出各写一条到 native 环形缓冲。
     *
     * <p>由 {@code NativeCrashGuard} 实现并注入。用接口而非直接调用，原因同
     * {@link LogSink}：{@code NativeCrashGuard} 依赖 {@code Logger}，本类若反向
     * 依赖它会形成初始化环。
     *
     * <p>实现必须是<b>无 IO、无分配、不抛异常</b>的：它会在每一次被 hook 的方法
     * 调用时执行，一旦它自己变慢或抛异常，就会把 hook 变成故障源。
     */
    public interface TraceSink {
        /** 注册标签，返回 id；-1 表示不可用。 */
        int registerTag(String tag);

        /** 用 id 打点。 */
        void trace(int id, String detail);
    }

    private static volatile TraceSink sTraceSink;

    public static void setTraceSink(TraceSink sink) {
        sTraceSink = sink;
    }

    /**
     * 从方法签名生成稳定的打点标签，如 {@code "com.baidu.tieba.X->a"}。
     *
     * <p>用混淆后的原始名而非可读名：崩溃现场要能直接和 dex 对上，
     * 可读名反而需要额外映射。点分形式非 JNI 描述符——标签只用于
     * 日志里肉眼比对 dex，不需要解析器可读。
     */
    private static String tagOf(Executable executable) {
        try {
            String cls = executable.getDeclaringClass().getName();
            String name = executable instanceof Method
                    ? executable.getName()
                    : "<init>";
            return cls + "->" + name;
        } catch (Throwable ignored) {
            return "hook";
        }
    }

    public static void attach(XposedModule module) {
        sModule.set(module);
    }

    // 仅本类 invokeOriginalMethod/install 内部使用; 无外部调用方故不公开
    private static XposedModule requireModule() {
        XposedModule module = sModule.get();
        if (module == null) {
            throw new IllegalStateException("libxposed module entry is not attached yet");
        }
        return module;
    }

    // ---------------------------------------------------------------- logging

    /**
     * 旧版 {@code XposedBridge.log(String)}：写框架日志。
     * 框架未就绪时退回到注入的 sink，保证早期日志不丢。
     */
    public static void log(String text) {
        XposedModule module = sModule.get();
        if (module != null) {
            try {
                module.log(android.util.Log.INFO, null, text);
                return;
            } catch (Throwable ignored) {
                // 框架日志通道异常时继续走 sink
            }
        }
        LogSink sink = sFallbackLogSink;
        if (sink != null) {
            try {
                sink.log(text);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void log(Throwable tr) {
        XposedModule module = sModule.get();
        if (module != null) {
            try {
                module.log(android.util.Log.ERROR, null, String.valueOf(tr), tr);
                return;
            } catch (Throwable ignored) {
            }
        }
        LogSink sink = sFallbackLogSink;
        if (sink != null) {
            try {
                sink.log(tr);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ hooks

    /**
     * 旧版 {@code hookMethod}：接受 {@link Method} 或 {@link Constructor}。
     *
     * <p>旧版签名就是 {@code hookMethod(Member, XC_MethodHook)}，构造器也走这条路径，
     * 因此这里必须收 {@link Executable} 而不是 {@link Method}。
     */
    @NonNull
    public static XC_MethodHook.Unhook hookMethod(Executable executable, XC_MethodHook callback) {
        XposedInterface.HookHandle handle = install(executable, callback, callback.getPriority());
        return new XC_MethodHook.Unhook(executable, handle);
    }

    /**
     * 旧版 {@code hookAllMethods}：按方法名匹配类中全部同名重载。
     */
    @NonNull
    public static List<XposedInterface.HookHandle> hookAllMethods(
            Class<?> clazz, String methodName, XC_MethodHook callback) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) continue;
            if (method.isSynthetic() || method.isBridge()) continue;
            try {
                handles.add(install(method, callback, callback.getPriority()));
            } catch (Throwable tr) {
                // 单个重载安装失败不影响其余重载, 但必须留痕: 关键调用方
                // (如 blockMsaSecurityLibrary 屏蔽 libmsaoaidsec.so)依赖这里,
                // 全部重载静默失败时用户只会看到"偶发启动闪退"而无任何日志
                log("hookAllMethods: install " + clazz.getName()
                        + "#" + methodName + " failed: " + tr);
            }
        }
        return handles;
    }

    /** 旧版 {@code hookAllConstructors}。 */
    @NonNull
    public static List<XposedInterface.HookHandle> hookAllConstructors(
            Class<?> clazz, XC_MethodHook callback) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            try {
                handles.add(install(ctor, callback, callback.getPriority()));
            } catch (Throwable tr) {
                // 与 hookAllMethods 同口径留痕: 全部构造器静默失败时(如 R8 改名/
                // 框架侧安装异常), 调用方(LotteryChainLearner 的 TbTextMsg 构造
                // 旁听)只会表现为"学习链永不就绪"而无线索
                log("hookAllConstructors: install " + clazz.getName()
                        + "#<init>" + java.util.Arrays.toString(ctor.getParameterTypes())
                        + " failed: " + tr);
            }
        }
        return handles;
    }

    /**
     * 旧版 {@code invokeOriginalMethod}：跳过全部 hook 直接调用原方法。
     *
     * <p>API 102 用 {@link XposedInterface.Invoker.Type#ORIGIN} 表达同一语义。
     * 反射访问检查由框架侧 invoker 负责，不需要额外 setAccessible。
     */
    public static Object invokeOriginalMethod(Method method, Object thisObject, Object[] args)
            throws Throwable {
        XposedModule module = requireModule();
        XposedInterface.Invoker<?, Method> invoker = module.getInvoker(method);
        invoker.setType(XposedInterface.Invoker.Type.ORIGIN);
        try {
            return invoker.invoke(thisObject, args == null ? new Object[0] : args);
        } catch (InvocationTargetException e) {
            // 旧 API 把原方法抛出的异常原样抛出（不包 InvocationTargetException）
            throw e.getCause() == null ? e : e.getCause();
        }
    }

    // -------------------------------------------------------------- internals

    @NonNull
    private static XposedInterface.HookHandle install(
            Executable executable, XC_MethodHook callback, int priority) {
        XposedModule module = requireModule();
        XposedInterface.HookBuilder builder = module.hook(executable);
        // 旧版优先级数值越大越先执行，API 102 同向（PRIORITY_HIGHEST 先执行）
        builder.setPriority(priority);
        // 回调自身抛异常时保持旧版"吞掉并继续"的语义，不能中断宿主调用链
        builder.setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE);
        // 标签在注册期解析一次, 之后每次回调只传 id, 不在热路径上做字符串拼接
        int tagId = -1;
        TraceSink sink = sTraceSink;
        if (sink != null) {
            try {
                tagId = sink.registerTag(tagOf(executable));
            } catch (Throwable ignored) {
            }
        }
        return builder.intercept(new CallbackInterceptor(callback, tagId));
    }

    /** 把一次拦截链回调翻译成旧版 before/after 两阶段语义。 */
    private static final class CallbackInterceptor implements XposedInterface.Hooker {
        private final XC_MethodHook mCallback;
        /** native 侧标签 id；-1 表示打点不可用。 */
        private final int mTagId;

        CallbackInterceptor(XC_MethodHook callback, int tagId) {
            this.mCallback = callback;
            this.mTagId = tagId;
        }

        /**
         * 往环形缓冲写一条事件。
         *
         * <p>这是定位 native 崩溃的核心：崩溃现场只能看到"崩在 libart 里"，
         * 而"崩之前模块正在跑哪个 hook"只有靠这条记录。写的是内存，无 IO，
         * 因此可以放在每一次回调的热路径上。
         *
         * <p>整个方法吞掉所有异常：打点失败绝不能影响宿主调用链。
         */
        private void trace(String phase) {
            if (mTagId < 0) return;
            TraceSink sink = sTraceSink;
            if (sink == null) return;
            try {
                sink.trace(mTagId, phase);
            } catch (Throwable ignored) {
            }
        }

        @Override
        public Object intercept(@NonNull XposedInterface.Chain chain) throws Throwable {
            XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam(
                    chain.getExecutable(), chain.getThisObject(), toArray(chain.getArgs()));

            // ---- before
            trace("enter");
            try {
                mCallback.beforeHookedMethod(param);
            } catch (Throwable tr) {
                // 旧版行为：before 回调异常被记录后忽略，原方法照常执行
                logHookError("before", tr);
                param.resetShortCircuit();
            }
            if (param.isReturnEarly()) {
                // 短路也要补收尾: setResult 短路是 hooker 的常态路径, 若不写 done,
                // 日志里会大量合法存在"enter 无 done", 稀释"enter 无 done = 崩在
                // 该次调用"这条排查规则的信噪比——补齐后唯一的 enter-only 即真崩溃
                trace("done");
                if (param.hasThrowable()) throw param.getThrowable();
                return param.getResult();
            }
            // 兼容历史写法: before 回调直接给 result 字段赋了非 null 值(未走 setResult)
            // 也视为短路——result 初始恒为 null, 非 null 只可能来自回调赋值。
            // 直接赋 null 的 void 短路无法与"未赋值"区分, 该类调用点必须改用
            // setResult(null) (全库已清点改造, 此兜底防御未来新增的旧式写法)
            if (param.getResult() != null) {
                trace("done");
                return param.getResult();
            }

            // ---- origin
            Object result;
            try {
                result = chain.proceed(param.args);
            } catch (Throwable tr) {
                param.setThrowableInternal(tr);
                param.setResultInternal(null);
                // ---- after（异常路径）
                try {
                    mCallback.afterHookedMethod(param);
                } catch (Throwable afterError) {
                    logHookError("after", afterError);
                }
                // 记 "throw" 而非 "done": 崩溃日志里能区分"原方法正常返回"和
                // "原方法抛异常"，后者往往是问题起点
                trace("throw");
                if (param.hasThrowable()) throw param.getThrowable();
                return param.getResult();
            }

            param.setResultInternal(result);
            param.clearThrowableInternal();

            // ---- after（正常路径）
            try {
                mCallback.afterHookedMethod(param);
            } catch (Throwable tr) {
                logHookError("after", tr);
                // after 抛异常时不在此提前 return: 沿用尾部公共出口按 param.result
                // 收口(与旧版一致)——回调抛异常前已 setResult 的值保留; 未设置时
                // result 字段已持有原方法返回值(上方 setResultInternal), 两种情形
                // 口径统一。此前的"提前 return result"会把已 set 的值丢掉
            }
            trace("done");
            if (param.hasThrowable()) throw param.getThrowable();
            return param.getResult();
        }

        // 异常落盘限流: 与 XposedContext.logHookError 共用 HookErrorRateLimiter
        // (60s 有界 map, 裁决单点)。replace 包装层重抛的异常会落到 before 分支这里,
        // 若无限流, 签名漂移的热路径 replace 钩点会在每次宿主回调时刷穿框架日志,
        // 击穿 XposedContext 侧的限流设计。key 只含阶段+异常类, 不含 message(动态
        // 值会击穿限流)
        private static void logHookError(String phase, Throwable tr) {
            try {
                if (!gm.tieba.tabswitch.util.HookErrorRateLimiter.shouldLog(phase, tr)) return;
                XposedBridge.log("hook callback " + phase + " failed: " + tr);
            } catch (Throwable ignored) {
            }
        }

        private static Object[] toArray(List<Object> args) {
            return args == null ? new Object[0] : args.toArray();
        }
    }
}
