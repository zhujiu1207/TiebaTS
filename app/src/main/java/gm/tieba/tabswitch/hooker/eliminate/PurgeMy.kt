package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.logBoth
import org.json.JSONArray

class PurgeMy : XposedContext(), IHooker, Obfuscated {

    companion object {
        // 新版"我的"页 AB 实验的 sid 前缀, 由服务端下发
        const val MY_TAB_NEW_SID_PREFIX = "12_64_my_tab_new"
    }

    override fun key(): String {
        return "purge_my"
    }

    override fun matchers(): List<Matcher> {
        return listOf<Matcher>()
    }

    // 协议字段/区块净化兜底：字段在新版本被移除或结构变化时跳过该项并记录日志，
    // 而不是让 build() 抛错导致"我的"页整页空白
    private fun purgeField(target: Any, field: String, value: Any?) {
        runCatching { XposedHelpers.setObjectField(target, field, value) }
            .onFailure {
                XposedBridge.log("PurgeMy: 协议字段 $field 不存在，跳过净化 -- ${it.javaClass.simpleName}")
            }
    }

    private fun filterMyTabNewSid(json: JSONArray): JSONArray {
        val filtered = JSONArray()
        for (i in 0 until json.length()) {
            val currTest = runCatching { json.getJSONObject(i) }.getOrNull() ?: continue
            if (!currTest.optString("sid").startsWith(MY_TAB_NEW_SID_PREFIX)) {
                filtered.put(currTest)
            }
        }
        return filtered
    }

    private fun purgeProfileDataRes(param: XC_MethodHook.MethodHookParam) {
        // 度小满钱包（有钱花）
        purgeField(param.thisObject, "finance_tab", null)

        // 页面区块（zone_info）：移除 度小满钱包 / 游戏专区 / 免费送SVIP 活动横幅 等推广区块，
        // 只保留 常用功能(common_func) 与 辅助功能(auxiliary_func)。
        // 复制式过滤(入参可能不可变); type 读取失败/未知时保留区块(fail-open),
        // 避免字段漂移时"我的"页全部功能区块被误清空
        runCatching {
            val zoneInfo = XposedHelpers.getObjectField(param.thisObject, "zone_info") as? List<*> ?: return
            val filtered = zoneInfo.filter { zone ->
                zone == null || runCatching {
                    val type = XposedHelpers.getObjectField(zone, "type") as? String
                    type == null || type == "common_func" || type == "auxiliary_func"
                }.getOrDefault(true)
            }
            if (filtered.size != zoneInfo.size) {
                XposedHelpers.setObjectField(param.thisObject, "zone_info", filtered)
            }
        }.onFailure {
            logBoth("PurgeMy: zone_info 过滤失败 -- ${it.javaClass.simpleName}")
        }
    }

    override fun hook() {
        // 12.74 起服务端通过两条相互独立的通道把"我的"页切到新版 H5 容器页
        // (PersonTabWebFragment, 数据经 WebPersonPageModel 单独请求后以 myTab.profileData
        // 推给 WebView, 全程不经过 Profile.DataRes$Builder, 净化钩子全部失效):
        //
        // 1) Ubs AB 实验 12_64_my_tab_new: 客户端会把它连同其他实验缓存进 SP("ubs_abtest_config"),
        //    UbsABTestDataManager 构造器直接从 SP 载入(getAllDataFormSharedPref), 不经过
        //    parseJSONArray; switchCurrentABTestBySid 等写入路径同样绕过解析钩子。
        //    故在解析入口(parseJSONArray)与查询点(getABTestSwitchData)双重拦截。
        // 2) 云控开关 22.6_mytab_to_h5_switch(PersonTabH5Switch, 默认开): 主页 tab 构建时
        //    (PersonInfoDelegateStatic.createFragmentTabStructure)依据它选择 H5 页或旧版原生页,
        //    服务端可随时下发, 常见触发时机为退后台约一分钟后回前台刷新云控配置——表现为
        //    "退后台再回来净化失效, 重新进入我的才恢复"。在开关读取点强制返回关, 保证走旧版
        //    原生页面 PersonCenterFragment, 净化行为确定。
        // 解析入口的注册点同样隔离: 类/签名缺失(版本漂移)时只损失该拦截点,
        // 不让后面的 PersonTabH5Switch/Profile 拦截一并失效
        runCatching {
            hookBeforeMethod(
                "com.baidu.tbadk.abtest.UbsABTestDataManager",
                "parseJSONArray",
                JSONArray::class.java
            ) { param ->
                val currentABTestJson = param.args[0] as? JSONArray ?: return@hookBeforeMethod
                param.args[0] = filterMyTabNewSid(currentABTestJson)
            }
        }.onFailure {
            logBoth("PurgeMy: hook parseJSONArray 失败 -- ${it.javaClass.simpleName}")
        }

        var abNeutralizedLogs = 0
        runCatching {
            hookAfterMethod(
                "com.baidu.tbadk.abtest.UbsABTestDataManager",
                "getABTestSwitchData", String::class.java
            ) { param ->
                val sid = param.args[0] as? String ?: return@hookAfterMethod
                if (sid.startsWith(MY_TAB_NEW_SID_PREFIX) && param.result != null) {
                    // 返回类型校验: 基本类型返回值的装箱结果恒非 null, 直接置 null
                    // 会在宿主调用点拆箱 NPE; 该情形按类型派发安全零值(布尔为 false,
                    // 与"开关关闭"的拦截语义一致)
                    val returnType = (param.method as java.lang.reflect.Method).returnType
                    param.result =
                        if (returnType.isPrimitive) safeConstantFor(returnType, 0, false) else null
                    if (abNeutralizedLogs++ < 3) {
                        logBoth("PurgeMy: 拦截新版我的页 AB 实验查询 $sid")
                    }
                }
            }
        }.onFailure {
            logBoth("PurgeMy: hook getABTestSwitchData 失败 -- ${it.javaClass.simpleName}")
        }

        runCatching {
            hookAfterMethod(
                "com.baidu.tieba.personTab.PersonTabH5Switch", "isOpen"
            ) { param ->
                param.result = false
            }
        }.onFailure {
            logBoth("PurgeMy: hook PersonTabH5Switch.isOpen 失败 -- ${it.javaClass.simpleName}")
        }

        // 开关内部读取点(Kotlin companion 上的 isOnType), 兜底覆盖直接调用 companion 的路径。
        // 只钩无参且返回 boolean 的 "a": 混淆名 "a" 可能被其他签名/返回类型的方法占用,
        // 对非布尔方法强写 false 会在宿主调用点触发拆箱 ClassCastException
        runCatching {
            val switchInnerClass = XposedHelpers.findClass(
                "com.baidu.tieba.personTab.PersonTabH5Switch\$a", sClassLoader
            )
            switchInnerClass.declaredMethods.firstOrNull {
                it.name == "a" && it.parameterTypes.isEmpty() &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }?.let { method ->
                hookAfterMethod(method) { param ->
                    param.result = false
                }
            }
        }.onFailure {
            logBoth("PurgeMy: hook PersonTabH5Switch\$a.a 失败 -- ${it.javaClass.simpleName}")
        }

        // "我的"页 Profile 数据净化。findClassIfExists 已判类缺失, 但方法签名
        // (build(Boolean)) 漂移仍会抛 NoSuchMethodError, 隔离以防拖垮整个 hooker
        runCatching {
            val profileBuilderClass = XposedHelpers.findClassIfExists(
                "tbclient.Profile.DataRes\$Builder", sClassLoader
            )
            profileBuilderClass?.let {
                hookBeforeMethod(it, "build", Boolean::class.javaPrimitiveType) { param ->
                    purgeProfileDataRes(param)
                }
            }
        }.onFailure {
            logBoth("PurgeMy: hook Profile.DataRes\$Builder.build 失败 -- ${it.javaClass.simpleName}")
        }
    }
}
