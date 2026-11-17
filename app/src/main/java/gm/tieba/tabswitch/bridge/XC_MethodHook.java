package gm.tieba.tabswitch.bridge;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 旧版 {@code XC_MethodHook} 的等价实现，建立在 API 102 拦截链之上。
 *
 * <p>字段语义严格对齐旧版：
 * <ul>
 *   <li>{@code args} 是<b>可变</b>数组，回调里改元素即改变传给原方法的实参。</li>
 *   <li>{@code result}：before 阶段<b>经 {@link #setResult} 赋值</b>表示短路
 *       （跳过原方法），after 阶段赋值表示覆盖返回值。直接给字段赋非 null 值
 *       也被框架兜底为短路（{@code result} 初始恒为 null，非 null 必来自回调）；
 *       但直接赋 <b>null 的 void 短路无法与"未赋值"区分</b>，before 阶段短路
 *       必须用 {@code setResult(null)}。</li>
 *   <li>{@code throwable} 非空表示抛出该异常；before 阶段赋值同样短路。</li>
 * </ul>
 *
 * <p><b>空值注解约定</b>：{@link MethodHookParam} 的字段与方法<b>刻意不加</b>
 * {@code @Nullable}/{@code @NonNull}。旧版 Xposed API 没有任何空值注解，Kotlin 侧
 * 因此看到的是平台类型（{@code Any!}、{@code Method!}），既能直接传给非空形参，
 * 也能做空判断。加上注解会把平台类型收紧成可空类型，导致大量既有调用点
 * （{@code param.thisObject} 传给 {@code Any}、{@code param.method} 传给
 * {@code Method}）编译失败，且这些调用点在语义上本来就是安全的。
 */
public abstract class XC_MethodHook {

    /**
     * 旧版优先级常量。API 102 的 {@link XposedInterface#PRIORITY_HIGHEST} 是
     * {@code Integer.MAX_VALUE}，而旧版是 10000；两者都是"越大越先执行"，
     * 但本模块里 {@code PRIORITY_HIGHEST} 被用于"必须最先看到参数"的场景，
     * 直接沿用旧数值会排到默认优先级（50）之后，语义反转。故按方向映射。
     */
    public static final int PRIORITY_HIGHEST = XposedInterface.PRIORITY_HIGHEST;
    public static final int PRIORITY_DEFAULT = XposedInterface.PRIORITY_DEFAULT;
    public static final int PRIORITY_LOWEST = XposedInterface.PRIORITY_LOWEST;

    private final int mPriority;

    public XC_MethodHook() {
        this(PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.mPriority = priority;
    }

    public final int getPriority() {
        return mPriority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** 旧版 Unhook：保留以便调用方签名不变，真正卸载走 {@code HookHandle#unhook()}。 */
    public static final class Unhook {
        private final Member mMember;
        private final XposedInterface.HookHandle mHandle;

        Unhook(Member member, XposedInterface.HookHandle handle) {
            this.mMember = member;
            this.mHandle = handle;
        }

        public void unhook() {
            mHandle.unhook();
        }

        @NonNull
        @Override
        public String toString() {
            return "Unhook[" + mMember + ']';
        }
    }

    /**
     * 旧版 {@code XC_MethodReplacement}：无条件替换返回值。
     *
     * <p>旧实现是在 before 阶段直接 {@code setResult}，等价于"跳过原方法"。
     */
    public abstract static class XC_MethodReplacement extends XC_MethodHook {
        public XC_MethodReplacement() {
            super();
        }

        public XC_MethodReplacement(int priority) {
            super(priority);
        }

        @Override
        protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
            param.setResult(replaceHookedMethod(param));
        }

        @Override
        protected final void afterHookedMethod(MethodHookParam param) {
        }

        protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;

        /**
         * 旧版便捷构造：返回一个总是返回 {@code result} 的替换器。
         *
         * <p>注意：返回值按原样返回，不做类型适配。基本类型返回值请改用
         * {@code XposedContext.safeConstantReplacement}，否则装箱类型不匹配会在
         * 宿主调用点拆箱崩溃。
         */
        public static XC_MethodReplacement returnConstant(Object result) {
            return new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    return result;
                }
            };
        }
    }

    /**
     * 旧版 {@code MethodHookParam}。
     *
     * <p>字段可见性与可变性必须与旧版一致：业务代码里既有
     * {@code param.result = x} 的直接赋值，也有 {@code param.args[i] = x}
     * 的原地修改。<b>本类不加空值注解</b>，理由见类注释。
     */
    public static final class MethodHookParam {
        /** 被 hook 的可执行体（方法或构造器）。 */
        public final Executable executable;

        /** 兼容旧代码的 {@code param.method}（构造器 hook 时为 null）。 */
        public final Method method;

        /** {@code this} 指针；静态方法与构造器 before 阶段为 null。 */
        public final Object thisObject;

        /** 实参数组，可原地修改。 */
        public Object[] args;

        /**
         * 返回值。before 阶段经 {@link #setResult} 赋值即短路；直接赋非 null 值
         * 也被框架兜底为短路，但直接赋 null 的 void 短路必须用 {@code setResult(null)}。
         */
        public Object result;

        private Throwable mThrowable;
        private boolean mReturnEarly;

        MethodHookParam(Executable executable, Object thisObject, Object[] args) {
            this.executable = executable;
            this.method = executable instanceof Method ? (Method) executable : null;
            this.thisObject = thisObject;
            this.args = args;
        }

        public Object getResult() {
            return result;
        }

        /**
         * 设置返回值并置位短路标志。before 阶段调用即跳过原方法与后续回调；
         * after 阶段调用即覆盖返回值。
         */
        public void setResult(Object value) {
            this.result = value;
            this.mThrowable = null;
            this.mReturnEarly = true;
        }

        /** 仅回写返回值，不置位短路标志（拦截链内部使用）。 */
        void setResultInternal(Object value) {
            this.result = value;
        }

        /** 设置上抛异常并置位短路标志：before 阶段调用即让原方法以该异常失败。 */
        public void setThrowable(Throwable tr) {
            this.mThrowable = tr;
            this.mReturnEarly = tr != null;
        }

        void setThrowableInternal(Throwable tr) {
            this.mThrowable = tr;
        }

        void clearThrowableInternal() {
            this.mThrowable = null;
        }

        public Throwable getThrowable() {
            return mThrowable;
        }

        public boolean hasThrowable() {
            return mThrowable != null;
        }

        /** 是否已短路（不再执行原方法）。 */
        public boolean isReturnEarly() {
            return mReturnEarly;
        }

        void resetShortCircuit() {
            this.mReturnEarly = false;
        }

        @NonNull
        @Override
        public String toString() {
            return "MethodHookParam[" + executable + ']';
        }
    }
}
