package gm.tieba.tabswitch.hooker.eliminate

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XC_MethodHook.XC_MethodReplacement
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.AcRules.findRule
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.MAIN_TAB_ACTIVITY
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.hooker.deobfuscation.SmaliMatcher
import gm.tieba.tabswitch.hooker.deobfuscation.StringMatcher
import gm.tieba.tabswitch.util.findFirstMethodByExactReturnType
import gm.tieba.tabswitch.util.getAssetFileContent
import gm.tieba.tabswitch.util.getObjectField
import gm.tieba.tabswitch.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier

class Purge : XposedContext(), IHooker, Obfuscated {

    companion object {
        // findTask 热路径使用的任务 ID 常量集合(启动广告/热启动闪屏)
        private val PURGE_TASK_IDS = setOf(2016555, 2921390)

        // 吧页面头条贴(41)/直播贴(69)的 thread_type 值, 提为常量避免逐 KV 判定循环内分配
        private val PURGE_FEED_TYPE_IDS = setOf("41", "69")

        // 首页 index_tab_info 需移除的 tab 类型: 活动(202)/直播(6),
        // 提为常量避免宿主 invoke 回调内每次分配
        private val REMOVE_TAB_TYPES = listOf("202", "6")

        // sideway_card 取值方法按类缓存(首页 setList 高频路径, 避免逐元素全方法表扫描);
        // 解析失败按 null 缓存。不能用 ConcurrentHashMap: 不允许 null value,
        // getOrPut 在 put(null) 处抛 NPE 且负缓存永远写不进去; HashMap 允许 null
        private val sidewayMethodCache = HashMap<Class<*>, java.lang.reflect.Method?>()

        // 首页彩蛋广告类名未混淆稳定, 方法名随版本混淆轮换
        private const val EASTER_EGG_DATA_HOLDER = "com.baidu.tieba.easteregg.data.EasterEggAdDataHolder"
        private const val EASTER_EGG_AD_CONTROLLER = "com.baidu.tieba.easteregg.EasterEggAdController"
        private const val EASTER_EGG_DATA = "com.baidu.tieba.easteregg.data.EasterEggAdData"

        // 个性化关闭横幅 show 方法候选(单 boolean 参数 void 实例方法):
        // 22.12.1.0/22.13.1.1 实测均为 i(注意与同类 f(boolean, 消息派发) 区分,
        // 按名精确匹配不会误伤)
        private val PERSONALIZE_BAR_SHOW_METHODS = arrayOf("i")
    }

    // 首页插屏广告控制器类去重(hook() 单线程调用一次, 无需并发容器)
    private val insertAdHookedClasses = HashSet<String>()

    // containsKey 判存而非 getOrPut: null 负缓存下 getOrPut 每次都会重算并覆盖
    private fun sidewayMethod(clazz: Class<*>): java.lang.reflect.Method? =
        synchronized(sidewayMethodCache) {
            if (sidewayMethodCache.containsKey(clazz)) {
                sidewayMethodCache[clazz]
            } else {
                runCatching { findFirstMethodByExactReturnType(clazz, String::class.java) }.getOrNull()
                    .also { sidewayMethodCache[clazz] = it }
            }
        }

    // 中段注册点逐项隔离: 任一注册点抛异常(类名/签名随宿主版本漂移)只损失该功能
    // 点; hooker 级 catch 只兜崩溃不补注册, 不隔离时一个点漂移会让 hook() 其后的
    // 全部净化钩子静默不注册。失败双写日志便于反馈排查
    private fun hookSafely(tag: String, hook: () -> Unit) {
        gm.tieba.tabswitch.hookSafely("Purge", tag, hook)
    }

    override fun key(): String {
        return "purge"
    }

    override fun matchers(): List<Matcher> {
        return listOf(
            SmaliMatcher("Lcom/baidu/tieba/recapp/lego/model/AdCard;-><init>(Lorg/json/JSONObject;)V"),
            SmaliMatcher("Lcom/baidu/tieba/lego/card/model/BaseCardInfo;-><init>(Lorg/json/JSONObject;)V").apply {
                // 按构造器签名找全部调用方: 实测 22.12.1.0 上约 14 个卡片子类经
                // super(json) 构造, 属合法多发; 全局默认上限 10 会把整条特征当
                // 歧义丢弃, 这里显式放宽(留漂移余量, 仍拦截失控命中)
                maxRules = 32
            },
            StringMatcher("pic_amount"),
            StringMatcher("准备展示精灵动画提示控件"),
            StringMatcher("bottom_bubble_config"),
            StringMatcher("index_tab_info"),
            StringMatcher("h5_pop_ups_config"),
            StringMatcher("key_home_insert_ad_show_time"),
            StringMatcher("1638177515997")
        )
    }

    override fun hook() {
        findRule(matchers()) { matcher, clazz, method ->
            // 逐规则隔离: 单条规则类漂移(findClass 抛 ClassNotFoundError)只跳过该条,
            // 不再让后续全部净化分支因 hooker 级 FAILED 整体失效
            runCatching {
                when (matcher) {
                // 卡片广告: 规则按构造器签名命中(method=="<init>"), 而 hookAllMethods
                // 遍历 getDeclaredMethods() 不含构造器——原写法是静默 no-op, 卡片广告
                // 拦截从未生效。改遍历 declaredConstructors 按参数含 JSONObject 匹配,
                // 返回类型守卫照搬 pic_amount 分支(构造器返回 void, replace null 安全)
                "Lcom/baidu/tieba/recapp/lego/model/AdCard;-><init>(Lorg/json/JSONObject;)V", "Lcom/baidu/tieba/lego/card/model/BaseCardInfo;-><init>(Lorg/json/JSONObject;)V" ->
                    findClass(clazz).declaredConstructors.filter { ctor ->
                        org.json.JSONObject::class.java in ctor.parameterTypes
                    }.forEach { ctor ->
                        XposedBridge.hookMethod(
                            ctor,
                            object : XC_MethodReplacement() {
                                override fun replaceHookedMethod(param: MethodHookParam): Any? = null
                            }
                        )
                    }

                // 图片广告：必须"recom_ala_info", "app", 可选"goods_info"
                "pic_amount" ->
                    findClass(clazz).declaredMethods.filter { md ->
                        // 精确匹配参数类型: contentToString 子串匹配会误中任何
                        // 类名含 "JSONObject" 的自定义类型参数的方法
                        JSONObject::class.java in md.parameterTypes && md.name != method
                    }.forEach { md ->
                        // 按返回类型安全化: 目标类中同参方法群若混有基本类型返回值,
                        // replace null 会在宿主调用点拆箱 NPE(hook 桥兜不住)
                        hookReplaceMethod(md) { param ->
                            safeZeroValueFor((param.method as java.lang.reflect.Method).returnType)
                        }
                    }

                // 吧内%s新贴热议中
                "准备展示精灵动画提示控件" ->
                    XposedBridge.hookAllMethods(
                        findClass(clazz),
                        method,
                        XC_MethodReplacement.returnConstant(false)
                    )

                // 底部导航栏活动图标
                "bottom_bubble_config" ->
                    if (method == "invoke") {
                        hookBeforeMethod(clazz, method) { param ->
                            getObjectField(param.thisObject, JSONObject::class.java)?.apply {
                                put("bottom_bubble_config", null)
                            }
                        }
                    }

                // 首页活动Tab (202), 直播Tab (6)
                "index_tab_info" ->
                    if (method == "invoke") {
                        hookBeforeMethod(clazz, method) { param ->
                            getObjectField(param.thisObject, JSONObject::class.java)?.apply {
                                // optJSONArray: payload 缺键时跳过本次过滤, 不抛异常中断
                                val indexTabInfo = optJSONArray("index_tab_info") ?: return@apply
                                // optJSONObject: 数组元素非对象时跳过该元素, 不抛异常中断整个净化
                                // optString: 任一 tab 缺键时不抛异常中断整个净化
                                val mainTabOnly = DeobfuscationHelper.isTbSatisfyVersionRequirement("12.59")
                                fun removable(tab: JSONObject): Boolean =
                                    if (mainTabOnly) {
                                        tab.optString("is_main_tab") == "1" &&
                                            tab.optString("tab_type") in REMOVE_TAB_TYPES
                                    } else {
                                        tab.optString("tab_type") in REMOVE_TAB_TYPES
                                    }
                                // 先判命中: 绝大多数响应无待删 Tab, 无命中直接返回,
                                // 避免每次都重建等价数组并回写
                                var hit = false
                                for (i in 0 until indexTabInfo.length()) {
                                    val currTab = indexTabInfo.optJSONObject(i) ?: continue
                                    if (removable(currTab)) {
                                        hit = true
                                        break
                                    }
                                }
                                if (!hit) return@apply
                                val newIndexTabInfo = JSONArray()
                                for (i in 0 until indexTabInfo.length()) {
                                    val currTab = indexTabInfo.optJSONObject(i) ?: continue
                                    if (!removable(currTab)) {
                                        newIndexTabInfo.put(currTab)
                                    }
                                }
                                // 保护: 结果为空说明 is_main_tab/tab_type 取值格式已变化(如布尔化),
                                // 此时保留原数组, 避免首页 Tab 栏被整栏清空
                                if (newIndexTabInfo.length() > 0) {
                                    put("index_tab_info", newIndexTabInfo)
                                }
                            }
                        }
                    }

                // 各种云控弹窗
                "h5_pop_ups_config" -> if (method == "invoke") {
                    hookBeforeMethod(clazz, method) { param ->
                        getObjectField(param.thisObject, JSONObject::class.java)?.apply {
                            put("h5_pop_ups", null)
                            put("h5_pop_ups_config", null)
                        }
                    }
                }
                // 首页插屏广告(推荐流上方全屏透明弹层大图广告): 含展示频控 SP key 的类
                // 即插屏广告控制器(22.11 为 d8c, 方法名随版本混淆轮换)。类内所有实例
                // boolean 方法语义均为"是否展示/是否可展示"(B 请求入口、k/H 展示 gate、
                // s/t/u/v/x 判定), 全部替换为 false 即全链路阻断, 广告请求不再发出。
                // 同一字符串被该类两个方法(频控读写)引用, 按 clazz 去重;
                // static boolean w() 是剪贴板检测(true 抑制展示), 必须排除
                "key_home_insert_ad_show_time" -> {
                    if (insertAdHookedClasses.add(clazz)) {
                        val hooked = findClass(clazz).declaredMethods.filter { md ->
                            !Modifier.isStatic(md.modifiers) &&
                                md.returnType == Boolean::class.javaPrimitiveType
                        }
                        hooked.forEach { md ->
                            runCatching { hookReplaceMethod(md) { false } }
                        }
                        // 排查日志: 记录锚定类与实际替换的方法名, 插屏漏出时可对照
                        // 宿主版本确认判定方法是否被覆盖(仅日志开关开启时落盘)
                        Logger.log("[插屏诊断] anchor=$clazz hooked=${hooked.joinToString(",") { it.name }}")
                    }
                }

                // 首页插屏广告第二层: 广告位请求拦截。22.11 实测仅替换控制器实例
                // boolean 判定仍有插屏漏出(展示链路存在 boolean 形状之外的路径),
                // 故锚定广告请求任务体内的槽位 id 字符串, 将整个请求方法 no-op:
                // 广告请求不再发出, 控制器 d==null 后所有展示入口自然走不展示分支;
                // 22.12 中该字符串唯一命中内层 AsyncTask 的 void doInBackground, 替换安全
                "1638177515997" -> {
                    // 22.12 的 doInBackground 为无参 void, 按名精确替换即可;
                    // 22.11 该方法带 AsyncTask 泛型参数(按名#exact 匹配抛
                    // NoSuchMethodError, 请求层兜底整体失效导致插屏漏出)。
                    // 改为枚举内层任务类全部 doInBackground 重载(含桥接)逐个 no-op:
                    // 该类由槽位 id 字符串唯一锚定, 全替换无误伤面
                    val targets = findClass(clazz).declaredMethods.filter { it.name == method }
                    if (targets.isEmpty()) {
                        logBoth("Purge: 插屏广告请求拦截失败 -- $clazz#$method 无可匹配方法")
                    } else {
                        targets.forEach { md ->
                            runCatching { hookReplaceMethod(md) { null } }
                                .onFailure { logBoth("Purge: 插屏广告请求拦截失败 -- ${md.name}${md.parameterTypes.contentToString()} -- $it") }
                        }
                        Logger.log("[插屏诊断] 请求层 no-op: $clazz#$method x${targets.size}" +
                            targets.joinToString(",", "(", ")") { it.parameterTypes.contentToString() })
                    }
                }
                else -> {}
                }
                // 显式 Unit: 使 when/if 保持语句语义, 不要求穷尽分支
                Unit
            }.onFailure { logBoth("Purge: 规则 [$matcher] 跳过 -- $it") }
        }

        // 启动广告
        hookSafely("启动广告") {
            hookBeforeMethod(
                "com.baidu.adp.framework.MessageManager",
                "findTask", Int::class.javaPrimitiveType
            ) { param ->
                val task = param.args[0] as? Int
                // 全 app 消息路由高频点: 用常量集合, 不在每次调用分配
                if (task != null && task in PURGE_TASK_IDS) {
                    param.setResult(null)
                }
            }
        }

        // 22.11: 不再替换 SpriteMemePan 构造为 null (会使 PbNewInputContainerV2 的
        // 表情面板视图为空, 点帖进入评论输入框时在 l0()/setEditorTools() 空指针崩溃)。
        // 禁用精灵表情面板改为让 SpriteMemePanController.t() 恒为 false:
        // 回复输入框走无表情面板分支, 效果一致且无空引用
        hookSafely("精灵表情面板") {
            XposedBridge.hookAllMethods(
                findClass("com.baidu.tbadk.editortools.meme.pan.SpriteMemePanController"),
                "t",
                XC_MethodReplacement.returnConstant(false)
            )
        }

        // 热启动闪屏
        hookSafely("热启动闪屏-TbSingleton") {
            hookReplaceMethod("com.baidu.tbadk.TbSingleton", "isPushLaunch4SplashAd") { true }
        }
        hookSafely("热启动闪屏-UbsABTest") {
            hookReplaceMethod("com.baidu.tbadk.abtest.UbsABTestHelper", "isPushLaunchWithoutSplashAdA") { true }
        }

        // Fix bugs related to isPushLaunch4SplashAd
        hookSafely("MainTab闪屏标记") {
            hookAfterMethodPriority(
                MAIN_TAB_ACTIVITY,
                "onCreate", Bundle::class.java
            ) { _ ->
                XposedHelpers.setStaticBooleanField(
                    findClass("com.baidu.tbadk.core.atomData.MainTabActivityConfig"),
                    "IS_MAIN_TAB_SPLASH_SHOW",
                    false
                )
            }
        }

        // 帖子底部推荐
        hookSafely("帖子底部推荐") {
            @Suppress("DEPRECATION")
            hookBeforeMethod("com.baidu.tieba.pb.pb.main.AbsPbActivity", "onCreate", Bundle::class.java) { param ->
                val activity = param.thisObject as Activity
                val bundle = activity.intent.extras
                // 复制原 Intent 保留 component/action/data/type/flags/categories:
                // 全新 Intent 会丢深链/外部分享进入帖子页的路由信息, 宿主读 intent.data 得 null
                val intent = Intent(activity.intent)
                bundle?.let {
                    for (key in bundle.keySet()) {
                        when (key) {
                            "key_start_from" -> {   // 为您推荐
                                val startFrom = bundle[key] as? Int
                                // 22.13 反编译(c0d.f/ThreadCardUtils.jumpToPB): 2=为您推荐
                                // (personalize_page), 3=吧页面(frs)——只改 2, 改 3 会把吧内
                                // 进入的 pageFrom/sourceType 与 obj_param1 上报一并污染
                                if (startFrom == 2) intent.putExtra(key, 0)
                            }
                            "key_uri" -> {          // 浏览器打开热门推荐
                                val uri = bundle[key] as? Uri
                                uri?.getQueryParameter("tid")?.let { intent.putExtra("thread_id", it) }
                            }
                            // 其余 extras 不必重放: Intent(Intent) 拷贝构造已整体复制原 extras
                            // (含 Parcelable[] 等 Intent.putExtra 覆盖不到的类型), 在这里逐个
                            // 重放反而会把无法归类的值静默丢弃
                        }
                    }
                    activity.intent = intent
                }
            }
        }

        // 帖子直播推荐：在 com/baidu/tieba/pb/pb/main/ 中搜索 tbclient/AlaLiveInfo
        hookSafely("帖子直播推荐") {
            hookBeforeMethod(
                "tbclient.AlaLiveInfo\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param ->
                XposedHelpers.setObjectField(param.thisObject, "user_info", null)
            }
        }

        // 首页直播推荐卡片：R.layout.card_home_page_ala_live_item_new
        // 注意不能 replace 为 null: 宿主 lf.getView() 拿到 null holder 后在
        // v.getView() 处必然 NPE(休眠崩溃, 一旦直播卡走到创建路径即崩宿主);
        // 改为放行创建后隐藏 itemView
        hookSafely("首页直播推荐卡片") {
            findClass("com.baidu.tieba.homepage.personalize.adapter.HomePageAlaLiveThreadAdapter").declaredMethods.filter { method ->
                method.returnType.toString().endsWith("HomePageAlaLiveThreadViewHolder")
            }.forEach { method ->
                hookAfterMethod(method) { param ->
                    val holder = param.result ?: return@hookAfterMethod
                    runCatching {
                        (XposedHelpers.callMethod(holder, "getView") as? android.view.View)
                            ?.visibility = android.view.View.GONE
                    }
                }
            }
        }

        // 首页推荐
        hookSafely("首页推荐") {
            hookBeforeMethod(
                "tbclient.Personalized.DataRes\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param ->
                // 复制式过滤: 不原地修改调用方列表(可能不可变), 元素为 null/异常时保留, 避免异常击穿宿主;
                // 无命中不写回, 避免每条推荐响应都分配一张与原表等大的副本
                val threadList = XposedHelpers.getObjectField(param.thisObject, "thread_list") as? List<*>
                if (threadList != null) {
                    val filtered = threadList.filter { thread ->
                        thread == null || runCatching {
                            XposedHelpers.getObjectField(thread, "forum_info") != null &&
                                XposedHelpers.getObjectField(thread, "ala_info") == null && run {
                                    val worksInfo = XposedHelpers.getObjectField(thread, "works_info")
                                    !(worksInfo != null && XposedHelpers.getObjectField(worksInfo, "is_works") as? Int == 1)
                                }
                        }.getOrDefault(true)
                    }
                    if (filtered.size != threadList.size) {
                        XposedHelpers.setObjectField(param.thisObject, "thread_list", filtered)
                    }
                }

                // 推荐置顶广告
                XposedHelpers.setObjectField(param.thisObject, "live_answer", null)

                // 圈层热贴
                XposedHelpers.setObjectField(param.thisObject, "hot_card", null)

                // 添加兴趣，为你精准推荐相关内容~
                XposedHelpers.setObjectField(param.thisObject, "interest_class", null)

                // 你可能感兴趣的吧
                XposedHelpers.setObjectField(param.thisObject, "hot_recomforum_top", null)
            }
        }

        // 帖子 AI 聊天
        hookSafely("帖子AI聊天") {
            hookBeforeMethod(
                "tbclient.PbPage.DataRes\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param ->
                // 复制式过滤: 不原地 removeIf(入参可能不可变); 无命中不写回
                val postList = XposedHelpers.getObjectField(param.thisObject, "post_list") as? List<*>
                if (postList != null) {
                    val filtered = postList.filter { post ->
                        post == null || runCatching {
                            XposedHelpers.getObjectField(post, "aichat_bot_comment_card") == null
                        }.getOrDefault(true)
                    }
                    if (filtered.size != postList.size) {
                        XposedHelpers.setObjectField(param.thisObject, "post_list", filtered)
                    }
                }

                // AI聊天卡片
                XposedHelpers.setObjectField(param.thisObject, "aichat_bot_card", null)
            }
        }

        // 吧页面
        hookSafely("吧页面-FrsPage") {
            hookBeforeMethod(
                "tbclient.FrsPage.DataRes\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param ->
                // 吧公告
                XposedHelpers.setObjectField(param.thisObject, "star_enter", ArrayList<Any>())

                // 万人直播互动 吧友开黑组队中
                XposedHelpers.setObjectField(param.thisObject, "live_fuse_forum", ArrayList<Any>())

                // AI 聊天
                XposedHelpers.setObjectField(param.thisObject, "ai_chatroom_guide", null)

                // 聊天室
                XposedHelpers.setObjectField(param.thisObject, "frs_bottom", null)

                // 吧友直播(复制式过滤: 入参不可变列表时原地 removeIf 抛异常,
                // 会中断本回调内后续 business_promot/activityhead 清理);
                // 无命中不写回, 避免每条响应都分配一张与原表等大的副本(与其余
                // 过滤分支的 size 守卫口径一致)
                val frsMainTabList = XposedHelpers.getObjectField(param.thisObject, "frs_main_tab_list") as? List<*>
                if (frsMainTabList != null) {
                    val filteredTabs = frsMainTabList.filter { tab ->
                        tab == null || runCatching {
                            XposedHelpers.getObjectField(tab, "tab_type") as? Int != 92
                        }.getOrDefault(true)
                    }
                    if (filteredTabs.size != frsMainTabList.size) {
                        XposedHelpers.setObjectField(param.thisObject, "frs_main_tab_list", filteredTabs)
                    }
                }

                // 弹出广告
                XposedHelpers.setObjectField(param.thisObject, "business_promot", null)

                // 顶部背景
                XposedHelpers.setObjectField(param.thisObject, "activityhead", null)
            }
        }

        // 吧友直播
        hookSafely("吧友直播Tab") {
            hookBeforeMethod("tbclient.FrsPage.NavTabInfo\$Builder",
                "build", Boolean::class.javaPrimitiveType) { param ->
                val tabList = XposedHelpers.getObjectField(param.thisObject, "tab") as? List<*>
                    ?: return@hookBeforeMethod
                XposedHelpers.setObjectField(param.thisObject, "tab", tabList.filter { tab ->
                    tab == null || runCatching {
                        XposedHelpers.getObjectField(tab, "tab_type") as? Int != 92
                    }.getOrDefault(true)
                })
            }
        }

        // 你可能感兴趣的人
        hookSafely("你可能感兴趣的人") {
            hookBeforeMethod(
                "com.baidu.tieba.feed.list.TemplateAdapter",
                "setList", MutableList::class.java
            ) { param ->
                // 复制式过滤: 不原地 removeIf(入参可能为不可变列表, removeIf 抛异常会击穿宿主)
                val feedList = param.args[0] as? List<*> ?: return@hookBeforeMethod
                param.args[0] = feedList.filter { feed ->
                    feed == null || runCatching {
                        val md = sidewayMethod(feed.javaClass)
                        val type = md?.let { XposedHelpers.callMethod(feed, it.name) }
                        "sideway_card" != type
                    }.getOrDefault(true)
                }
            }
        }

        // 一键签到广告
        hookSafely("一键签到广告") {
            hookBeforeMethod("com.baidu.tieba.signall.SignAllForumAdvertActivity",
                "onCreate", Bundle::class.java) { param ->
                val activity = param.thisObject as? Activity
                activity?.finish()
            }
        }

        // 首页推荐右侧悬浮
        hookSafely("首页推荐右侧悬浮") {
            findClass("com.baidu.tbadk.widget.RightFloatLayerView").declaredMethods.filter { method ->
                method.parameterTypes.isEmpty() && method.returnType == Boolean::class.javaPrimitiveType
            }.forEach { method -> hookReplaceMethod(method) { false } }
        }

        // 个性化关闭横幅(首页底部"个性化内容推荐已关闭/去打开"引导条, 22.12 反编译:
        // com.baidu.tieba.homepage.personalize.controller.FeedPersonalizeBarController,
        // 类名未混淆, 方法/字段为单字母混淆名): 双保险封死。
        // 1) 控制器构造后按字段类型定位容器(类内唯一 ViewGroup 字段)置 GONE ——
        //    类型查找不受字段名随版本混淆轮换影响;
        // 2) show 方法(22.12 为 i(boolean))强制入参 false, 走宿主自己的关闭分支:
        //    与用户点 X 语义一致(精灵联动隐藏/事件收尾/消息派发均保留),
        //    任何 i(true) 都退化为 no-op 关闭。方法名候选未命中时写日志,
        //    版本漂移后按"单 boolean 参数 void 方法"形状排查改名即可
        hookSafely("个性化关闭横幅") {
            val controllerClass = findClass(
                "com.baidu.tieba.homepage.personalize.controller.FeedPersonalizeBarController"
            )
            hookAfterConstructor(controllerClass, android.view.ViewGroup::class.java) { param ->
                runCatching {
                    param.thisObject.javaClass.declaredFields
                        .firstOrNull { android.view.ViewGroup::class.java.isAssignableFrom(it.type) }
                        ?.let { field ->
                            field.isAccessible = true
                            (field.get(param.thisObject) as? android.view.View)?.visibility =
                                android.view.View.GONE
                        }
                }
            }
            val showMethod = PERSONALIZE_BAR_SHOW_METHODS.firstNotNullOfOrNull { name ->
                controllerClass.declaredMethods.firstOrNull { md ->
                    md.name == name && !Modifier.isStatic(md.modifiers) &&
                        md.parameterTypes.size == 1 &&
                        md.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                        md.returnType == Void.TYPE
                }
            }
            if (showMethod == null) {
                logBoth("Purge: 个性化关闭横幅 show 方法未命中(候选 $PERSONALIZE_BAR_SHOW_METHODS), 横幅可能漏出")
            } else {
                hookBeforeMethod(showMethod) { param ->
                    param.args[0] = false
                }
            }
        }

        // 更多板块 (吧友直播，友情吧), 一键签到页面广告
        // Purge.js 只拦截 tieba.baidu.com 的 frsBottom/getforumlist 两个接口,
        // 且注入对宿主全 app 所有 WebView 触发(含搜索/外部/广告 H5), 按 baidu 系
        // 域名过滤: 不影响吧页面等自带 H5, 不再向无关页面注入脚本。
        // 注入时机选 onPageFinished: onPageStarted 时新文档尚未 commit,
        // evaluateJavascript 实际执行在旧文档上, 净化脚本落不到目标页;
        // 域名按 host 精确匹配 baidu.com 及其子域, url 子串匹配会误伤
        // "evil-baidu.com" 之类第三方页
        hookSafely("WebView净化注入") {
            val jsPurgeScript = getAssetFileContent("Purge.js")
            jsPurgeScript?.let {
                hookBeforeMethod(
                    WebViewClient::class.java,
                    "onPageFinished", WebView::class.java, String::class.java
                ) { param ->
                    val mWebView = param.args[0] as? WebView
                    val url = param.args[1] as? String ?: return@hookBeforeMethod
                    val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
                    if (mWebView != null && host != null &&
                        (host == "baidu.com" || host.endsWith(".baidu.com"))
                    ) {
                        mWebView.evaluateJavascript(jsPurgeScript, null)
                    }
                }
            }
        }

        // 吧页面
        hookSafely("吧页面头条贴-FrsPage") {
            hookBeforeMethod(
                "tbclient.FrsPage.PageData\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param ->
                purgePageData(param.thisObject)
            }
        }
        hookSafely("吧页面头条贴-ThreadList") {
            hookBeforeMethod(
                "tbclient.ThreadList.PageData\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param ->
                purgePageData(param.thisObject)
            }
        }

        // 聊天-AI角色
        hookSafely("聊天AI角色") {
            hookBeforeMethod(
                "com.baidu.tieba.immessagecenter.chatgroup.data.ChatGroupInfo",
                "parse", JSONObject::class.java
            ) { param ->
                val chatGroupInfo = param.args[0] as? JSONObject
                chatGroupInfo?.put("aichat_entrance_info", null)
            }
        }

        // 12.55+ 帖子内广告(原仅捕 ClassNotFoundError, 按 NoSuchMethodError 等统一走逐项隔离)
        hookSafely("帖子内广告") {
            hookReplaceMethod(
                "com.fun.ad.sdk.internal.api.BaseNativeAd2",
                "getNativeInfo"
            ) { null }
        }

        // 首页彩蛋广告
        blockEasterEggAd()

        // 云控H5弹窗(hybrid-business-ad/618adPopup 等): BaseWebDialogProcessor 数据入口
        // 整体 no-op。类名未混淆, 22.11/22.12 双版本稳定; 参数 ae6/jf6 为混淆类,
        // 按形状匹配(Context + 2参 + void)而非精确签名。与 h5_pop_ups_config 规则
        // 同一净化语义: 该规则只拦一条 H5 弹窗配置链路, 云控弹窗(dialog_name=
        // xxx阿里首页弹窗 等)走 YunDialogManager 独立链路, 必须在处理器层掐断
        runCatching {
            val processor = findClass("com.baidu.tbadk.core.dialog.yun.h5.BaseWebDialogProcessor")
            XposedBridge.hookAllMethods(processor, "a", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    // 仅拦数据入口 a(Context, data, callback), 不误伤其它同名静态/桥方法
                    if (param.args.size == 3 && param.args[0] is android.content.Context) {
                        runCatching {
                            // 通知调用方"展示失败", 让上游状态机正常收尾( dismiss/统计 )
                            val callback = param.args[2]
                            XposedHelpers.callMethod(callback, "onError", "blocked by purify")
                        }
                        return null
                    }
                    return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                }
            })
        }

        // 帖子内容
        hookSafely("帖子内容") {
            hookBeforeMethod(
                "tbclient.Post\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param ->
                // 相关推荐
                XposedHelpers.setObjectField(param.thisObject, "outer_item", null)

                // 点击使用同系列表情
                XposedHelpers.setObjectField(param.thisObject, "sprite_meme_info", null)

                // 小说推荐
                XposedHelpers.setObjectField(param.thisObject, "novel_recom_card", null)
            }
        }

        // 首页样式 AB test
        hookSafely("首页样式AB test") {
            hookBeforeMethod(
                "com.baidu.tbadk.abtest.UbsABTestDataManager",
                "parseJSONArray",
                JSONArray::class.java
            ) { param ->
                // 防御性转换: args[0] 可能为 null/非 JSONArray, 条目可能缺 sid 键
                val currentABTestJson = param.args[0] as? JSONArray ?: return@hookBeforeMethod
                val newABTestJson = JSONArray()
                for (i in 0 until currentABTestJson.length()) {
                    val currTest = currentABTestJson.optJSONObject(i) ?: continue
                    if (!currTest.optString("sid").startsWith("12_57_5_home_search")) {
                        newABTestJson.put(currTest)
                    }
                }
                param.args[0] = newABTestJson
            }
        }

        // 全局 NPS 满意度打分卡(吧内热门"核心吧友专属"请为本吧氛围体验打分,
        // 首页/吧页面底部悬浮出现): GlobalNpsCardManager 是全局 NPS 卡单例管理器
        // (类名未混淆, 22.11/22.12/22.13 三版本一致), K(Activity, GlobalNpsScene,
        // GlobalNpsTrigger, Long) 是唯一请求入口(BaseForumTabModel.g0 在响应含
        // is_query_global_nps_card=1 时经接口 j()/h()/g() 调入), 替换为 no-op 后
        // 状态机恒为 IDLE, 请求/加载/挂载全部不再发生。仅掐 NPS 调研卡,
        // 不影响 feed_score 打分贴(ScorePostFilter 管辖)
        hookSafely("全局NPS打分卡") {
            XposedBridge.hookAllMethods(
                findClass("com.baidu.tieba.nps.global.GlobalNpsCardManager"),
                "K",
                XC_MethodReplacement.returnConstant(null)
            )
        }

        // 运营活动横幅卡(S16全球总决赛锅王投票这类活动图, 服务端经 banner_card
        // layout 下发): 模板卡体系里卡片键为 feed_banner(合成卡内嵌载荷, 卡工厂
        // ao9 构造 vd9), 与其它 PostFilter 同走 FeedListPipeline 按键过滤——
        // 独立模板卡命中移除条目, 合成卡(common_card/stagger_card)载荷内嵌
        // feed_banner 时整帖移除(合成卡本身还承载其它内容时保留, 见载荷扫描)
        hookSafely("运营活动横幅卡") {
            FeedListPipeline.register { list, _, _ -> filterBannerFeedList(list) }
        }
    }

    // 运营活动横幅模板键: 卡片键 b()=feed_banner(22.13 vd9.p / 22.12 zth 同构)
    private val BANNER_TEMPLATE_KEYS = setOf("feed_banner")

    // 列表入口处过滤横幅卡; 独立横幅卡移除条目, 合成卡内嵌横幅移除整帖。
    // 骨架共享于 Fs9Api.filterByCardKeys(原手写主循环已收编); 合成卡键取
    // Fs9Api.COMPOSITE_CARD_KEYS 单点
    private fun filterBannerFeedList(list: List<*>?): List<*>? =
        Fs9Api.filterByCardKeys(list, BANNER_TEMPLATE_KEYS) { payload ->
            Fs9Api.payloadHasKey(payload, BANNER_TEMPLATE_KEYS)
        }

    // 首页彩蛋广告: 首页底部横幅广告(带倒计时关闭, 服务端 sync 下发, 非每次出现)。
    // 展示链路(22.12 反编译): sync 数据的 floating_icon.easter_egg 由 rz8.parseJson
    // 写入 EasterEggAdDataHolder → 云弹窗策略判定可展示 → sz8 注册空闲任务
    // new EasterEggAdController(activity).o(data) 弹出横幅并倒计时。
    // easteregg 包为 12.74 之后的版本新增, 类缺失时静默跳过。
    private fun blockEasterEggAd() {
        // 数据层: 拦截 EasterEggAdDataHolder 的写入(唯一的 void(EasterEggAdData)
        // 实例方法, 方法名随版本混淆轮换, 按签名定位)。数据恒为空后云弹窗策略
        // 判定"data_null"不展示, 空闲任务注册/视图创建等下游全部不再发生
        try {
            val holderClass = findClass(EASTER_EGG_DATA_HOLDER)
            val dataClass = findClass(EASTER_EGG_DATA)
            holderClass.declaredMethods.filter { md ->
                !Modifier.isStatic(md.modifiers) &&
                    md.returnType == Void.TYPE &&
                    md.parameterTypes.size == 1 &&
                    md.parameterTypes[0] == dataClass
            }.forEach { md ->
                runCatching { hookReplaceMethod(md) { null } }
            }
        } catch (ignored: Throwable) {
        }
        // 视图层兜底: EasterEggAdController 中 void(EasterEggAdData) 实例方法
        // (showAd/showStat 两个)全部 no-op, 覆盖数据经其它路径写入的情况;
        // 广告未展示时这些方法本就不会被调用, 替换无副作用
        try {
            val controllerClass = findClass(EASTER_EGG_AD_CONTROLLER)
            val dataClass = findClass(EASTER_EGG_DATA)
            controllerClass.declaredMethods.filter { md ->
                !Modifier.isStatic(md.modifiers) &&
                    md.returnType == Void.TYPE &&
                    md.parameterTypes.size == 1 &&
                    md.parameterTypes[0] == dataClass
            }.forEach { md ->
                runCatching { hookReplaceMethod(md) { null } }
            }
        } catch (ignored: Throwable) {
        }
    }

    // 吧页面头条贴(41), 直播贴(69 / is_live_card)
    private fun purgePageData(pageData: Any) {
        val feedList = XposedHelpers.getObjectField(pageData, "feed_list") as? List<*>
        // 复制式过滤: 不原地 removeIf(入参可能不可变), 元素为 null/字段缺失时保留
        val filtered = feedList?.filter { feedItem ->
            feedItem == null || !runCatching {
                val currFeed = XposedHelpers.getObjectField(feedItem, "feed")
                val businessInfo = currFeed?.let {
                    XposedHelpers.getObjectField(it, "business_info") as? List<*>
                }
                businessInfo?.any { feedKV ->
                    feedKV != null && when (XposedHelpers.getObjectField(feedKV, "key")?.toString()) {
                        "thread_type" -> XposedHelpers.getObjectField(feedKV, "value")?.toString() in PURGE_FEED_TYPE_IDS
                        "is_live_card" -> XposedHelpers.getObjectField(feedKV, "value")?.toString() == "1"
                        "game_ext" -> true
                        else -> false
                    }
                } ?: false
            }.getOrDefault(false)
        }
        if (filtered != null && filtered.size != feedList.size) {
            XposedHelpers.setObjectField(pageData, "feed_list", filtered)
        }
    }
}
