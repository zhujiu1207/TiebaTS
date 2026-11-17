@file:JvmName("Parser")

package gm.tieba.tabswitch.util

import gm.tieba.tabswitch.bridge.XposedHelpers

// 楼层/楼中楼过滤热路径: content 每个元素都要读一次 text, 而 api-82 的
// XposedHelpers.findField 沿继承链 getDeclaredField 且无缓存, 长贴上百楼时
// 每次列表构建要重复上百次反射查找。按(元素类, 字段名)缓存 Field
// (findField 内部已 setAccessible), 命中后只剩一次 Field.get
private val sTextFieldCache =
    java.util.concurrent.ConcurrentHashMap<Pair<Class<*>, String>, java.lang.reflect.Field>()

fun parsePbContent(instance: Any?, fieldName: String?): String {
    if (instance == null || fieldName == null) return ""
    val contents = XposedHelpers.getObjectField(instance, fieldName) as? List<*> ?: return ""
    // 元素可能为 null, 逐条判空; mapNotNull 不拦截异常
    return contents.mapNotNull { e ->
        if (e == null) null else {
            sTextFieldCache.getOrPut(e.javaClass to "text") {
                XposedHelpers.findField(e.javaClass, "text")
            }.get(e) as? String
        }
    }.joinToString("")
}
