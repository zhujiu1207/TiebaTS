package gm.tieba.tabswitch.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 旧版 {@code XposedHelpers} 的等价实现。
 *
 * <p>与 {@link XposedBridge} 不同，这里的每个方法都只是<b>反射工具</b>，不触碰
 * 框架 API，因此可以在任何线程、任何时机调用。唯一需要框架的是
 * {@code findAndHookMethod}/{@code findAndHookConstructor}，它们转调
 * {@link XposedBridge}。
 *
 * <p><b>空值注解约定</b>：本类的参数与返回值<b>刻意不加</b>
 * {@code @Nullable}/{@code @NonNull}。旧版 Xposed API 没有空值注解，Kotlin 侧
 * 因此看到的是平台类型，既能直接传给非空形参，也能做空判断。加上注解会把平台
 * 类型收紧，导致既有调用点（如 {@code Long::class.javaPrimitiveType} 传入
 * {@code Class<*>} 形参）编译失败。
 *
 * <p>缓存策略：旧版对 {@code findField}/{@code findMethodExact} 有内部缓存，
 * 本模块的长贴/信息流路径会高频调用，因此这里同样缓存查找结果，避免每帧重复
 * {@code getDeclaredField}。
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    /** 字段查找缓存：key = 类名 + '@' + ClassLoader身份 + '#' + 字段名。 */
    private static final ConcurrentHashMap<String, Field> sFieldCache = new ConcurrentHashMap<>();
    /** 方法查找缓存：key = 类名 + '@' + ClassLoader身份 + '#' + 方法名 + 参数签名。 */
    private static final ConcurrentHashMap<String, Method> sMethodCache = new ConcurrentHashMap<>();

    // 宽松匹配缓存: 值为 Method(命中)或 LENIENT_MISS 哨兵(未命中负缓存)。
    // CHM 不允许 null 值, 故用哨兵对象
    private static final ConcurrentHashMap<String, Object> sLenientMethodCache =
            new ConcurrentHashMap<>();
    private static final Object LENIENT_MISS = new Object();

    // ------------------------------------------------------------------ class

    public static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        if (classLoader == null) {
            return Class.forName(className);
        }
        // initialize=false：旧版同样不触发静态初始化，避免提前跑宿主 <clinit>
        return Class.forName(className, false, classLoader);
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        try {
            return findClass(className, classLoader);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ field

    /** 旧版 {@code findField}：沿继承链查找字段，找不到抛 {@link NoSuchFieldError}。 */
    public static Field findField(Class<?> clazz, String fieldName) {
        // 键含 ClassLoader 身份维度(与 lenientKey 同口径): 宿主插件化场景下
        // 不同加载器可能加载同二进制名的类且字段集不同, 仅按类名入键会串缓存
        String key = clazz.getName() + '@' + System.identityHashCode(clazz.getClassLoader())
                + '#' + fieldName;
        Field cached = sFieldCache.get(key);
        if (cached != null) return cached;
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                sFieldCache.put(key, field);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + fieldName);
    }

    /**
     * 旧版 {@code findFirstFieldByExactType}：按<b>精确</b>类型找第一个字段。
     * 沿继承链查找（本类声明字段优先，未命中则向超类上溯），找不到抛 {@link NoSuchFieldError}。
     */
    public static Field findFirstFieldByExactType(Class<?> clazz, Class<?> type) {
        Class<?> clz = clazz;
        do {
            for (Field field : clz.getDeclaredFields()) {
                if (field.getType() == type) {
                    field.setAccessible(true);
                    return field;
                }
            }
        } while ((clz = clz.getSuperclass()) != null);
        throw new NoSuchFieldError(clazz.getName() + " has no field of type " + type);
    }

    public static Object getObjectField(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (Throwable tr) {
            throw new IllegalStateException("getObjectField " + fieldName + " failed", tr);
        }
    }

    public static int getIntField(Object obj, String fieldName) {
        if (obj == null) return 0;
        try {
            return findField(obj.getClass(), fieldName).getInt(obj);
        } catch (Throwable tr) {
            throw new IllegalStateException("getIntField " + fieldName + " failed", tr);
        }
    }

    public static long getLongField(Object obj, String fieldName) {
        if (obj == null) return 0L;
        try {
            return findField(obj.getClass(), fieldName).getLong(obj);
        } catch (Throwable tr) {
            throw new IllegalStateException("getLongField " + fieldName + " failed", tr);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        if (obj == null) return;
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (Throwable tr) {
            throw new IllegalStateException("setObjectField " + fieldName + " failed", tr);
        }
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        if (obj == null) return;
        try {
            findField(obj.getClass(), fieldName).setInt(obj, value);
        } catch (Throwable tr) {
            throw new IllegalStateException("setIntField " + fieldName + " failed", tr);
        }
    }

    public static int getStaticIntField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).getInt(null);
        } catch (Throwable tr) {
            throw new IllegalStateException("getStaticIntField " + fieldName + " failed", tr);
        }
    }

    public static boolean getStaticBooleanField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).getBoolean(null);
        } catch (Throwable tr) {
            throw new IllegalStateException("getStaticBooleanField " + fieldName + " failed", tr);
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName).get(null);
        } catch (Throwable tr) {
            throw new IllegalStateException("getStaticObjectField " + fieldName + " failed", tr);
        }
    }

    public static void setStaticBooleanField(Class<?> clazz, String fieldName, boolean value) {
        try {
            findField(clazz, fieldName).setBoolean(null, value);
        } catch (Throwable tr) {
            throw new IllegalStateException("setStaticBooleanField " + fieldName + " failed", tr);
        }
    }

    // ----------------------------------------------------------------- method

    /**
     * 旧版 {@code findMethodExact}：参数类型接受 {@link Class} 或 {@link String}（类名）。
     * 业务代码里两种写法都有（如 {@code "com.baidu.tbadk.TbPageContext"}）。
     */
    public static Method findMethodExact(Class<?> clazz, String methodName, Object... parameterTypes) {
        Class<?>[] resolved = resolveParameterTypes(parameterTypes, clazz.getClassLoader());
        // 键含 ClassLoader 身份维度, 口径与 findField/lenientKey 一致
        String key = clazz.getName() + '@' + System.identityHashCode(clazz.getClassLoader())
                + '#' + methodName + '#' + Arrays.toString(resolved);
        Method cached = sMethodCache.get(key);
        if (cached != null) return cached;
        Class<?> current = clazz;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName, resolved);
                method.setAccessible(true);
                sMethodCache.put(key, method);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName + Arrays.toString(resolved));
    }

    /** 旧版 {@code findMethodExactIfExists(Class, String, Object...)}：找不到返回 null。 */
    public static Method findMethodExactIfExists(
            Class<?> clazz, String methodName, Object... parameterTypes) {
        try {
            return findMethodExact(clazz, methodName, parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 旧版 {@code findMethodExactIfExists(String, ClassLoader, String, Object...)}。 */
    public static Method findMethodExactIfExists(
            String className, ClassLoader classLoader, String methodName, Object... parameterTypes) {
        try {
            return findMethodExactIfExists(
                    findClassOrThrow(className, classLoader), methodName, parameterTypes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 把 {@code Class}/{@code String} 混排的参数类型列表归一成 {@code Class[]}。 */
    private static Class<?>[] resolveParameterTypes(Object[] parameterTypes, ClassLoader classLoader) {
        if (parameterTypes == null) return new Class<?>[0];
        Class<?>[] resolved = new Class<?>[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object item = parameterTypes[i];
            if (item instanceof Class) {
                resolved[i] = (Class<?>) item;
            } else if (item instanceof String) {
                resolved[i] = findClassOrThrow((String) item, classLoader);
            } else {
                throw new IllegalArgumentException("parameter type must be Class or String: " + item);
            }
        }
        return resolved;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        // null receiver 静默返回 null 是与旧版的刻意差异(旧版抛 NPE): hooker 侧
        // 大量调用点依赖"宿主对象为 null 时整段调用静默降级"的行为(如页面销毁
        // 窗口期), 抛 NPE 会让这些调用点逐一改造判空。副作用不发生也不留痕,
        // 排查"callMethod 没生效"时先查 receiver 是否为 null
        if (obj == null) return null;
        Method method = resolveMethod(obj.getClass(), methodName, args);
        try {
            return method.invoke(obj, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("callMethod " + methodName + " threw", e.getCause());
        } catch (Throwable tr) {
            throw new IllegalStateException("callMethod " + methodName + " failed", tr);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        Method method = resolveMethod(clazz, methodName, args);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("callStaticMethod " + methodName + " threw", e.getCause());
        } catch (Throwable tr) {
            throw new IllegalStateException("callStaticMethod " + methodName + " failed", tr);
        }
    }

    /**
     * 按实参类型解析方法：先按精确参数类型找，再按"可赋值"匹配。
     * 宽松分支按 getDeclaredMethods() 声明序取<b>首个</b>形参可赋值的方法
     * （非旧版 findMethodBestMatch 的最优匹配打分）：存在同名重载时解析结果
     * 可能与旧版不同，宿主版本更新改变声明顺序后行为可能翻转——业务代码
     * 依赖的是它对装箱/子类实参的宽容度，不是匹配优先级。
     */
    private static Method resolveMethod(Class<?> clazz, String methodName, Object[] args) {
        int argc = args == null ? 0 : args.length;
        Class<?>[] exact = new Class<?>[argc];
        for (int i = 0; i < argc; i++) {
            exact[i] = args[i] == null ? null : args[i].getClass();
        }
        if (argc == 0 || !containsNull(exact)) {
            try {
                return findMethodExact(clazz, methodName, (Object[]) exact);
            } catch (NoSuchMethodError ignored) {
            }
        }
        // 宽松匹配：参数个数相同且形参可由实参赋值。
        // 结果缓存(含未命中负缓存): 此分支每次调用沿继承链 getDeclaredMethods()
        // 全表扫描, 而 callMethod/callStaticMethod 是 hooker 热路径主力——项目内
        // FragmentTab/HomeTopBar/HomeSearchIcon 曾为绕开这点各建局部缓存。
        // 解析结果由 (起始类, 方法名, 实参运行时类型序列) 完全决定且进程内稳定
        // (继承链与 declaredMethods 顺序运行期不变), 可安全缓存;
        // null 实参以占位符入键, 其匹配规则("非基本类型即可")同样由键唯一决定
        String key = lenientKey(clazz, methodName, args);
        Object cached = sLenientMethodCache.get(key);
        if (cached instanceof Method) return (Method) cached;
        if (cached == LENIENT_MISS) {
            throw new NoSuchMethodError(clazz.getName() + "#" + methodName + " with " + argc + " args");
        }
        Class<?> current = clazz;
        Method found = null;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != argc) continue;
                boolean ok = true;
                for (int i = 0; i < argc; i++) {
                    if (!isAssignable(params[i], args[i])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    found = method;
                    break;
                }
            }
            if (found != null) break;
            current = current.getSuperclass();
        }
        if (found != null) {
            found.setAccessible(true);
            sLenientMethodCache.put(key, found);
            return found;
        }
        sLenientMethodCache.put(key, LENIENT_MISS);
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName + " with " + argc + " args");
    }

    private static String lenientKey(Class<?> clazz, String methodName, Object[] args) {
        // 键须含 ClassLoader 维度: 宿主插件化场景下不同加载器可能加载同二进制
        // 名的类且方法集不同, 仅按类名入键会互相串缓存结果
        StringBuilder sb = new StringBuilder(clazz.getName())
                .append('@').append(System.identityHashCode(clazz.getClassLoader()))
                .append('#').append(methodName).append('/');
        if (args != null) {
            for (Object arg : args) {
                sb.append(arg == null ? "\u0000" : arg.getClass().getName()).append(';');
            }
        }
        return sb.toString();
    }

    private static boolean containsNull(Class<?>[] types) {
        for (Class<?> type : types) {
            if (type == null) return true;
        }
        return false;
    }

    private static boolean isAssignable(Class<?> param, Object arg) {
        if (arg == null) return !param.isPrimitive();
        Class<?> argType = arg.getClass();
        if (param.isAssignableFrom(argType)) return true;
        // 基本类型与包装类型的互认
        return (param == int.class && argType == Integer.class)
                || (param == long.class && argType == Long.class)
                || (param == boolean.class && argType == Boolean.class)
                || (param == float.class && argType == Float.class)
                || (param == double.class && argType == Double.class)
                || (param == short.class && argType == Short.class)
                || (param == byte.class && argType == Byte.class)
                || (param == char.class && argType == Character.class);
    }

    // ------------------------------------------------------------- newInstance

    public static Object newInstance(Class<?> clazz, Object... args) {
        int argc = args == null ? 0 : args.length;
        Class<?>[] exact = new Class<?>[argc];
        for (int i = 0; i < argc; i++) {
            exact[i] = args[i] == null ? null : args[i].getClass();
        }
        Constructor<?> ctor = null;
        if (argc == 0 || !containsNull(exact)) {
            try {
                ctor = clazz.getDeclaredConstructor(exact);
            } catch (NoSuchMethodException ignored) {
            }
        }
        if (ctor == null) {
            for (Constructor<?> candidate : clazz.getDeclaredConstructors()) {
                Class<?>[] params = candidate.getParameterTypes();
                if (params.length != argc) continue;
                boolean ok = true;
                for (int i = 0; i < argc; i++) {
                    if (!isAssignable(params[i], args[i])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    ctor = candidate;
                    break;
                }
            }
        }
        if (ctor == null) {
            throw new IllegalStateException("no constructor for " + clazz.getName() + " with " + argc + " args");
        }
        ctor.setAccessible(true);
        try {
            return ctor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("constructor of " + clazz.getName() + " threw", e.getCause());
        } catch (Throwable tr) {
            throw new IllegalStateException("newInstance " + clazz.getName() + " failed", tr);
        }
    }

    // ---------------------------------------------------------------- hooking

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        Object[] split = splitCallback(parameterTypesAndCallback, "findAndHookMethod");
        XC_MethodHook callback = (XC_MethodHook) split[split.length - 1];
        Object[] types = Arrays.copyOf(split, split.length - 1);
        Method method = findMethodExact(clazz, methodName, types);
        return XposedBridge.hookMethod(method, callback);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader, String methodName,
            Object... parameterTypesAndCallback) {
        return findAndHookMethod(
                findClassOrThrow(className, classLoader), methodName, parameterTypesAndCallback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(
            Class<?> clazz, Object... parameterTypesAndCallback) {
        Object[] split = splitCallback(parameterTypesAndCallback, "findAndHookConstructor");
        XC_MethodHook callback = (XC_MethodHook) split[split.length - 1];
        Object[] types = Arrays.copyOf(split, split.length - 1);
        Class<?>[] resolved = resolveParameterTypes(types, clazz.getClassLoader());
        Constructor<?> ctor;
        try {
            ctor = clazz.getDeclaredConstructor(resolved);
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodError(clazz.getName() + ".<init>" + Arrays.toString(resolved));
        }
        ctor.setAccessible(true);
        return XposedBridge.hookMethod(ctor, callback);
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(
            String className, ClassLoader classLoader, Object... parameterTypesAndCallback) {
        return findAndHookConstructor(
                findClassOrThrow(className, classLoader), parameterTypesAndCallback);
    }

    private static Object[] splitCallback(Object[] parameterTypesAndCallback, String caller) {
        if (parameterTypesAndCallback == null || parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback passed to " + caller);
        }
        return parameterTypesAndCallback;
    }

    private static Class<?> findClassOrThrow(String className, ClassLoader classLoader) {
        try {
            return findClass(className, classLoader);
        } catch (ClassNotFoundException e) {
            throw new ClassNotFoundError(className);
        }
    }

    /**
     * 旧版 {@code XposedHelpers.ClassNotFoundError}：保留类型名以兼容既有 catch 块。
     *
     * <p>注意旧版它是 {@link NoClassDefFoundError} 的子类，业务代码里
     * {@code catch (ignored: Error)} 与 {@code catch (tr: Throwable)} 都能接住。
     */
    public static class ClassNotFoundError extends NoClassDefFoundError {
        public ClassNotFoundError(String message) {
            super(message);
        }
    }
}
