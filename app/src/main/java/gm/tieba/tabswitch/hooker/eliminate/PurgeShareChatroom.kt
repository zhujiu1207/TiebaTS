package gm.tieba.tabswitch.hooker.eliminate

import android.view.View
import android.view.ViewGroup
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.logBoth
import java.lang.reflect.Modifier

class PurgeShareChatroom : XposedContext(), IHooker {

    override fun key(): String {
        return "purge_share_chatroom"
    }

    override fun hook() {
        // 注册点隔离: 类名漂移时只损失本功能并留痕, 与其余 hooker 的逐点隔离风格一致
        runCatching {
            // 帖子底部 "xx吧友在讨论" 聊天室入口栏
            val barClass = findClass("com.baidu.tieba.pb.pb.main.underlayer.PbBottomEnterBarView")

            for (constructor in barClass.declaredConstructors) {
                XposedBridge.hookMethod(constructor, object : gm.tieba.tabswitch.bridge.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? View)?.let { squash(it) }
                    }
                })
            }

            // 数据到达与换肤时会重新设置内容, 需再次隐藏
            val contentMethods = barClass.declaredMethods.filter { method ->
                !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE && (
                    (method.name == "setData" && method.parameterTypes.size == 1) ||
                        (method.name == "onChangeSkinType" && method.parameterTypes.isEmpty())
                    )
            }
            // 零命中留痕: 构造钩子仍在(条目会被压扁), 但"数据到达/换肤重设"的
            // 再隐藏链路丢失——混淆名漂移时表现为"偶发露条", 无日志无从排查
            if (contentMethods.isEmpty()) {
                logBoth("PurgeShareChatroom: setData/onChangeSkinType 零命中, 再隐藏链路缺失")
            }
            contentMethods.forEach { method ->
                hookAfterMethod(method) { param ->
                    (param.thisObject as? View)?.let { squash(it) }
                }
            }
        }.onFailure {
            logBoth("PurgeShareChatroom: 注册失败 -- $it")
        }
    }

    private fun squash(view: View) {
        try {
            view.minimumHeight = 0
            view.minimumWidth = 0
            view.setPadding(0, 0, 0, 0)
            val layoutParams = view.layoutParams
            if (layoutParams != null) {
                layoutParams.width = 0
                layoutParams.height = 0
                if (layoutParams is ViewGroup.MarginLayoutParams) layoutParams.setMargins(0, 0, 0, 0)
                view.layoutParams = layoutParams
            }
            view.visibility = View.GONE
            // 布局阶段可能被宿主重新展开, 延迟再压一次
            view.post { reSquash(view) }
        } catch (ignored: Throwable) {
        }
    }

    private fun reSquash(view: View) {
        if (view.visibility != View.GONE || view.minimumHeight != 0 || view.minimumWidth != 0) {
            squash(view)
        }
    }
}
