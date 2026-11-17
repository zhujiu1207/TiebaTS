package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XC_MethodHook.XC_MethodReplacement
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.util.Logger
import java.lang.reflect.Modifier

// 云控弹窗根源屏蔽(22.11/22.12 反编译核对, 类名与方法名双版本一致):
//   sync 下发     → YunDialogDataManager.r(JSONArray) 写本地存储
//   通用路由推送  → YunDialogDispatcher.dispatch (服务名 "dialog/yundialog")
//   时机/指定弹窗 → YunDialogManager.onShow/onPreload
//   三条通道全部汇聚到 YunDialogDataManager.f/g/h(String, e回调) 后 show() 弹出。
// 四层封死: 数据出口回调空列表 + sync 写库 no-op + 推送入口 no-op + 展示入口 no-op。
// 全部按稳定类名/形状匹配, 不依赖混淆类名(nd6/gd6 等); 各 hook 点独立 try/catch,
// 单点失效只降级为点状拦截, 不影响其它净化。
class PurgeYunDialog : XposedContext(), IHooker {

    override fun key(): String {
        return "purge_yun_dialog"
    }

    override fun hook() {
        blockDataOut()
        blockSyncWrite()
        blockDispatcher()
        blockShowEntry()
    }

    // ---------- a. 数据出口封死(核心) ----------
    // YunDialogDataManager.f/g/h(String, 内部回调接口e): 所有展示路径的策略唯一来源。
    // 形状匹配: 实例方法, 2参, [0]=String, [1]=接口且为本类内部接口(双版本均为 $e)。
    // 替换为以空列表回调 onResult 后返回, 语义等价"服务端下发空配置":
    // 调用方(YunDialogManager 的时机回调)正常收尾, 任何弹窗策略都取不到,
    // 本地存量配置(sync 已写入的)也永远读不出来。
    private fun blockDataOut() {
        try {
            val managerClass = findClass(YUN_DIALOG_DATA_MANAGER)
            val fetched = managerClass.declaredMethods.filter { md ->
                !Modifier.isStatic(md.modifiers) &&
                    md.parameterTypes.size == 2 &&
                    md.parameterTypes[0] == String::class.java &&
                    md.parameterTypes[1].isInterface &&
                    md.parameterTypes[1].name.startsWith(YUN_DIALOG_DATA_MANAGER)
            }
            // 回调接口通常只有一个抽象方法(onResult), 动态解析方法名防混淆轮换;
            // 解析失败退化为纯 no-op(弹窗照样被掐, 仅宿主时机收尾走不到, 无副作用)
            val callbackMethodName = fetched.firstNotNullOfOrNull { md ->
                md.parameterTypes[1].declaredMethods
                    .filter { Modifier.isAbstract(it.modifiers) }
                    .singleOrNull()?.name
            }
            if (fetched.isEmpty()) {
                logBoth("PurgeYunDialog: 数据出口未命中 (String, e) 形状方法")
                return
            }
            fetched.forEach { md ->
                runCatching {
                    hookReplaceMethod(md) { param ->
                        if (callbackMethodName != null && param.args[1] != null) {
                            runCatching {
                                XposedHelpers.callMethod(param.args[1], callbackMethodName, emptyList<Any>())
                            }
                        }
                        null
                    }
                }
            }
            Logger.log("[云控诊断] 数据出口封死: ${fetched.joinToString(",") { it.name }} -> onResult(空)$callbackMethodName")
        } catch (tr: Throwable) {
            logBoth("PurgeYunDialog: 数据出口封死失败 -- $tr")
        }
    }

    // ---------- b. sync 写库封死 ----------
    // 唯一 void(JSONArray) 实例方法(r): 服务端 sync 下发云弹窗配置的唯一写入口。
    // no-op 后本地存储不再更新; 存量配置由数据出口封死兜住, 永远读不出来。
    private fun blockSyncWrite() {
        try {
            val managerClass = findClass(YUN_DIALOG_DATA_MANAGER)
            val targets = managerClass.declaredMethods.filter { md ->
                !Modifier.isStatic(md.modifiers) &&
                    md.returnType == Void.TYPE &&
                    md.parameterTypes.size == 1 &&
                    md.parameterTypes[0] == org.json.JSONArray::class.java
            }
            if (targets.size != 1) {
                logBoth("PurgeYunDialog: sync 写库未唯一命中 void(JSONArray) -- x${targets.size}")
                return
            }
            hookReplaceMethod(targets[0]) { null }
            Logger.log("[云控诊断] sync 写库封死: ${targets[0].name}")
        } catch (tr: Throwable) {
            logBoth("PurgeYunDialog: sync 写库封死失败 -- $tr")
        }
    }

    // ---------- c. 推送通道封死 ----------
    // YunDialogDispatcher.dispatch(JSONObject, Context): 服务端通用路由实时下发云弹窗
    // 的入口(服务名 "dialog/yundialog"), 类名/方法名双版本稳定。
    private fun blockDispatcher() {
        try {
            hookReplaceMethod(
                YUN_DIALOG_DISPATCHER, "dispatch",
                org.json.JSONObject::class.java, android.content.Context::class.java
            ) { null }
            Logger.log("[云控诊断] 推送通道封死: YunDialogDispatcher.dispatch")
        } catch (tr: Throwable) {
            logBoth("PurgeYunDialog: 推送通道封死失败 -- $tr")
        }
    }

    // ---------- d. 展示入口兜底 ----------
    // YunDialogManager 全部 onShow/onPreload 静态重载 no-op: 覆盖任何绕过
    // DataManager 直达展示层的调用。不碰 canShowDialog/onHidden 等状态方法。
    private fun blockShowEntry() {
        try {
            val managerClass = findClass(YUN_DIALOG_MANAGER)
            for (name in listOf("onShow", "onPreload")) {
                val methods = XposedBridge.hookAllMethods(managerClass, name, XC_NO_OP_REPLACE)
                if (methods.size == 0) {
                    logBoth("PurgeYunDialog: 展示入口 $name 无重载命中")
                } else {
                    Logger.log("[云控诊断] 展示入口封死: $name x${methods.size}")
                }
            }
        } catch (tr: Throwable) {
            logBoth("PurgeYunDialog: 展示入口封死失败 -- $tr")
        }
    }

    companion object {
        private const val YUN_DIALOG_DATA_MANAGER = "com.baidu.tbadk.core.dialog.yun.YunDialogDataManager"
        private const val YUN_DIALOG_MANAGER = "com.baidu.tbadk.core.dialog.yun.YunDialogManager"
        private const val YUN_DIALOG_DISPATCHER = "com.baidu.tbadk.core.dialog.yun.YunDialogDispatcher"

        // 展示入口专用: 已知重载全为 void; 按命中方法实际返回类型派发零值,
        // 未来出现 boolean/int 返回的重载时返回 false/0, 避免宿主拆箱 NPE
        private val XC_NO_OP_REPLACE = object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? =
                safeConstantFor((param.method as java.lang.reflect.Method).returnType, 0, false)
        }
    }
}
