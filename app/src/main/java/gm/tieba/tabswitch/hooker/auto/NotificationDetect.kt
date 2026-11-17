package gm.tieba.tabswitch.hooker.auto

import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker

class NotificationDetect : XposedContext(), IHooker {

    override fun key(): String {
        return "notification_detect"
    }

    override fun hook() {
        // 禁止检测通知开启状态
        hookReplaceMethod(
            "androidx.core.app.NotificationManagerCompat",
            "areNotificationsEnabled"
        ) { true }
        hookReplaceMethod(
            "com.baidu.tieba.push.PushSceneGroup",
            "getLimit"
        ) { param ->
            // 按实际返回类型派发零值(safeConstantFor): 假定 int 而宿主改为其他
            // 基本类型或引用类型时, 固定返回装箱 Integer 会在宿主调用点
            // 拆箱/类型检查出错
            safeConstantFor((param.method as java.lang.reflect.Method).returnType, 0, false)
        }
    }
}
