package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.bridge.XC_MethodHook
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.util.Logger
import java.util.concurrent.ConcurrentHashMap

// 屏蔽指定用户: 名单填个人主页展示的"ID"(User.tieba_uid, 新版统一用户 ID),
// 按 "/" 隔开, 覆盖首页推荐/吧内帖子/楼层/楼中楼/楼层回复, 全部入口按内部
// 编号(User.id/Post.author_id)精确匹配。
//
// 两套编号不互通(22.14 实测: 同一用户 tieba_uid=329706749 而 author_id=3799067486),
// feed 各入口(吧内 business_info.uid/首页卡片 feed_head.business_info.user_id/
// Post/SubPost/SubPostList.author_id)只携带内部编号, 因此名单须换算:
// tieba_uid -> 内部 id 的映射表见下。
//
// 映射的建立(三条通道, 模块不自行发起网络请求):
// ① 数字 ID 主动解析: 用户保存名单时对未映射项借宿主自带通道发起一次
//    GetUserByTiebaUid 查询(TiebaUidCheckReqMsg, cmd 309702, 用户明确触发);
//    响应由 TiebaUidCheckHttpResMsg.decodeInBackGround 的 after 钩子读出
//    (UserData 同时携带两套编号), 写表并持久化(Preferences)。宿主自身同 cmd
//    查询顺带入表。
// ② Profile 响应被动收割: 宿主打开任意用户主页的 Profile 响应(Socket/Http
//    两个响应类)的 proto User 携带全部标识与内部编号, after 钩子收表。
// ③ 帖子详情页 user_list 被动收割: PbPage.DataRes.user_list(List<User>)对
//    本帖出现的每个用户都带完整 User——打开含目标用户回复的帖子即建立其
//    全部标识(name/name_show/portrait/tieba_uid)→编号映射, 是无统一 ID 用户
//    (主页不显示数字 ID)的主要映射来源。
// 未映射(未解析/未路过其内容)的名单项不参与匹配——宁可漏过不误删。
class UserFilter : XposedContext(), IHooker, KeywordFilter {

    override fun key(): String {
        return "user_filter"
    }

    override fun hook() {
        // 逐点隔离: 单个 proto Builder 类缺失(版本漂移)只损失该入口,
        // 不让其余钩点一并失效(与 Purge 的 hookSafely 范式一致)
        fun hookSafely(tag: String, register: () -> Unit) {
            gm.tieba.tabswitch.hookSafely("UserFilter", tag, register)
        }

        // 响应钩子: GetUserByTiebaUid 解析完成即写映射表(与请求发送方解耦,
        // 宿主自身同 cmd 的查询顺带入表亦无害, 只增不减且仅本地存储)
        hookSafely("TiebaUidCheck") {
            hookAfterMethod(
                "com.baidu.tbadk.BdToken.TiebaUidCheckHttpResMsg",
                "decodeInBackGround", Int::class.javaPrimitiveType, ByteArray::class.java
            ) { param ->
                val userData = runCatching { XposedHelpers.callMethod(param.thisObject, "getUserData") }
                    .getOrNull() ?: return@hookAfterMethod
                val tbUid = runCatching { XposedHelpers.getObjectField(userData, "mTiebaUid") as? String }
                    .getOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: return@hookAfterMethod
                val internalId = runCatching { XposedHelpers.getObjectField(userData, "userId") as? String }
                    .getOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: return@hookAfterMethod
                Companion.putMapping(tbUid, internalId)
            }
        }

        // ===== 被动收割: User proto 的全部唯一标识→内部编号入表 =====
        // name/name_show/portrait 全局唯一且可互查(与官方拉黑同源), 入名字表;
        // tieba_uid 入数字表。Profile 响应与帖子页 user_list 两个来源共用本逻辑
        fun harvestUser(user: Any?) {
            if (user == null) return
            val internalId = runCatching { XposedHelpers.getObjectField(user, "id") as? Long }
                .getOrNull()?.takeIf { it != 0L }?.toString() ?: return
            val name = runCatching { XposedHelpers.getObjectField(user, "name") as? String }
                .getOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
            val nameShow = runCatching { XposedHelpers.getObjectField(user, "name_show") as? String }
                .getOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
            val portrait = runCatching { XposedHelpers.getObjectField(user, "portrait") as? String }
                .getOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
                // portrait 字段值可能携带头像缓存参数后缀("tb.1.xxx?t=123", 22.14
                // 实测新旧两条共存), 与名单里手填的纯 portrait 不一致会永不匹配,
                // 统一裁掉 "?" 起的后缀归一化
                ?.substringBefore('?')
            val tbUid = runCatching { XposedHelpers.getObjectField(user, "tieba_uid") as? String }
                .getOrNull()?.trim().takeUnless { it.isNullOrEmpty() }
            if (tbUid != null) Companion.putMapping(tbUid, internalId)
            name?.let { Companion.putNameMapping(it, internalId) }
            if (nameShow != null && nameShow != name) {
                Companion.putNameMapping(nameShow, internalId)
            }
            portrait?.let { Companion.putNameMapping(it, internalId) }
        }

        // Profile 响应(宿主打开任意用户主页即触发)。宿主有两个 Profile 响应类走
        // 不同通道(Socket/Http), 22.14 实测别人主页走 Http, 两个都挂(类缺失只损失
        // 该通道)
        fun harvestProfileUser(param: XC_MethodHook.MethodHookParam) {
            val user = runCatching { XposedHelpers.callMethod(param.thisObject, "GetUser") }
                .getOrNull() ?: return
            harvestUser(user)
        }
        hookSafely("ProfileUserHttp") {
            hookAfterMethod(
                "com.baidu.tieba.person.ProfileHttpResponseMessage",
                "decodeInBackGround", Int::class.javaPrimitiveType, ByteArray::class.java
            ) { param -> harvestProfileUser(param) }
        }
        hookSafely("ProfileUserSocket") {
            hookAfterMethod(
                "com.baidu.tieba.person.ProfileSocketResponseMessage",
                "decodeInBackGroundNeedResult",
                Int::class.javaPrimitiveType, ByteArray::class.java
            ) { param -> harvestProfileUser(param) }
        }

        // 帖子详情页收割: user(楼主, 单 User) + user_list(楼层用户)——对本帖出现的
        // 每个用户都带完整 User, 打开目标用户发布/回复过的帖子即建立映射, 是无
        // 统一 ID 用户的主要映射来源。单条收割失败只跳过该用户, 不影响其余收割
        // 与当轮过滤
        fun harvestPbPageUsers(builder: Any?) {
            runCatching {
                harvestUser(XposedHelpers.getObjectField(builder, "user"))
            }.onFailure { Logger.log("UserFilter: 楼主收割失败 $it") }
            val users = runCatching {
                @Suppress("UNCHECKED_CAST")
                XposedHelpers.getObjectField(builder, "user_list") as? List<*>
            }.getOrNull() ?: return
            for (user in users) {
                runCatching { harvestUser(user) }
                    .onFailure { Logger.log("UserFilter: user_list 收割单条失败 $it") }
            }
        }

        // 吧内帖子(吧页/吧内翻页): 作者内部编号存于 FeedLayout.business_info 键
        // "uid"(回退 "user_id")——宿主长按删帖流程即从该 map 取 uid
        hookSafely("FrsPage") {
            hookBeforeMethod("tbclient.FrsPage.PageData\$Builder",
                "build", Boolean::class.javaPrimitiveType) { param ->
                filterPageData(param.thisObject)
            }
        }
        hookSafely("ThreadList") {
            hookBeforeMethod("tbclient.ThreadList.PageData\$Builder",
                "build", Boolean::class.javaPrimitiveType) { param ->
                filterPageData(param.thisObject)
            }
        }

        // 楼层(过滤前先收割 user_list, 新映射当轮生效)
        hookSafely("PbPage") {
            hookBeforeMethod("tbclient.PbPage.DataRes\$Builder",
                "build", Boolean::class.javaPrimitiveType) { param ->
                harvestPbPageUsers(param.thisObject)
                val ids = blockedIds() ?: return@hookBeforeMethod
                filterProtoBuilderList(param.thisObject, "post_list") { post ->
                    post != null && runCatching {
                        // 主楼豁免与 content_filter 同口径: floor 未下发(null)或为 1 不删。
                        // 块内直接给"命中"原始值, 取反只在外层做一次——曾在块内预取反
                        // (`!blocked`)再被谓词反回去, 净效果变成"命中者留、无辜者删"
                        val floorValue = XposedHelpers.getObjectField(post, "floor") as? Int
                        floorValue != null && floorValue != 1 &&
                            idIn(XposedHelpers.getObjectField(post, "author_id"), ids)
                    }.getOrDefault(false)
                }
            }
        }

        // 楼中楼
        hookSafely("SubPost") {
            hookBeforeMethod("tbclient.SubPost\$Builder",
                "build", Boolean::class.javaPrimitiveType) { param ->
                val ids = blockedIds() ?: return@hookBeforeMethod
                filterProtoBuilderList(param.thisObject, "sub_post_list") { subPost ->
                    subPost != null && runCatching {
                        idIn(XposedHelpers.getObjectField(subPost, "author_id"), ids)
                    }.getOrDefault(false)
                }
            }
        }

        // 楼层回复
        hookSafely("PbFloor") {
            hookBeforeMethod("tbclient.PbFloor.DataRes\$Builder",
                "build", Boolean::class.javaPrimitiveType) { param ->
                val ids = blockedIds() ?: return@hookBeforeMethod
                filterProtoBuilderList(param.thisObject, "subpost_list") { subPost ->
                    subPost != null && runCatching {
                        idIn(XposedHelpers.getObjectField(subPost, "author_id"), ids)
                    }.getOrDefault(false)
                }
            }
        }

        // 首页推荐 feed: 与屏蔽首页求助/回复贴共用 FeedListPipeline 管线(单 hook
        // 多阶段链); 作者编号不在卡片信息 Map(字段 c)而在合成卡载荷内层 feed_head
        // 卡的 business_info 映射(键 user_id), 见 filterHomeFeed
        FeedListPipeline.register { list, _, _ -> filterHomeFeed(list) }
    }

    // ===== 名单换算与映射表 =====

    companion object {
        // 映射持久化键: "tiebaUid:internalId;..." 串(数字键=统一 ID)与
        // "name:internalId;..." 串(非数字键=用户名/昵称/portrait,
        // 来自 Profile 响应与帖子页 user/user_list 被动收割)
        private const val MAP_KEY = "user_filter_id_map"
        private const val NAME_MAP_KEY = "user_filter_name_map"
        private const val MAP_SECTION = ';'
        private const val MAP_PAIR = ':'

        // 映射上限: 正常使用远达不到(等于名单数+宿主偶发 share 查询), 仅防无界增长
        private const val MAX_MAP_ENTRIES = 5000

        // 进程内缓存; Preferences 为唯一持久层, 读取惰性一次。
        // 写方(解析响应钩子线程)与读方(feed 回调的主线程+解码线程)
        // 并发, 必须用并发集合——同原版 mIds 名单的教训, 普通 Map 并发 get 遇扩容
        // 有数据错乱乃至死循环风险
        private val sIdMap = ConcurrentHashMap<String, String>()

        private val sNameMap = ConcurrentHashMap<String, String>()

        @Volatile
        private var sLoaded = false

        private fun parseMapInto(target: ConcurrentHashMap<String, String>, raw: String?) {
            raw?.takeIf { it.isNotEmpty() }?.split(MAP_SECTION)?.forEach { pair ->
                val idx = pair.indexOf(MAP_PAIR)
                if (idx > 0) {
                    val k = pair.substring(0, idx)
                    val v = pair.substring(idx + 1)
                    // 兼容清理: 旧版主页按钮曾把统一ID自映射入表(key==value),
                    // 这类条目换算出的"编号"与 author_id 永不相等, 视为未解析
                    if (k == v) return@forEach
                    target[k] = v
                }
            }
        }

        fun loadMapIfNeeded() {
            if (sLoaded) return
            synchronized(sIdMap) {
                if (sLoaded) return
                parseMapInto(sIdMap, Preferences.getString(MAP_KEY))
                parseMapInto(sNameMap, Preferences.getString(NAME_MAP_KEY))
                sLoaded = true
            }
        }

        private fun putMappingInto(
            map: ConcurrentHashMap<String, String>,
            mapKey: String,
            key: String,
            internalId: String,
            logTag: String
        ) {
            loadMapIfNeeded()
            val normalized = key.lowercase()
            synchronized(map) {
                if (map[normalized] == internalId) return
                // 上限兜底防无界增长: 宿主 share 流程的同 cmd 查询也会顺带入表
                if (map.size >= MAX_MAP_ENTRIES && !map.containsKey(normalized)) return
                map[normalized] = internalId
                Preferences.putString(mapKey, map.entries.joinToString("$MAP_SECTION") {
                    "${it.key}$MAP_PAIR${it.value}"
                })
            }
            // 能走到这里即发生过写入(上方两处 return 已拦截未写场景)
            Logger.log("过滤用户: $logTag $key -> $internalId")
        }

        fun putMapping(tiebaUid: String, internalId: String) {
            putMappingInto(sIdMap, MAP_KEY, tiebaUid, internalId, "ID 解析成功")
        }

        // 名字表写入(用户名/昵称/portrait -> 内部编号): 三者全局唯一且可互查,
        // 由 Profile 响应/帖子页 user+user_list 被动收割建立, 模块零网络请求
        fun putNameMapping(name: String, internalId: String) {
            putMappingInto(sNameMap, NAME_MAP_KEY, name, internalId, "标识映射")
        }

        // 名单保存触发: 对未映射条目借宿主消息通道发起查询, 返回实际发起的数量
        // (0=名单为空或全部已映射)。两类通道:
        // - 统一 ID(纯数字) → GetUserByTiebaUid(cmd 309702)
        // - portrait(tb.1.开头) → Profile(cmd 303012) 的 friend_uid_portrait 查询,
        //   响应经既有 ProfileUserHttp/Socket 收割钩子入表(名字/昵称/portrait/
        //   tieba_uid→编号), 并触发宿主用户卡弹窗(与"分享贴吧号"同窗)
        // - 用户名/昵称无主动查询通道, 不发起(靠被动收割)
        // Titan 可拦截的宿主类按反射构造/调用, 不参与模块编译链接
        fun resolveIds(raw: String): Int {
            loadMapIfNeeded()
            var dispatched = 0
            for (entry in splitIds(raw)) {
                val normalized = entry.lowercase()
                if (normalized.all { it.isDigit() }) {
                    if (sIdMap.containsKey(normalized)) continue
                    dispatched += resolveTiebaUid(normalized)
                } else if (normalized.startsWith("tb.1.") && !sNameMap.containsKey(normalized)) {
                    dispatched += resolveByPortrait(entry.trim())
                }
                // 非数字非 portrait(用户名/昵称): 无主动查询通道, 跳过
            }
            if (dispatched > 0) Logger.log("过滤用户: 已发起 $dispatched 个标识解析")
            return dispatched
        }

        private fun resolveTiebaUid(tbUid: String): Int = runCatching {
            val holder = findClass("com.baidu.tbadk.BdToken.BdTokenController\$m")
                .getDeclaredConstructor().newInstance()
            XposedHelpers.setObjectField(holder, "b", tbUid)
            val req = findClass("com.baidu.tbadk.BdToken.TiebaUidCheckReqMsg")
                .getDeclaredConstructor().newInstance()
            XposedHelpers.callMethod(req, "setTiebaUid", holder)
            val manager = XposedHelpers.callStaticMethod(
                findClass("com.baidu.adp.framework.MessageManager"), "getInstance")
            XposedHelpers.callMethod(manager, "sendMessage", req)
            1
        }.onFailure {
            logBoth("UserFilter: ID 解析请求发起失败 $tbUid -- $it")
        }.getOrDefault(0)

        // portrait 查询: ProfileRequestMessage(cmd 303012) 置 friend_uid_portrait,
        // uid 置 0(查别人非自己)。响应走 ProfileHttpResponseMessage(22.14 主通道),
        // 既有收割钩子入表; 宿主页面若监听同 cmd 亦会展示用户卡(与统一 ID 查询
        // 的分享卡同窗)。is_guest 置 false 避免访客态分支
        private fun resolveByPortrait(portrait: String): Int = runCatching {
            val req = findClass("com.baidu.tieba.person.ProfileRequestMessage")
                .getDeclaredConstructor().newInstance()
            XposedHelpers.callMethod(req, "set_friend_uid_portrait", portrait)
            XposedHelpers.callMethod(req, "set_uid", 0L)
            XposedHelpers.callMethod(req, "set_is_guest", 0)
            val manager = XposedHelpers.callStaticMethod(
                findClass("com.baidu.adp.framework.MessageManager"), "getInstance")
            XposedHelpers.callMethod(manager, "sendMessage", req)
            1
        }.onFailure {
            logBoth("UserFilter: portrait 解析请求发起失败 $portrait -- $it")
        }.getOrDefault(0)

        // 名单(统一 ID/用户名/昵称/portrait)换算为内部 id 集合;
        // 全部未映射时返回 null=不过滤
        fun mappedInternalIds(rawKeywords: List<String>): Set<String>? {
            loadMapIfNeeded()
            val mapped = rawKeywords.mapNotNull { sIdMap[it] ?: sNameMap[it] }.toSet()
            return mapped.ifEmpty { null }
        }

        // 设置值 "/" 切分为 ID 名单(纯文本, trim 忽略空段)
        fun splitIds(raw: String): List<String> =
            raw.split('/').map { it.trim() }.filter { it.isNotEmpty() }

        // 按内部编号反查已收割的标识(展示用): 优先 portrait(官方唯一头像ID),
        // 回退用户名/昵称; 无收割记录返回 null
        fun identifierFor(internalId: String): String? {
            loadMapIfNeeded()
            sNameMap.entries.firstOrNull { it.value == internalId }?.let { return it.key }
            return sIdMap.entries.firstOrNull { it.value == internalId }?.key
        }

        // 按用户名/昵称查其 portrait(获取ID按钮展示用); 未收割过返回 null。
        // 两跳: 名字→编号→portrait
        fun lookupPortraitByName(name: String): String? {
            loadMapIfNeeded()
            val id = sNameMap[name.trim().lowercase()] ?: return null
            var result: String? = null
            sNameMap.entries.firstOrNull {
                it.value == id && it.key.startsWith("tb.1.")
            }?.let { result = it.key }
            return result
        }

        // 名单条目状态(确认框解析结果窗口展示用): entry 原始条目, kind 标识
        // 类型, mappedId 换算出的内部编号(未映射为 null)
        class EntryStatus(val entry: String, val kind: String, val mappedId: String?)

        // 逐条解析名单当前映射状态: 数字项查数字表(统一 ID), 其余查名字表
        // (用户名/昵称/portrait); 未映射项是否还能主动解析见 resolvable
        fun listEntryStatuses(raw: String): List<EntryStatus> {
            loadMapIfNeeded()
            return splitIds(raw).map { rawEntry ->
                val entry = rawEntry.lowercase()
                if (entry.all { it.isDigit() }) {
                    EntryStatus(rawEntry, "统一ID", sIdMap[entry])
                } else {
                    val kind = if (entry.startsWith("tb.1.")) "头像ID" else "用户名"
                    EntryStatus(rawEntry, kind, sNameMap[entry])
                }
            }
        }
    }

    // 名单换算为内部 id 集合; 无有效映射时跳过过滤(同原判空语义)
    private fun blockedIds(): Set<String>? {
        val keywords = getKeywords()
        if (keywords.isEmpty()) return null
        return Companion.mappedInternalIds(keywords)
    }

    // author_id 源头是 proto int64 装箱(Int/Long 均可能), 统一 toString 后精确比较;
    // 未设置/0 视为无 ID 不命中
    private fun idIn(value: Any?, ids: Set<String>): Boolean {
        if (value == null) return false
        if (value is Number && value.toLong() == 0L) return false
        return value.toString() in ids
    }

    // 首页 feed 卡: 作者内部编号在合成卡(common_card)载荷的内层 feed_head 卡
    // (CardHeadUiState) 的 business_info 映射里(键 user_id, 回退旧约定 uid),
    // 22.14 实测见适配记录; 复制式过滤, 返回 null=未修改
    private fun filterHomeFeed(list: List<*>?): List<*>? {
        if (list == null || list.isEmpty()) return null
        val ids = blockedIds() ?: return null
        if (!Fs9Api.resolve()) return null
        // 主循环走 Fs9Api.filterCopy 共享骨架: 无命中返回原列表引用, 管线按
        // 引用回写与旧的"返回 null=未修改"等价; null 条目保留, 口径同旧实现
        return Fs9Api.filterCopy(list) { item ->
            item != null && runCatching {
                val uid = homeAuthorId(item)
                uid != null && idIn(uid, ids)
            }.getOrDefault(false)
        }
    }

    // 首页卡作者编号: 卡自身 Map → 合成卡载荷内层 feed_head 卡的 Map, 逐级取
    // user_id/uid; 全部取不到返回 null=不过该条(宁可漏过不误删)。
    // 独立 feed_head 卡(不经 common_card 合成)同样经卡自身 Map 命中
    private fun homeAuthorId(item: Any): String? {
        for (field in Fs9Api.resolveMapFields(item.javaClass)) {
            val map = runCatching { field.get(item) as? Map<*, *> }.getOrNull() ?: continue
            homeUid(map)?.let { return it }
        }
        val payload = runCatching { Fs9Api.cardPayload(item) }.getOrNull() ?: return null
        val head = Fs9Api.payloadHeadElement(payload) ?: return null
        for (mapField in Fs9Api.resolveMapFields(head.javaClass)) {
            val map = runCatching { mapField.get(head) as? Map<*, *> }.getOrNull() ?: continue
            homeUid(map)?.let { return it }
        }
        return null
    }

    // 作者编号: 22.14 首页 business_info 键为 user_id; uid 为旧约定回退。
    // "get_uid"/"0" 等占位/空值不采信
    private fun homeUid(map: Map<*, *>): String? {
        val value = (map["user_id"] ?: map["uid"])?.toString()?.trim()
        return value?.takeIf { it.isNotEmpty() && it != "0" && it != "get_uid" }
    }

    // 吧页 feed 卡: 作者内部编号存于 FeedLayout.business_info 键 "uid"(回退 "user_id")
    private fun filterPageData(pageData: Any) {
        val ids = blockedIds() ?: return
        filterBusinessInfoFeedList(pageData) { key, value ->
            when (key) {
                "uid", "user_id" -> idIn(value.trim(), ids)
                else -> false
            }
        }
    }
}
