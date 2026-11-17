package gm.tieba.tabswitch.hooker.extra

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.AcRules.findRule
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.hooker.deobfuscation.ResMatcher
import gm.tieba.tabswitch.hooker.deobfuscation.SmaliMatcher
import gm.tieba.tabswitch.util.Logger
import gm.tieba.tabswitch.util.getObjectField
import gm.tieba.tabswitch.util.getR
import org.luckypray.dexkit.query.matchers.ClassMatcher

class ForbidGesture : XposedContext(), IHooker, Obfuscated {

    override fun key(): String {
        return "forbid_gesture"
    }

    override fun matchers(): List<Matcher> {
        // 资源缺失时 getR 返回 0 而非抛错: ResMatcher(0) 会把宿主内所有使用常量 0
        // 的方法写成规则并批量改写返回值, 引发难定位的大面积功能错乱。
        // 版本漂移时宁可本功能失效也不误伤, 跳过该 matcher
        val resId = getR("drawable", "icon_word_t_size")
        val resMatchers = if (resId != 0) {
            listOf<Matcher>(ResMatcher(resId.toLong(), "forbid_gesture"))
        } else {
            Logger.log("ForbidGesture: icon_word_t_size 资源不存在, 跳过字号 matcher")
            emptyList()
        }
        return resMatchers + listOf(
            SmaliMatcher("Ljava/lang/Math;->sqrt(D)D").apply {
                classMatcher = ClassMatcher.create().className("com.baidu.tbadk.widget.DragImageView")
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun hook() {
        // 帖子字号
        // findRule 回调内的注册逐条隔离: 单条规则签名漂移抛 NoSuchMethodError 时
        // 只损失该条, 不中断 findRule 循环与其后的视频帖字号/帖子进吧注册
        findRule(matchers()) { matcher, clazz, method ->
            runCatching {
                when (matcher) {
                    // 按返回类型安全化: replace null 遇基本类型返回值会在宿主调用点拆箱 NPE
                    "forbid_gesture" -> hookReplaceMethod(clazz, method) { param ->
                        safeZeroValue((param.method as java.lang.reflect.Method).returnType)
                    }
                    // 保留原返回值类型(Host 版本漂移时可能是 Double/Int), 避免硬 as Float 的 CCE
                    "Ljava/lang/Math;->sqrt(D)D" -> hookAfterMethod(clazz, method, Bitmap::class.java) { param ->
                        when (val result = param.result) {
                            is Float -> param.result = result * 3f
                            is Double -> param.result = result * 3.0
                            is Int -> param.result = result * 3
                            is Long -> param.result = result * 3L
                        }
                    }
                    // 显式空分支: runCatching 的 lambda 以 when 为返回值时必须穷尽
                    else -> {}
                }
            }.onFailure {
                Logger.log("ForbidGesture: 规则[$matcher] 注册失败 -- $it")
            }
        }

        // 视频帖字号
        hookAfterMethod("com.baidu.tieba.pb.videopb.fragment.DetailInfoAndReplyFragment",
            "onCreateView", LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java) { param ->
            val recyclerView = getObjectField(
                param.thisObject,
                "com.baidu.adp.widget.ListView.BdTypeRecyclerView"
            ) as? ViewGroup
            recyclerView?.setOnTouchListener { _, _ -> false }
        }

        // 帖子进吧
        // 注册期解析并缓存 Method, 回调只在 ACTION_DOWN 时调用一次:
        // 此前每个触摸事件(滚动时含大量 MOVE)都做全类方法表反射, 漂移时逐事件刷日志
        try {
            val forbidMethod = XposedHelpers.findMethodExactIfExists(
                findClass("com.baidu.tieba.pb.pb.main.PbLandscapeListView"),
                "setForbidDragListener", Boolean::class.javaPrimitiveType
            )
            if (forbidMethod != null) {
                // 失败只记一次: 每次触摸(滚动含大量事件)都落盘会刷爆日志
                var forbidFailureLogged = false
                hookBeforeMethod(
                    "com.baidu.tieba.pb.pb.main.PbLandscapeListView",
                    "dispatchTouchEvent", MotionEvent::class.java
                ) { param ->
                    if ((param.args[0] as? MotionEvent)?.actionMasked == MotionEvent.ACTION_DOWN) {
                        runCatching { forbidMethod.invoke(param.thisObject, true) }
                            .onFailure {
                                if (!forbidFailureLogged) {
                                    forbidFailureLogged = true
                                    Logger.log("ForbidGesture setForbidDragListener 失败: $it")
                                }
                            }
                    }
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    // replace 的类型安全零值收编为 XposedContext.safeZeroValueFor(此前与
    // Purge 各持一份逐字相同的私有实现)
    private fun safeZeroValue(type: Class<*>): Any? = safeZeroValueFor(type)
}
