package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Adp
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.dao.Preferences.getLikeForum
import gm.tieba.tabswitch.dao.Preferences.getLikeForumBduss
import gm.tieba.tabswitch.dao.Preferences.putLikeForum
import gm.tieba.tabswitch.dao.Preferences.putLikeForumBduss
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.LIKE_URL
import gm.tieba.tabswitch.hooker.auto.AutoSignHelper
import gm.tieba.tabswitch.widget.TbToast
import gm.tieba.tabswitch.widget.TbToast.Companion.showTbToast
import kotlin.concurrent.thread

class FollowFilter : XposedContext(), IHooker {

    override fun key(): String {
        return "follow_filter"
    }

    override fun hook() {
        // 启动即预拉取关注列表, 避免首屏推荐数据先于异步拉取完成而漏过滤
        if (getLikeForum() == null || isCacheStale()) {
            fetchFollowList()
        }
        // 注册点隔离: Builder 类缺失(版本漂移)只损失此入口, 不让后面的
        // decodeInBackGround 兜底与适配器层过滤一并失效
        runCatching {
            hookBeforeMethod(
                "tbclient.Personalized.DataRes\$Builder",
                "build", Boolean::class.javaPrimitiveType
            ) { param ->
                val forums = followList() ?: run {
                    // 缓存缺失或已随换号失效: 异步拉取关注列表补缓存。
                    // 缺失提示只弹一次, 避免每次推荐数据构建都弹 toast
                    if (!sMissingToastShown) {
                        sMissingToastShown = true
                        runOnUiThread { showTbToast("正在获取关注列表", TbToast.LENGTH_LONG) }
                    }
                    return@hookBeforeMethod
                }
                // 复制式过滤: 不原地 removeIf(入参可能不可变);
                // 无命中不写回——否则每次数据构建都白分配一张与原表等大的副本
                val threadList = XposedHelpers.getObjectField(param.thisObject, "thread_list") as? List<*>
                if (!threadList.isNullOrEmpty()) {
                    val filtered = filterThreadList(threadList, forums)
                    if (filtered.size != threadList.size) {
                        XposedHelpers.setObjectField(param.thisObject, "thread_list", filtered)
                    }
                }
                // 吧推荐卡(card_forum, 吧头+热帖+关注按钮)与 thread_list 过滤同理:
                // 内含未关注的吧则整卡丢弃(builder 重建分支此前只过滤了 thread_list)。
                val cardForum = XposedHelpers.getObjectField(param.thisObject, "card_forum") as? List<*>
                if (!cardForum.isNullOrEmpty()) {
                    val keptCards = filterCardForum(cardForum, forums)
                    if (keptCards.size != cardForum.size) {
                        XposedHelpers.setObjectField(param.thisObject, "card_forum", keptCards)
                    }
                }
            }
        }.onFailure {
            logBoth("FollowFilter: hook Personalized.DataRes\$Builder.build 失败 -- $it")
        }
        // 22.11 首页推荐响应由 Wire 直接 parseFrom(不走 Builder), 仅
        // mergeLogParam 重建分支会经过上面的 build 钩点, log_param 为空时
        // 被绕过。在响应解码完成后按关注列表改写 resultData 兜底。
        try {
            hookAfterMethod(
                "com.baidu.tieba.homepage.personalize.data.RecPersonalizeHttpResponse",
                "decodeInBackGround",
                Integer::class.javaPrimitiveType, ByteArray::class.java
            ) { param ->
                val dataRes = XposedHelpers.getObjectField(param.thisObject, "resultData")
                    ?: return@hookAfterMethod
                val forums = followList() ?: return@hookAfterMethod
                // Builder 类按缓存复用: 每条推荐响应解码都触发, findClass 的
                // 字符串键查找不值得逐次重复(解析失败 lazy 会重试)
                val builderClass = sPersonalizedBuilderClass ?: return@hookAfterMethod
                val builder = XposedHelpers.newInstance(builderClass, dataRes)
                var changed = false
                val threadList = XposedHelpers.getObjectField(dataRes, "thread_list") as? List<*>
                if (threadList != null) {
                    val filtered = filterThreadList(threadList, forums)
                    if (filtered.size != threadList.size) {
                        XposedHelpers.setObjectField(builder, "thread_list", filtered)
                        changed = true
                    }
                }
                // 吧推荐卡(吧头+热帖预览, 带+关注按钮): 内含明确未关注的吧才整卡丢弃。
                val cardForum = XposedHelpers.getObjectField(dataRes, "card_forum") as? List<*>
                if (!cardForum.isNullOrEmpty()) {
                    val keptCards = filterCardForum(cardForum, forums)
                    if (keptCards.size != cardForum.size) {
                        XposedHelpers.setObjectField(builder, "card_forum", keptCards)
                        changed = true
                    }
                }
                if (changed) {
                    XposedHelpers.setObjectField(
                        param.thisObject, "resultData", XposedHelpers.callMethod(builder, "build", true)
                    )
                }
            }
        } catch (ignored: Throwable) {
        }

        // 适配器列表层兜底: 首页 feed 无论走哪个数据通道(个性化接口/新通道/
        // 本地缓存恢复)最终都经这些列表入口渲染, 在此按吧名丢弃未关注的帖卡。
        // 只在能明确取到吧名时才判, 取不到的卡一律保留, 避免误杀。
        // 仅对 HomeTemplateAdapter 实例生效: 基类 FeedTemplateAdapter/
        // TemplateAdapter 的列表入口被吧内帖子列表共用, 无差别过滤会把
        // 未关注吧的整页帖子全丢掉(只剩置顶, 列表永远加载中)。
        // HomeTemplateAdapter 全仓库仅 PersonalizePageView 实例化(22.11 实测)。
        // 列表入口 hook 统一走 FeedListPipeline(单 hook 多阶段链), 此处只注册阶段
        FeedListPipeline.register { list, owner, _ ->
            if (isHomeAdapter(owner)) {
                recommendDelegate.filterTemplateList(filterCardList(list))
            } else {
                null
            }
        }
    }

    // 推荐"可能感兴趣的吧"卡(吧头+热帖+关注按钮)不含 forum_name 参数槽,
    // 按吧名过滤拦不到; 其推荐的必然是未关注的吧, 复用 RecommendForumFilter
    // 的模板键检测(独立卡/合成卡载荷/嵌套数据)一并丢弃
    private val recommendDelegate = RecommendForumFilter()

    // Personalized Builder 类(22.11 兜底重建分支用), 首次回调时解析并缓存
    private val sPersonalizedBuilderClass: Class<*>? by lazy {
        runCatching { findClass("tbclient.Personalized.DataRes\$Builder") }.getOrNull()
    }

    // 首页推荐适配器判别: HomeTemplateAdapter 是 FeedTemplateAdapter 唯一
    // 的首页子类, 基类入口(L/P/R/setList)只对它放行过滤。
    // homeAdapterResolved 区分"未解析"与"解析失败": 只判 null 时, 类不存在的
    // 旧版本宿主上每个列表回调都重跑 findClass 并构造 ClassNotFoundException。
    // 管线阶段在主线程+后台解码线程并发执行: 置位标志 volatile 且必须在结果
    // 发布之后(与 RecommendForumFilter 同口径), 否则另一线程可能读到
    // resolved=true 而类字段尚未发布, isInstance 恒 false, 过滤永久静默失效
    @Volatile
    private var homeAdapterClass: Class<*>? = null
    @Volatile
    private var homeAdapterResolved = false

    private fun isHomeAdapter(adapter: Any?): Boolean {
        if (adapter == null) return false
        if (!homeAdapterResolved) {
            homeAdapterClass = try {
                findClass("com.baidu.tieba.homepage.personalize.HomeTemplateAdapter")
            } catch (ignored: Throwable) {
                null
            }
            homeAdapterResolved = true
        }
        return homeAdapterClass?.isInstance(adapter) ?: false
    }

    // 模板卡参数载体(22.12=mr9/us9, 22.13=ds9/lt9): f() 与 c().a() 两个槽位, 取 forum_name
    // (类名随版本轮换, 解析共享于 CardParamsApi)
    private fun resolveMr9(): Boolean = CardParamsApi.resolve()

    private fun cardForumName(item: Any): String? {
        if (!resolveMr9() || !CardParamsApi.isParamItem(item)) return null
        val fName = mapForumName(CardParamsApi.fMap(item))
        if (fName != null) return fName
        return mapForumName(CardParamsApi.cHolderMap(item))
    }

    // 吧名键兼容: ThreadInfo 字段名是 fname, 卡参数映射里两种键都有出现
    private fun mapForumName(map: Map<*, *>?): String? {
        if (map == null) return null
        return map["forum_name"]?.toString()?.takeIf { it.isNotEmpty() }
            ?: map["fname"]?.toString()?.takeIf { it.isNotEmpty() }
    }

    // 关注列表吧名与卡片吧头显示名可能差一个"吧"后缀(接口返回 forum_name
    // 为"steam", 吧头显示"steam吧"), 双向归一匹配
    private fun isFollowed(name: String, forums: Set<String>): Boolean {
        if (name in forums) return true
        if (name.endsWith("吧") && name.dropLast(1) in forums) return true
        return (name + "吧") in forums
    }

    // thread_list 条目移除谓词(builder 分支与 decodeInBackGround 兜底共用的
    // 逐字重复逻辑收编): proto String 未设置时为 null 保留(fail-open, 避免误杀);
    // 与适配器层 isFollowed 同口径: 吧名可能带/不带"吧"后缀, 裸 contains 遇
    // 后缀形态会静默漏过滤
    private fun filterThreadList(list: List<*>, forums: Set<String>): List<*> =
        Fs9Api.filterCopy(list) { thread ->
            !(thread == null || run {
                val fname = XposedHelpers.getObjectField(thread, "fname") as? String
                fname == null || isFollowed(fname, forums)
            })
        }

    // card_forum 整卡移除谓词(同上收编): 内含明确未关注的吧才整卡丢弃,
    // 条目 forum_name 缺失时按未知保留(fail-open), 否则 proto 未下发吧名的卡
    // 会被误杀; null 条目按原实现剔除
    private fun filterCardForum(list: List<*>, forums: Set<String>): List<*> =
        Fs9Api.filterCopy(list) { card ->
            if (card == null) return@filterCopy true
            val forumList = runCatching {
                XposedHelpers.getObjectField(card, "forum_list") as? List<*>
            }.getOrNull()
            val keep = forumList == null || forumList.all { entry ->
                entry == null || run {
                    val forumName = runCatching {
                        XposedHelpers.getObjectField(entry, "forum_name") as? String
                    }.getOrNull()
                    forumName == null || isFollowed(forumName, forums)
                }
            }
            !keep
        }

    // 在适配器列表入口按吧名过滤未关注的帖卡; 返回原列表表示无需修改
    private fun filterCardList(list: List<*>?): List<*>? {
        if (list == null || list.isEmpty()) return list
        val forums = followList() ?: return list
        return Fs9Api.filterCopy(list) { item ->
            item != null && runCatching {
                // 吧头+热帖形态的合成卡两个 forum_name 参数槽位皆空, 吧名藏在
                // 载荷内层 feed_head/feed_enter_forum 组件内, 参数槽取不到时按载荷判定
                val name = cardForumName(item) ?: payloadForumName(item)
                    ?: return@runCatching false
                !isFollowed(name, forums)
            }.getOrDefault(false)
        }
    }

    // 模板卡数据项接口 (22.12=nt9/fs9, 22.13=eu9): b()=卡片键, d()=载荷(共享 Fs9Api)
    private fun resolveCardMethods(): Boolean = Fs9Api.resolve()

    private fun cardKey(item: Any?): String? {
        return Fs9Api.cardKey(item)
    }

    // 载荷类实例的 List 字段(含继承), 共享缓存见 Fs9Api.resolveListFields
    private fun resolveListFields(clazz: Class<*>): List<java.lang.reflect.Field> =
        Fs9Api.resolveListFields(clazz)

    // 合成卡载荷内的吧名(两个参数槽位皆空的吧头卡): 内层 feed_head 组件
    // (吧头, FeedHeadComponent.main_data 符号内 FeedKV/文本)、feed_enter_forum
    // 组件(ThreadRecommendInfo), 或内层 ThreadInfo 预览的 fname 字段
    private fun payloadForumName(item: Any): String? {
        // gs9 族聚合卡不是 mr9, 卡参数经无参 Map 方法(rg9/i().a())暴露,
        // 与 HotPostFilter 内层 card_type 探测同路, 先查对象自身参数映射
        for (accessor in resolveMapAccessors(item.javaClass)) {
            val map = runCatching { accessor(item) }.getOrNull() ?: continue
            mapForumName(map)?.let { return it }
        }
        if (!resolveCardMethods()) return null
        val payload = runCatching { Fs9Api.cardPayload(item) }.getOrNull() ?: return null
        readStringField(payload, "forum_name")?.let { return it }
        readStringField(payload, "fname")?.let { return it }
        for (accessor in resolveMapAccessors(payload.javaClass)) {
            val map = runCatching { accessor(payload) }.getOrNull() ?: continue
            mapForumName(map)?.let { return it }
        }
        for (field in resolveListFields(payload.javaClass)) {
            val inner = runCatching { field.get(payload) as? List<*> }.getOrNull() ?: continue
            for (element in inner) {
                if (element == null) continue
                when (cardKey(element)) {
                    "feed_enter_forum" -> {
                        val rec = runCatching { Fs9Api.cardPayload(element) }.getOrNull()
                        if (rec != null) readStringField(rec, "forum_name")?.let { return it }
                    }
                    "feed_head" -> {
                        val head = runCatching { Fs9Api.cardPayload(element) }.getOrNull()
                        if (head != null) {
                            val name = feedHeadForumName(head)
                            if (name != null) return name
                        }
                    }
                }
                readStringField(element, "forum_name")?.let { return it }
                readStringField(element, "fname")?.let { return it }
                // 内层元素(BaseCardInfo 族)的参数映射探测
                for (accessor in resolveMapAccessors(element.javaClass)) {
                    val map = runCatching { accessor(element) }.getOrNull() ?: continue
                    mapForumName(map)?.let { return it }
                }
            }
        }
        return null
    }

    // 对象上无参 Map 返回值方法取值器(含一级对象字段的方法), 共享缓存见
    // Fs9Api.resolveMapAccessors
    private fun resolveMapAccessors(clazz: Class<*>): List<(Any) -> Map<*, *>?> =
        Fs9Api.resolveMapAccessors(clazz)

    // 吧头组件取吧名: 先看符号 business_info/log_info 内 FeedKV(key=forum_name
    // /fname), 再兜底取符号文本(吧头主文本即吧名, 以"吧"结尾且不超长才采信,
    // 避免"+关注"/时间戳等文案误当吧名)
    private fun feedHeadForumName(head: Any): String? {
        for (field in resolveListFields(head.javaClass)) {
            val symbols = runCatching { field.get(head) as? List<*> }.getOrNull() ?: continue
            for (symbol in symbols) {
                if (symbol == null) continue
                for (kvField in resolveListFields(symbol.javaClass)) {
                    val kvs = runCatching { kvField.get(symbol) as? List<*> }.getOrNull() ?: continue
                    for (kv in kvs) {
                        if (kv == null) continue
                        val key = readStringField(kv, "key") ?: continue
                        if (key == "forum_name" || key == "fname") {
                            readStringField(kv, "value")?.let { return it }
                        }
                    }
                }
                val textObj = runCatching { XposedHelpers.getObjectField(symbol, "text") }.getOrNull()
                val text = textObj?.let { readStringField(it, "text") }
                if (!text.isNullOrEmpty() && text.endsWith("吧") && text.length <= 30) return text
            }
        }
        return null
    }

    // 读对象上的 public String 字段(Wire protobuf 字段均为 public final), 缺失返回 null
    private fun readStringField(obj: Any, name: String): String? {
        return runCatching {
            val value = XposedHelpers.getObjectField(obj, name) as? String
            value?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    // 关注列表缓存按账号校验: 缓存不存在或属于其他账号(BDUSS不匹配, 换号后
    // 旧缓存仍可读)时返回 null 并异步重拉, 避免按旧账号关注列表过滤。
    // BDUSS 未知(未登录/AccountData 钩子未触发)时必须视为过期:
    // 此时无法证明缓存属于当前账号, 宁可不过滤也不按旧账号列表误杀
    private fun isCacheStale(): Boolean {
        val bduss = Adp.BDUSS ?: return true
        return getLikeForumBduss() != bduss
    }

    private fun followList(): Set<String>? {
        if (getLikeForum() == null || isCacheStale()) {
            // 失败退避: 60s 内不再重试, 避免离线/接口失效时每次列表构建都发请求
            if (System.currentTimeMillis() - sLastFetchFailAt > FETCH_FAIL_BACKOFF_MILLIS) {
                fetchFollowList()
            }
            return null
        }
        return getLikeForum()
    }

    // 拉取用户全部关注的吧并写缓存(缓存与账号BDUSS绑定); 网络失败后允许重试,
    // 但有 60s 退避, 否则断网/接口失效时每次推荐列表构建都会起新线程发真实请求
    private fun fetchFollowList() {
        if (!sFetching.compareAndSet(false, true)) return
        thread {
            try {
                val bduss = Adp.BDUSS ?: run {
                    // 未登录/钩子未触发, 记退避, 避免每次列表构建都空转一个线程
                    sLastFetchFailAt = System.currentTimeMillis()
                    return@thread
                }
                AutoSignHelper.setCookie(bduss)
                val jsonObject = AutoSignHelper.get(LIKE_URL)
                val jsonArray = jsonObject.getJSONObject("data").getJSONArray("like_forum")
                val forums = HashSet<String>(jsonArray.length())
                for (i in 0 until jsonArray.length()) {
                    jsonArray.optJSONObject(i)?.getString("forum_name")?.let { forums.add(it) }
                }
                if (forums.isNotEmpty()) {
                    putLikeForum(forums)
                    putLikeForumBduss(bduss)
                    sLastFetchFailAt = 0L
                    logBoth("TiebaTS: follow list fetched (${forums.size})")
                } else {
                    // 200 但空列表(登录态失效/风控): 与失败同走退避,
                    // 否则每次列表构建都会重发真实请求
                    sLastFetchFailAt = System.currentTimeMillis()
                    logBoth("TiebaTS: follow list empty, back off")
                }
            } catch (t: Throwable) {
                sLastFetchFailAt = System.currentTimeMillis()
                logBoth(t)
            } finally {
                sFetching.set(false)
            }
        }
    }

    companion object {
        // 与 AutoSign 相同的关注列表接口, 不依赖签到状态;
        // LIKE_URL 收编至 hooker/HostAnchors.kt（与 AutoSign 共用）
        @Volatile
        private var sMissingToastShown = false

        // 防止并发拉取; 网络失败后复位允许重试
        private val sFetching = java.util.concurrent.atomic.AtomicBoolean(false)

        // 失败退避: 上次拉取失败时刻(0=无失败), 成功后清零
        @Volatile
        private var sLastFetchFailAt = 0L
        private const val FETCH_FAIL_BACKOFF_MILLIS = 60_000L
    }
}
