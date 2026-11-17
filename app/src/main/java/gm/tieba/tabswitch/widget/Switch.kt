package gm.tieba.tabswitch.widget

import android.os.Vibrator
import android.view.View
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.bridge.XposedHelpers.ClassNotFoundError
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.util.callMethod
import gm.tieba.tabswitch.util.getObjectField
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class Switch : XposedContext() {

    var bdSwitch: View
    private var mMethods: Array<Method>

    init {
        val cls = findClass("com.baidu.adp.widget.BdSwitchView.BdSwitchView")
        bdSwitch = XposedHelpers.newInstance(cls, getContext()) as View
        mMethods = cls.declaredMethods
    }

    fun setOnSwitchStateChangeListener(l: InvocationHandler) {
        val clazz: Class<*> = try {
            findClass("com.baidu.adp.widget.BdSwitchView.BdSwitchView\$b")
        } catch (e: ClassNotFoundError) {
            findClass("com.baidu.adp.widget.BdSwitchView.BdSwitchView\$a")
        }
        val proxy = Proxy.newProxyInstance(sClassLoader, arrayOf(clazz), l)
        XposedHelpers.callMethod(bdSwitch, "setOnSwitchStateChangeListener", proxy)
    }

    fun isOn(): Boolean = try {
        XposedHelpers.callMethod(bdSwitch, "isOn") as Boolean
    } catch (e: NoSuchMethodError) {
        // 12.x: index 6 为 ()Z 方法；22.11 起按字段 a 与 SwitchState.ON 比较。
        // 回退链整体兜底: findClass 可抛 ClassNotFoundError、字段读取可抛
        // NoSuchFieldError, 该路径被保存按钮等无外层兜底的点击回调直接调用,
        // 击穿即崩宿主主线程; 全链失效时返回 false 只影响显示状态
        try {
            val legacy = legacyMethod(6, Boolean::class.javaPrimitiveType ?: Void.TYPE)
            if (legacy != null) {
                callMethod(legacy, bdSwitch) as Boolean
            } else {
                val stateCls = findClass("com.baidu.adp.widget.BdSwitchView.BdSwitchView\$SwitchState")
                getObjectField(bdSwitch, "com.baidu.adp.widget.BdSwitchView.BdSwitchView\$SwitchState") ===
                    XposedHelpers.getStaticObjectField(stateCls, "ON")
            }
        } catch (ignored: Throwable) {
            false
        }
    }

    fun changeState() {
        try {
            XposedHelpers.callMethod(bdSwitch, "changeState")
        } catch (e: NoSuchMethodError) {
            // 名称回退优先于下标回退: declaredMethods 顺序无规范保证, 固定下标
            // 在方法表乱序时可能命中错误方法; 22.11 起混淆名 j/g/c 跨版本更稳。
            // 回退链整体兜底: 与 isOn 同理, 调用方(设置页点击回调)无外层兜底,
            // 全链失效时抛异常会击穿宿主主线程, 只记日志安全返回
            val target = namedVoidMethod("c") ?: legacyMethod(3, Void.TYPE)
            if (target != null) callMethod(target, bdSwitch) else logBoth("Switch.changeState 回退链全部失效 -- $e")
        }
    }

    fun turnOn() {
        try {
            XposedHelpers.callMethod(bdSwitch, "turnOn")
        } catch (e: NoSuchMethodError) {
            val target = namedVoidMethod("j") ?: legacyMethod(11, Void.TYPE)
            if (target != null) callMethod(target, bdSwitch) else logBoth("Switch.turnOn 回退链全部失效 -- $e")
        }
    }

    fun turnOff() {
        try {
            XposedHelpers.callMethod(bdSwitch, "turnOff")
        } catch (e: NoSuchMethodError) {
            val target = namedVoidMethod("g") ?: legacyMethod(8, Void.TYPE)
            if (target != null) callMethod(target, bdSwitch) else logBoth("Switch.turnOff 回退链全部失效 -- $e")
        }
    }

    fun getVibrator(): Vibrator? = getObjectField(bdSwitch, Vibrator::class.java)

    // 12.x 系列版本按固定下标回退；调用前校验签名，避免 22.11 上按旧下标调到带参方法
    private fun legacyMethod(index: Int, expectedReturnType: Class<*>): Method? {
        val m = mMethods.getOrNull(index) ?: return null
        return if (m.parameterTypes.isEmpty() && m.returnType == expectedReturnType) m else null
    }

    // 22.11: 无参混淆方法 j()/g()/c() 分别为 turnOn/turnOff/changeState
    private fun namedVoidMethod(name: String): Method? {
        return mMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() && it.returnType == Void.TYPE }
    }
}

