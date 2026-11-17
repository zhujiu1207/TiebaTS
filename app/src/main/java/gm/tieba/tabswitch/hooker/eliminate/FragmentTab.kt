package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.AcRules.findRule
import gm.tieba.tabswitch.dao.Preferences.getBoolean
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.Obfuscated
import gm.tieba.tabswitch.hooker.deobfuscation.DeobfuscationHelper
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher
import gm.tieba.tabswitch.hooker.deobfuscation.MethodNameMatcher
import gm.tieba.tabswitch.hooker.deobfuscation.SmaliMatcher
import gm.tieba.tabswitch.hooker.deobfuscation.StringMatcher
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.util.findFirstMethodByExactType
import gm.tieba.tabswitch.util.setObjectField
import org.luckypray.dexkit.query.matchers.ClassMatcher
import java.lang.ref.WeakReference

class FragmentTab : XposedContext(), IHooker, Obfuscated {

    override fun key(): String {
        return "fragment_tab"
    }

    override fun matchers(): List<Matcher> {
        return listOf(
            StringMatcher("has_show_message_tab_tips"),
            SmaliMatcher("Lcom/airbnb/lottie/LottieAnimationView;->setImageResource(I)V").apply {
                classMatcher = ClassMatcher.create().usingStrings("has_show_message_tab_tips")
            },
            // 22.11+ 底栏小卖部 delegate (混淆类): 类内唯一组合特征为
            // 字符串 "retailStore" + 未混淆方法 createFragmentTabStructure
            MethodNameMatcher("createFragmentTabStructure", "retail_store_delegate").apply {
                classMatcher = ClassMatcher.create().usingStrings(
                    "retailStore",
                    "null cannot be cast to non-null type com.baidu.tbadk.mainTab.MainTabCommonIndicator"
                )
                requiredVersion = "22.11.0.0"
            }
        )
    }

    override fun hook() {
        // 22.11+: 发帖入口由底栏Tab改为首页顶栏右上角"+"按钮, 隐藏发帖时同步隐藏
        if (getBoolean("write_thread")) {
            hideHomeWriteButton()
        }
        // 小卖部 delegate 类名与 lottie hook 均只在 hook() 时解析注册一次:
        // 放在 hook 回调体内会每次目标方法执行都查一次 Room 且重复注册 lottie
        // 钩子(无界累积, 内存增长 + 同一回调重复执行)
        var retailStoreDelegateClass: String? = null
        findRule("retail_store_delegate") { _, clazz, _ ->
            retailStoreDelegateClass = clazz
        }
        if (retailStoreDelegateClass == null && getBoolean("retail_store") &&
            DeobfuscationHelper.isTbSatisfyVersionRequirement("22.12.0.0")
        ) {
            // 22.12 起规则锚点("retailStore" 字符串常量)已移出 delegate 类, DexKit
            // 组合特征不再成立, 按版本硬编码兜底: delegate 为未混淆 RetailStoreFragment
            // 的绑定类(22.12=loe, 22.13=sqe, 22.14=zse; 均含 createFragmentTabStructure +
            // "retailStore" + MainTabCommonIndicator 强转, 形态一致)。绑定前校验
            // createFragmentTabStructure 仍存在, 类名再漂移时静默放弃
            runCatching {
                val fallback = listOf(
                    "com.baidu.tieba.zse", "com.baidu.tieba.sqe", "com.baidu.tieba.loe"
                ).firstOrNull { name ->
                    runCatching {
                        findClass(name).methods.any { it.name == "createFragmentTabStructure" }
                    }.getOrDefault(false)
                }
                if (fallback != null) {
                    retailStoreDelegateClass = fallback
                } else {
                    logBoth("FragmentTab: 22.12+ 小卖部兜底类 zse/sqe/loe 形态漂移, 放弃绑定")
                }
            }.onFailure {
                logBoth("FragmentTab: 22.12+ 小卖部兜底类不存在 -- ${it.javaClass.simpleName}")
            }
        }
        // 22.11 底部写按钮曾一度移除, 22.12 起回归(见下方 z() 短路);
        // 且该 lottie hook 会误伤其它 TBLottie 视图(帖子编辑器初始化时被拦截会 NPE),
        // 仅在 22.11 之前的版本应用
        if (getBoolean("write_thread") && !DeobfuscationHelper.isTbSatisfyVersionRequirement("22.11.0.0")) {
            findRule("Lcom/airbnb/lottie/LottieAnimationView;->setImageResource(I)V") { _, clazz, method ->
                val md = XposedHelpers.findMethodExactIfExists(clazz, sClassLoader, method)
                md?.let {
                    hookBeforeMethod(it) { param ->
                        setObjectField(
                            param.thisObject,
                            "com.baidu.tbadk.widget.lottie.TBLottieAnimationView",
                            null
                        )
                        param.setResult(null)
                    }
                }
            }
        }

        // 整段隔离: findFirstMethodByExactType 在方法漂移时抛 NoSuchMethodError,
        // 不包裹会让 hook() 中断, 此前已注册的功能与该功能一起全灭
        runCatching {
            findRule("has_show_message_tab_tips") { _, clazz, _ ->
                val method = findFirstMethodByExactType(clazz, ArrayList::class.java)
                hookBeforeMethod(method) { param ->
                // 联合短路: fragment_tab 常驻 activeHookerKeys(规则解析与 retailStore
                // 兜底依赖), 5 个子开关全关时回调仍会在每次底栏重建时空转——
                // HashSet 分配 + 全列表 removeIf/count 全部可免
                if (!getBoolean("home_recommend") && !getBoolean("enter_forum") &&
                    !getBoolean("retail_store") && !getBoolean("write_thread") &&
                    !getBoolean("im_message")
                ) {
                    return@hookBeforeMethod
                }
                val tabsToRemove = HashSet<String>().apply {
                    if (getBoolean("home_recommend")) {
                        add("com.baidu.tieba.homepage.framework.RecommendFrsDelegateStatic")
                    }
                    if (getBoolean("enter_forum")) {
                        // 22.11 起 enterForum 包名改为全小写 enterforum
                        add("com.baidu.tieba.enterForum.home.EnterForumDelegateStatic")
                        add("com.baidu.tieba.enterforum.home.EnterForumDelegateStatic")
                    }
                    if (getBoolean("retail_store")) {
                        // 22.11+ 底栏小卖部 (混淆 delegate, 类名已在 hook() 时解析)
                        retailStoreDelegateClass?.let { add(it) }
                    }
                    if (getBoolean("write_thread")) {
                        add("com.baidu.tieba.write.bottomButton.WriteThreadDelegateStatic")
                        // 22.11+: 发帖入口在首页顶栏右上角, lottie hook 已在 hook() 注册
                    }
                    if (getBoolean("im_message")) {
                        add("com.baidu.tieba.imMessageCenter.im.chat.notify.ImMessageCenterDelegateStatic")
                        add("com.baidu.tieba.immessagecenter.im.chat.notify.ImMessageCenterDelegateStatic")
                    }
                }

                val tabList = param.args[0] as? ArrayList<*>
                // 槽位锚点采集(必须在 removeIf 之前): 宿主 z() 固定把发帖槽位插在
                // child index 2, 语义是"首页、进吧两个 delegate 之后"(原生栏序
                // [首页,进吧,槽位,消息,我的])。按这两者(含 22.11 进吧小写包名)的
                // 存活数定位插入点——隐藏哪一侧, 槽位就向哪一侧补位, 槽位不越过
                // 任何存活的 Tab。不要在这里调 isAvailable(): 提前调用时部分
                // delegate 的可用性尚未就绪, 会把锚点集合算错(实测 + 跑到右边)
                val preSlotClasses = setOf(
                    "com.baidu.tieba.homepage.framework.RecommendFrsDelegateStatic",
                    "com.baidu.tieba.enterForum.home.EnterForumDelegateStatic",
                    "com.baidu.tieba.enterforum.home.EnterForumDelegateStatic"
                )
                // null 元素保留不删, 避免整批 removeIf 中断。
                // write_thread 叠加语义层兜底: 发帖 delegate 类名可能随版本混淆轮换
                // (同小卖部 loe), 按类名移除会落空; FragmentTabStructure.type==9 是
                // "发帖页"的跨版本稳定判据(点按经 home_write 登录检查进发布器), 据此移除。
                // 开关读取提升到谓词外: 谓词对每个未命中 Tab 各读一次 SP 是纯冗余
                val writeThread = getBoolean("write_thread")
                tabList?.removeIf { tab ->
                    tab != null && (tabsToRemove.contains(tab.javaClass.name) ||
                        (writeThread && isWriteTabDelegate(tab)))
                }
                mSlotAnchorIndex = tabList?.count { tab ->
                    tab != null && tab.javaClass.name in preSlotClasses
                }
                }

                // 22.12 起底部中央发帖"+"回归: 由该控制器的 z() 独立创建可点击槽位
                // 指示器并设置中央蓝"+"图(2131233513)/动态图标, 不经过上面的
                // delegate 列表, 按类名移除挡不住它。22.12.1.0 实测 z() 的唯一调用点
                // 是上面被 hook 的 L()(L 内 retailStorePage()==false 分支), 在 L 返回前
                // 同步执行, 短路 z() 可同时去掉"+"图标与可点击空槽, 点击区域随之消失;
                // L 之后的异步路径(K(), 动态图标消息/会员横幅消息回调)只会重显 h/i,
                // 不会重建槽位, z() 短路后无需再兜。"z"为混淆名, 仅限 22.12+ 应用并
                // runCatching 隔离, 失败静默不影响其它功能;
                // 顶栏"+"的隐藏仍走 TripleSlotFlexLayout 路径
                if (getBoolean("write_thread") && DeobfuscationHelper.isTbSatisfyVersionRequirement("22.12.0.0")) {
                    runCatching {
                        val controller = XposedHelpers.findClass(clazz, sClassLoader)
                        val fields = controller.declaredFields.associateBy { it.name }
                        // 形态校验: 中央按钮三视图字段名与类型须完全匹配(h=TBLottie/i=TbImageView/
                        // j=容器FrameLayout), 防未来混淆名漂移时误藏无关视图
                        val hasLottieField =
                            fields["h"]?.type?.name == "com.baidu.tbadk.widget.lottie.TBLottieAnimationView"
                        val centerButtonShapeOk = hasLottieField &&
                            fields["i"]?.type?.name == "com.baidu.tbadk.widget.TbImageView" &&
                            fields["j"]?.type?.name == "android.widget.FrameLayout"

                        // 1) z() 短路: 该方法独立创建可点击槽位指示器并设置蓝"+"图/动态图标,
                        //    22.12.1.0 中唯一调用点是上面被 hook 的 L(), 短路后点击区域随之消失
                        val zMethod = if (hasLottieField) {
                            controller.declaredMethods.firstOrNull { zMethod ->
                                zMethod.name == "z" && zMethod.parameterTypes.isEmpty() &&
                                    // 限定 void: 混淆名可能被带返回值的方法占用, 对基本类型
                                    // 返回 setResult(null) 会在宿主调用点拆箱崩溃且无法兜底
                                    zMethod.returnType == Void.TYPE
                            }
                        } else {
                            null
                        }
                        if (zMethod == null) {
                            // 静默会导致无法区分"没挂上"与"挂上但没生效", 落日志便于排查
                            logBoth("FragmentTab: 底栏发帖 z() 未定位(形态校验=$hasLottieField)")
                        } else {
                            // setResult 才保证置位 returnEarly 跳过原方法, 直接给 result 字段
                            // 赋值在不同 Xposed 实现上不保证短路(与上方 lottie 短路写法一致)
                            hookBeforeMethod(zMethod) { param -> param.setResult(null) }
                        }

                        // 2) 中央按钮视图隐藏: 蓝"+"图内置于布局 XML 的悬浮视图(h/i/j),
                        //    不依赖 z() 运行时设置, 短路 z() 后仍显示, 且 Tab 重排后悬浮在
                        //    "消息"上方; h/i/j 的可见性设置均发生在 L() 返回前(z() 在 L 内
                        //    同步执行), 在被 hook 的方法之后统一置 GONE 可压制所有显示来源
                        //    (X()/Y() 重置只设 VISIBLE 不设图, 空视图不渲染, 无需额外处理)
                        if (centerButtonShapeOk) {
                            hookAfterMethod(method) { param ->
                                for (fieldName in arrayOf("h", "i", "j")) {
                                    val view = runCatching {
                                        XposedHelpers.getObjectField(param.thisObject, fieldName)
                                            as? android.view.View
                                    }.getOrNull()
                                    if (view != null && view.visibility != android.view.View.GONE) {
                                        view.visibility = android.view.View.GONE
                                    }
                                }
                            }
                        } else {
                            logBoth("FragmentTab: 底栏发帖中央按钮字段形态漂移, 跳过视图隐藏")
                        }
                    }.onFailure { logBoth("FragmentTab: 底栏发帖+短路注册失败 -- $it") }
                }

                // 中央发帖槽位纠位: 宿主 z() 把槽位固定插在 child index 2
                // (getFragmentTabWidget().g(gVar, 2), 22.11/22.12/22.13 一致且为全树唯一
                // 调用点), 语义是"列表前两位 delegate 之后"。原生 4 delegate 时 index 2
                // 恰好正中; 隐藏首页/进吧/消息任一项后剩余 Tab 变少, 若仍插在 2, 槽位会
                // 越过存活的 Tab(如隐藏首页后变成 [进吧,消息,槽位,我的]), 且剩余 Tab
                // 少于 2 个时 addView(view, 2) 直接越界崩溃。
                // 保序插入: 插入点改为"原生前两位 delegate 中的存活数"(L() 回调里在
                // removeIf 前采集前两位类名、移除后计数, 经 mSlotAnchorIndex 传入),
                // 隐藏哪一侧, 槽位就向哪一侧补位, 与存活 Tab 的相对顺序保持原生不变:
                //   隐藏首页 → [进吧,槽位,消息,我的]; 隐藏进吧 → [首页,槽位,消息,我的];
                //   隐藏消息 → [首页,进吧,槽位,我的]; 隐藏首页+进吧 → [槽位,消息,我的]。
                // 满编(N>=4, 含云控 5 Tab 且 retailStorePage()==false 仍走 z() 的场景)
                // 保持宿主语义, 仅在模块删 Tab 后(N<4)才纠位。
                // 选中态/点击序号按注册序(n() 的 D 计数 + C 表)管理, 与 child index 无关;
                // 指示线已被宿主 setShouldDrawIndicatorLine(false) 关闭, 移动槽位无副作用。
                // 已知代价: eaf/ucf 的引导气泡按固定 index(1/2/4)锚定底栏子视图, Tab 被
                // 隐藏后气泡本就错锚, 纠位不使其更糟。
                //
                // 悬浮"+"跟随: 槽位单元格挪位后, 悬浮蓝"+"(h=lottie/i=动态图标, XML 布局
                // 恒居中)不会跟着走——T=4(3 Tab+槽位)时 50% 处没有单元格中心, "+"骑在
                // 单元格边界上, 旁边空出槽位单元格(5.1.3 实测反馈)。把 h/i 平移到槽位
                // 单元格中心: dx = 槽位中心x − 底栏中心x, T 为奇数时 dx=0(原生等价),
                // 槽位重建/栏宽变化由 onLayout 每次重算。宿主三版本对 h/i 均无
                // setTranslationX(平移量独立于布局, 宿主 A(int) 只改 LayoutParams 不会
                // 清零), h/i 亦无点击监听(点击由其覆盖的槽位单元格承接, 平移后重新对齐)
                if (getBoolean("write_thread") || getBoolean("home_recommend") ||
                    getBoolean("enter_forum") || getBoolean("im_message") ||
                    getBoolean("retail_store")
                ) {
                    runCatching {
                        val widgetClass = XposedHelpers.findClass(
                            "com.baidu.tbadk.core.tabHost.FragmentTabWidget", sClassLoader
                        )
                        hookBeforeMethod(widgetClass, "g",
                            android.view.View::class.java, Int::class.javaPrimitiveType
                        ) { param ->
                            val slotIndex = param.args[1] as? Int ?: return@hookBeforeMethod
                            val widget = param.thisObject as? android.view.ViewGroup ?: return@hookBeforeMethod
                            // 宿主只在 z() 传 2(全树唯一调用点), 传其它值的一律不动。
                            // 注意槽位视图(控制器内部类)本身就是 TbFragmentTabIndicator 的
                            // 子类, 不能再按该类名排除调用方——那会把真槽位一并拦掉,
                            // 5.1.3/5.1.4 的纠位因此整体未生效(实测复现)
                            if (slotIndex != 2) {
                                return@hookBeforeMethod
                            }
                            // 走到此处时 delegate 已全部追加完毕(L() 先 s() 清空再逐个
                            // f(view,-1) 追加, 最后才调 z()), childCount 即存活 Tab 数;
                            // 满编(N>=4, 含云控 5 Tab 且 retailStorePage()==false 仍走 z()
                            // 的场景)保持宿主语义, 仅在模块删 Tab 后(N<4)才纠位
                            val count = widget.childCount
                            if (count >= 4) {
                                return@hookBeforeMethod
                            }
                            // 记录槽位视图供悬浮"+"跟随定位(onLayout 按引用在底栏找槽位)
                            mSlotViewRef = WeakReference(param.args[0] as? android.view.View)
                            // 保序插入点: 取 L() 回调里算好的锚点(原生前两位 delegate 的
                            // 存活数); 锚点未知(尚未经历 L()/列表形态异常)时退回按数量
                            // 取中位, 兜底保证插入点不越界
                            val anchor = mSlotAnchorIndex
                            val target =
                                if (anchor != null && anchor in 0..count) anchor else (count + 1) / 2
                            if (target != 2) {
                                param.args[1] = target
                            }
                        }
                        // L() 每次重建底栏都会执行: 捕获控制器实例(h/i 字段挂在其上,
                        // z()/g() 在 L() 返回前执行, 首次布局时控制器引用已就位)
                        hookAfterMethod(method) { param ->
                            mControllerRef = WeakReference(param.thisObject)
                        }
                        // 每次底栏布局把悬浮"+"平移到槽位单元格中心。槽位引用失效/脱离
                        // 该底栏(含其它页面共用 FragmentTabWidget 的实例)时跳过不误移
                        hookAfterMethod(widgetClass, "onLayout",
                            Boolean::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        ) { param ->
                            val widget = param.thisObject as? android.view.ViewGroup
                                ?: return@hookAfterMethod
                            val slot = mSlotViewRef?.get() ?: return@hookAfterMethod
                            if (slot.parent !== widget || widget.width <= 0 || slot.width <= 0) {
                                return@hookAfterMethod
                            }
                            val controller = mControllerRef?.get() ?: return@hookAfterMethod
                            val fields = floatButtonFieldsOf(controller.javaClass)
                                ?: return@hookAfterMethod
                            val dx = (slot.left + slot.width * 0.5f) - widget.width * 0.5f
                            runCatching {
                                (fields.first.get(controller) as? android.view.View)
                                    ?.translationX = dx
                                (fields.second.get(controller) as? android.view.View)
                                    ?.translationX = dx
                            }
                        }
                    }.onFailure { logBoth("FragmentTab: 发帖槽位纠位注册失败 -- $it") }
                }
            }
        }.onFailure { logBoth("FragmentTab: 底栏 Tab 移除注册失败 -- $it") }
    }

    // FragmentTabStructure.type==9 即发帖页(全版本仅 WriteThreadDelegateStatic 使用),
    // 是类名之外的跨版本稳定判据; 结构获取失败按非发帖处理, 不误删其它 Tab
    private fun isWriteTabDelegate(tab: Any): Boolean {
        return runCatching {
            val structure = XposedHelpers.callMethod(tab, "getFragmentTabStructure")
            structure != null && XposedHelpers.getIntField(structure, "type") == 9
        }.getOrDefault(false)
    }

    // 底栏发帖槽位视图与控制器引用(弱引用, 视图/控制器重建后自动失效)。
    // 槽位由 z() 经 g(view,2) 创建, 控制器是 has_show_message_tab_tips 所在类实例
    private var mSlotViewRef: WeakReference<android.view.View>? = null
    private var mControllerRef: WeakReference<Any>? = null

    // 发帖槽位保序插入点: 每次 L() 重建时按"原生前两位 delegate 的存活数"重算,
    // g() 纠位时消费; null=未知(尚未经历 L() 或列表形态异常), 纠位退回按数量取中位。
    // 采集与消费同在主线程(L() → z() → g() 同步调用链), 无并发问题
    private var mSlotAnchorIndex: Int? = null

    // 悬浮蓝"+"视图字段对(h=TBLottieAnimationView 静态图/i=TbImageView 动态图标)的
    // 反射解析缓存: 三版本控制器字段名一致(22.11=m2f/22.12=m7f/22.13=u9f 均为
    // public TBLottieAnimationView h; public TbImageView i;), 按类解析一次后复用;
    // 形态不符返回 null(负缓存), onLayout 每帧不再重复解析
    private fun floatButtonFieldsOf(controllerClass: Class<*>): Pair<java.lang.reflect.Field, java.lang.reflect.Field>? {
        sFloatButtonFieldsCache[controllerClass]?.let { return it }
        val resolved = runCatching {
            val lottie = controllerClass.getField("h")
            val img = controllerClass.getField("i")
            if (lottie.type.name == "com.baidu.tbadk.widget.lottie.TBLottieAnimationView" &&
                img.type.name == "com.baidu.tbadk.widget.TbImageView"
            ) {
                lottie to img
            } else {
                null
            }
        }.getOrNull()
        sFloatButtonFieldsCache[controllerClass] = resolved
        return resolved
    }

    private val sFloatButtonFieldsCache =
        HashMap<Class<*>, Pair<java.lang.reflect.Field, java.lang.reflect.Field>?>()

    // 22.11+: 首页顶栏右上角"+"发帖按钮 (HomeTabBarRightSlot 内的普通ImageView)
    // TripleSlotFlexLayout 每次 onLayout 都会重排, 在其后持续保持隐藏
    private fun hideHomeWriteButton() {
        HomeTopBarState.registerSearchIconCapture()
        try {
            hookAfterMethod(
                "com.baidu.tieba.homepage.view.TripleSlotFlexLayout",
                "onLayout",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ) { param ->
                hideWriteButtonIn(param.thisObject as android.view.View)
            }
        } catch (ignored: Throwable) {
        }
    }

    // RightSlot 定位与 getSearchIconView 解析已收编 RightSlotLocator
    // (与 HomeTopBar 共用单份缓存/退避实现, 版本漂移时单点维护)

    private fun hideWriteButtonIn(view: android.view.View) {
        if (view.visibility == android.view.View.GONE || view !is android.view.ViewGroup) {
            return
        }
        // 定位器内部: 缓存命中校验挂载态, 空扫退避防形态异常时逐帧全树重扫
        val slot = RightSlotLocator.rightSlotOf(view) ?: return
        // Slot 可能被宿主置 GONE, 需校验
        if (slot.visibility != android.view.View.GONE) {
            hideWriteButtonInRightSlot(slot)
        }
    }

    private fun hideWriteButtonInRightSlot(slot: android.view.ViewGroup) {
        // 优先用 HomeTabBarRightSlot.getSearchIconView() 精确定位放大镜, 回调捕获兜底;
        // 放大镜与发帖+同为 android.widget.ImageView, 仅靠类名匹配会把放大镜误隐藏且无法恢复
        val searchIcon = RightSlotLocator.rightSlotSearchIcon(slot)
            ?: HomeTopBarState.searchIconRef?.get()
        for (i in 0 until slot.childCount) {
            val child = slot.getChildAt(i)
            if (child === searchIcon) continue
            if (child.javaClass.name == "android.widget.ImageView" &&
                child.visibility != android.view.View.GONE
            ) {
                child.visibility = android.view.View.GONE
            }
        }
    }
}
