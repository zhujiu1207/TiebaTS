@file:JvmName("ReflectUtils")

package gm.tieba.tabswitch.util

import android.app.Activity
import android.app.Application
import androidx.annotation.ColorInt
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.XposedContext.Companion.findClass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

fun getR(innerClassName: String, fieldName: String): Int {
    return XposedContext.getContext().resources
        .getIdentifier(fieldName, innerClassName, XposedContext.getContext().packageName)
}

@ColorInt
fun getColor(fieldName: String): Int {
    return XposedContext.getContext().getColor(
        getR("color", fieldName + getTbSkin(XposedContext.getContext()))
    )
}

fun getDimen(fieldName: String): Float {
    // 常量直返表仅收录有调用方的键(2026-11-21 审查清理: ds10/ds140 无引用已删);
    // 其余键走宿主资源表真实值
    when (fieldName) {
        "ds20" -> return dipToPx(XposedContext.getContext(), 10f).toFloat()
        "ds30" -> return dipToPx(XposedContext.getContext(), 15f).toFloat()
        "ds32" -> return dipToPx(XposedContext.getContext(), 16f).toFloat()
    }
    return XposedContext.getContext().resources.getDimension(getR("dimen", fieldName))
}

fun getDimenDip(fieldName: String): Float {
    // 同上: 仅 fontsize22 有调用方(TSPreferenceHelper), 28/36 死分支已删
    when (fieldName) {
        "fontsize22" -> return 11f
    }
    return pxToDip(XposedContext.getContext(), getDimen(fieldName)).toFloat()
}

fun getDrawableId(fieldName: String): Int {
    return getR("drawable", fieldName)
}

// 按类型/类名解析字段的缓存: findFirstFieldByExactType 每次调用都 getDeclaredFields()
// 沿继承链线性扫描, 而 feed/楼中楼每条目都要走这里(Purge 业务字段、Parser 楼中楼元素),
// 缓存后命中路径 O(1)。键含宿主类与目标类型, 规则库驱动的字段名在进程内不变, 可安全缓存;
// 只缓存成功解析, 失败路径维持原抛错语义
private val sFieldCache =
    java.util.concurrent.ConcurrentHashMap<Pair<Class<*>, Any>, java.lang.reflect.Field>()

private fun resolveField(instance: Any?, type: Class<*>): java.lang.reflect.Field {
    val clazz = instance?.javaClass ?: throw NullPointerException("instance == null")
    return sFieldCache.getOrPut(clazz to type) {
        XposedHelpers.findFirstFieldByExactType(clazz, type)?.apply { isAccessible = true }
            ?: throw NoSuchFieldError("field of type ${type.name} not found in ${clazz.name}")
    }
}

/**
 * Returns the first field of the given type in a class.
 * Might be useful for Proguard'ed classes to identify fields with unique types.
 *
 * @param instance The class which either declares or inherits the field.
 * @param type     The type of the field.
 * @return A reference to the first field of the given type.
 * @throws NoSuchFieldError In case no matching field was not found.
 */
fun <T> getObjectField(instance: Any?, type: Class<T>): T? {
    return try {
        type.cast(resolveField(instance, type)[instance])
    } catch (e: IllegalAccessException) {
        XposedBridge.log(e)
        throw IllegalAccessError(e.message)
    }
}

fun getObjectField(instance: Any?, className: String): Any? {
    return try {
        resolveField(instance, findClass(className))[instance]
    } catch (e: IllegalAccessException) {
        XposedBridge.log(e)
        throw IllegalAccessError(e.message)
    }
}

fun setObjectField(instance: Any?, className: String, value: Any?) {
    try {
        resolveField(instance, findClass(className))[instance] = value
    } catch (e: IllegalAccessException) {
        XposedBridge.log(e)
        throw IllegalAccessError(e.message)
    }
}

fun findFirstMethodByExactType(cls: Class<*>, vararg paramTypes: Class<*>): Method {
    return cls.declaredMethods.firstOrNull { method ->
        method.parameterTypes.contentEquals(paramTypes)
    } ?: throw NoSuchMethodError(paramTypes.contentToString())
}

fun findFirstMethodByExactType(className: String, vararg paramTypes: Class<*>): Method {
    return findFirstMethodByExactType(
        findClass(className),
        *paramTypes
    )
}

fun findFirstMethodByExactReturnType(cls: Class<*>, returnType: Class<*>): Method {
    return cls.declaredMethods.firstOrNull { method ->
        method.returnType == returnType
    } ?: throw NoSuchMethodError(returnType.toString())
}

fun findFirstMethodByExactReturnType(className: String, returnType: Class<*>): Method {
    return findFirstMethodByExactReturnType(
        findClass(className),
        returnType
    )
}

fun callMethod(method: Method, instance: Any?, vararg args: Any?): Any? {
    return try {
        method.isAccessible = true
        method.invoke(instance, *args)
    } catch (e: IllegalAccessException) {
        XposedBridge.log(e)
        throw IllegalArgumentException(e)
    } catch (e: InvocationTargetException) {
        XposedBridge.log(e)
        throw IllegalArgumentException(e)
    }
}

fun callStaticMethod(method: Method, vararg args: Any?): Any? {
    return callMethod(method, null, *args)
}

fun getTbadkCoreApplicationInst(): Application = XposedHelpers.callStaticMethod(
    findClass("com.baidu.tbadk.core.TbadkCoreApplication"),
    "getInst"
) as Application

// 返回可空: 宿主在前台无 Activity(页面切换/销毁阶段)时 getCurrentActivity 为 null;
// 从平台类型 as Activity 的 checkcast 不拦截 null, 必须用 as? 显式转为可空。
// getInst() 本身也可能为 null(进程极早期/异常态), callMethod 对 null receiver 抛 NPE,
// 而调用方普遍以 "getCurrentActivity() ?: return" 判空——必须让可空承诺真实成立
fun getCurrentActivity(): Activity? = runCatching {
    XposedHelpers.callMethod(getTbadkCoreApplicationInst(), "getCurrentActivity") as? Activity
}.getOrNull()
