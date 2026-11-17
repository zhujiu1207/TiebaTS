package gm.tieba.tabswitch.hooker.auto

import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.MSG_CENTER_CONTAINER_VIEW
import gm.tieba.tabswitch.logBoth

class MsgCenterTab : XposedContext(), IHooker {

    override fun key(): String {
        return "msg_center_tab"
    }

    override fun hook() {
        val longMethods = findClass(MSG_CENTER_CONTAINER_VIEW).declaredMethods.filter {
            it.parameterTypes.isEmpty() && it.returnType == Long::class.javaPrimitiveType
        }
        // 该 hook 假设类内只有一个"时间戳类"无参 long 方法; 出现多个时说明宿主
        // 结构已漂移, 命中集里必然混有非时间戳方法, 盲写同一 Long 字段会污染无关
        // 状态(排序/未读时间戳等)——宁可功能失效, 放弃整个 hook 并留线索
        if (longMethods.size > 1) {
            logBoth("MsgCenterTab: 无参 long 方法有 ${longMethods.size} 个(${longMethods.joinToString(",") { it.name }}), 疑似宿主结构漂移, 放弃 hook 防止污染无关字段")
            return
        }
        // 目标字段可能是装箱 Long 或基本型 long: findFirstFieldByExactType
        // 按精确类型匹配, 单一形态会永远 miss。字段在注册期解析一次并缓存,
        // 不走"回调内先抛 NoSuchFieldError 再回退"的路径——异常构造+全字段
        // 线性扫描被乘到消息中心的高频钩点上。
        // 不做字段数守卫(与方法侧"多个 ()long 即放弃"不对称是有意的): 22.14
        // 宿主类本身合法声明了 h/x 两个装箱 Long 字段(jadx 核实), 按数量放弃
        // 会禁用实测正常的功能; 这里"第一个装箱 Long"取到 h(声明序在前)是
        // 针对 22.11-22.14 逐一验证过的形态, 方法侧守卫(唯一 ()long 方法)
        // 就是字段布局未漂移的证据
        val containerClass = findClass(MSG_CENTER_CONTAINER_VIEW)
        val timestampField = try {
            XposedHelpers.findFirstFieldByExactType(containerClass, Long::class.javaObjectType)
        } catch (ignored: NoSuchFieldError) {
            try {
                XposedHelpers.findFirstFieldByExactType(containerClass, Long::class.javaPrimitiveType)
            } catch (ignored: Throwable) {
                null
            }
        }
        longMethods.forEach { method ->
            hookBeforeMethod(
                MSG_CENTER_CONTAINER_VIEW,
                method.name
            ) { param ->
                timestampField?.let { field ->
                    try {
                        // Field.set 对装箱 Long 与基本型 long 字段都适用(-1L 自动装拆箱),
                        // 不能用 setLong: 目标字段实测为装箱 Long(宿主 h/x 字段), setLong
                        // 会抛 IllegalArgumentException 被吞掉, 钩子退化为空操作
                        field.set(param.thisObject, -1L)
                    } catch (tr: Throwable) {
                        // 字段类型漂移(如改为 int)时功能静默失效, 须留线索;
                        // logHookError 自带 60s 限流, 高频钩点上不会刷屏
                        logHookError("MsgCenterTab: 时间戳字段写入", tr)
                    }
                }
            }
        }
    }
}
