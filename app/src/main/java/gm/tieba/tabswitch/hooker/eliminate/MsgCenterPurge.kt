package gm.tieba.tabswitch.hooker.eliminate

import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.MSG_CENTER_CONTAINER_VIEW
import gm.tieba.tabswitch.logBoth
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

class MsgCenterPurge : XposedContext(), IHooker {

    // 吧广播频道的 forumId 集合, 由数据源层按名称命中后记录, 供渲染层兜底过滤。
    // getForumData 与渲染入口可能不在同一线程, 用并发集合
    private val broadcastForumIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    override fun key(): String {
        return "msg_center_purge"
    }

    override fun hook() {
        try {
            purgeForumChannel()
        } catch (tr: Throwable) {
            logBoth(tr)
        }

        try {
            purgeForumBroadcast()
        } catch (tr: Throwable) {
            logBoth(tr)
        }

        try {
            // 邀您回答屏蔽后未读会累积到消息页角标, 需同时开启免打扰并清空未读
            silenceInviteAnswer()
        } catch (tr: Throwable) {
            logBoth(tr)
        }

        try {
            purgeInviteAnswer()
        } catch (tr: Throwable) {
            logBoth(tr)
        }

        try {
            // 屏蔽项未读切断: 服务端持续下发未读数, 数据层一次性清理不够,
            // 需在提醒数据写入层持续归零(红点/角标的唯一来源)
            blockPurgedUnreadCounts()
        } catch (tr: Throwable) {
            logBoth(tr)
        }

        try {
            purgeNotifyEntranceRow()
        } catch (tr: Throwable) {
            logBoth(tr)
        }
    }

    // 22.11: 通知页行列表由 MsgTabNotifyItemAdapter.A(int, List×4) 一次性灌入,
    // 其中"吧友互助"行数据为 fib(boolean/int/long 三字段数据类), 据此过滤
    private fun purgeNotifyEntranceRow() {
        val adapterClass = findClass(MSG_TAB_NOTIFY_ITEM_ADAPTER)
        val setter = adapterClass.declaredMethods.firstOrNull { method ->
            method.parameterTypes.size == 5 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[1] == List::class.java
        } ?: return
        hookBeforeMethod(setter) { param ->
            for (i in 1 until param.args.size) {
                // 复制式过滤: 不原地 removeAll(入参可能不可变或被调用方复用)
                val list = param.args[i] as? List<*> ?: continue
                param.args[i] = Fs9Api.filterCopy(list) { item -> item != null && isEntranceData(item) }
            }
        }
    }

    // 吧广播: 通知页系统通知列表中的"吧广播"条目(ImMessageCenterShowItemData)。
    // MsgNotifyTabModel.getData() 继承自 ImBaseMessageCenterModel 且方法名未混淆,
    // 过滤其返回列表即可, 条目标题为资源字符串 msg_tieba_broadcast("吧广播"),
    // 另以 ownerName == "5"(吧广播图文feed) 兜底
    private fun purgeForumBroadcast() {
        val modelClass = findClass(MSG_NOTIFY_TAB_MODEL)
        var clazz: Class<*>? = modelClass
        var getDataMethod: Method? = null
        while (clazz != null && getDataMethod == null) {
            getDataMethod = clazz.declaredMethods.firstOrNull { method ->
                "getData" == method.name && method.parameterTypes.isEmpty()
            }
            clazz = clazz.superclass
        }
        val method = getDataMethod ?: return

        val titles = broadcastTitles()
        hookAfterMethod(method) { param ->
            try {
                if (!modelClass.isInstance(param.thisObject)) return@hookAfterMethod
                val list = param.result as? List<*> ?: return@hookAfterMethod
                if (list.isEmpty()) return@hookAfterMethod
                // 先判命中再分配(与旧实现逐字等价): 无广播命中时原列表原样返回,
                // null 条目随原列表保留——剔除 null 是"伴随命中重建列表"的伴生
                // 行为而非无条件发生, 故预扫不可省
                var hasBroadcast = false
                for (item in list) {
                    if (item != null && isBroadcastItem(item, titles)) {
                        hasBroadcast = true
                        break
                    }
                }
                if (!hasBroadcast) return@hookAfterMethod
                param.result = Fs9Api.filterCopy(list) { item ->
                    item == null || isBroadcastItem(item, titles)
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun isBroadcastItem(item: Any, titles: HashSet<String>): Boolean {
        val showName = XposedHelpers.getObjectField(item, "friendNameShow") as? String
        if (showName != null && titles.contains(showName)) {
            return true
        }
        val ownerName = XposedHelpers.getObjectField(item, "ownerName") as? String
        return BROADCAST_OWNER_NAME == ownerName
    }

    private fun broadcastTitles(): HashSet<String> {
        val titles = HashSet<String>()
        broadcastTitle()?.let { titles.add(it) }
        titles.add(BROADCAST_TITLE)
        return titles
    }

    private fun broadcastTitle(): String? {
        return try {
            val context = getContext()
            val id = context.resources.getIdentifier("msg_tieba_broadcast", "string", context.packageName)
            if (id != 0) context.getString(id) else null
        } catch (ignored: Throwable) {
            null
        }
    }

    // 吧广播(聊天频道): 服务端下发 ForumListData(channel_list)后, MsgCenterContainerModel
    // 把其中每个 MsgTabForumData 都映射为 NavigationData(type == 3) 的频道条目及对应页面。
    // 用户在"管理"中添加的频道与吧广播走同一列表, 且映射后的 NavigationData 仅剩
    // dataId/type/isSelected, 名称已丢失无法区分。若在渲染层按 type == 3 全量过滤,
    // 会把用户添加的频道一并屏蔽(4.8.6 回归), 因此:
    //   数据源层: hook ForumListData.getForumData(), 按 forumName 只剔除吧广播并记录 forumId
    //   渲染层兜底: 仅剔除 type == 3 且 dataId 命中已记录 forumId 的条目
    private fun purgeForumChannel() {
        try {
            purgeForumChannelData()
        } catch (tr: Throwable) {
            logBoth(tr)
        }

        try {
            purgeForumChannelView()
        } catch (tr: Throwable) {
            logBoth(tr)
        }
    }

    private fun purgeForumChannelData() {
        val dataClass = findClass(FORUM_LIST_DATA)
        val getter = dataClass.declaredMethods.firstOrNull { method ->
            "getForumData" == method.name && method.parameterTypes.isEmpty()
        } ?: return
        val titles = broadcastTitles()
        hookAfterMethod(getter) { param ->
            try {
                val list = param.result as? List<*> ?: return@hookAfterMethod
                if (list.isEmpty()) return@hookAfterMethod
                // filterCopy 无命中即返回原列表不写回(等价原"先扫命中再分配"口径);
                // 谓词内的 forumId 记录是剔除动作的伴生侧效, 仅对命中条目执行
                param.result = Fs9Api.filterCopy(list) { item ->
                    if (item == null) return@filterCopy true
                    val hit = isBroadcastChannelData(item, titles)
                    if (hit) broadcastForumIds.add(XposedHelpers.getLongField(item, "forumId"))
                    hit
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    // MsgTabForumData 的 getter/字段名未混淆, 名称读取失败时不判定为吧广播
    private fun isBroadcastChannelData(item: Any, titles: HashSet<String>): Boolean {
        val name = try {
            XposedHelpers.callMethod(item, "getForumName") as? String
        } catch (ignored: Throwable) {
            null
        } ?: return false
        return titles.contains(name)
    }

    private fun purgeForumChannelView() {
        val renderMethods = findClass(MSG_CENTER_CONTAINER_VIEW).declaredMethods.filter { method ->
            method.parameterTypes.size == 1 && method.parameterTypes[0] == List::class.java &&
                method.returnType == Void.TYPE && !method.isSynthetic
        }
        for (method in renderMethods) {
            hookBeforeMethod(method) { param ->
                try {
                    if (broadcastForumIds.isEmpty()) return@hookBeforeMethod
                    val list = param.args[0] as? List<*> ?: return@hookBeforeMethod
                    val filtered = ArrayList<Any>(list.size)
                    for (item in list) {
                        if (item != null && isBroadcastChannel(item)) {
                            continue
                        }
                        if (item != null) {
                            filtered.add(item)
                        }
                    }
                    if (filtered.size != list.size) {
                        param.args[0] = filtered
                    }
                } catch (ignored: Throwable) {
                }
            }
        }
    }

    private fun isBroadcastChannel(item: Any): Boolean {
        val typeField = navTypeField(item.javaClass) ?: return false
        if (XposedHelpers.getIntField(item, typeField.name) != NAV_TYPE_FORUM_CHANNEL) {
            return false
        }
        val dataIdField = navDataIdField(item.javaClass) ?: return false
        return broadcastForumIds.contains(XposedHelpers.getLongField(item, dataIdField.name))
    }

    // NavigationData(混淆类)的字段: long dataId, int type, boolean isSelected, 其中 int 型字段唯一
    // 按类缓存查找结果: 该查询在通知列表灌入回调中逐条目触发, 不缓存则每项都全字段扫描
    private val navFieldCache = HashMap<String, Field?>()

    private fun navTypeField(clazz: Class<*>): Field? {
        return navField(clazz, Int::class.javaPrimitiveType)
    }

    private fun navDataIdField(clazz: Class<*>): Field? {
        return navField(clazz, Long::class.javaPrimitiveType)
    }

    private fun navField(clazz: Class<*>, type: Class<*>?): Field? {
        val key = clazz.name + "#" + (type?.name ?: "null")
        synchronized(navFieldCache) {
            if (navFieldCache.containsKey(key)) return navFieldCache[key]
        }
        // 沿父类链查找, 字段上移到父类时不至于静默失效
        var result: Field? = null
        var current: Class<*>? = clazz
        loop@ while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                if (!Modifier.isStatic(field.modifiers) && !field.isSynthetic &&
                    field.type == type
                ) {
                    result = field
                    break@loop
                }
            }
            current = current.superclass
        }
        synchronized(navFieldCache) { navFieldCache[key] = result }
        return result
    }

    // 邀您回答: 入口卡片的可见性由 MsgTabNotifyModel 的 BehaviorSubject 驱动
    // 1) 构造时读取偏好 "msg_tab_entrance_invitation_answer" 初始化
    // 2) 运行时由内部监听器 MsgTabNotifyModel$b.onMessage(服务端消息 cmd 2921726) 置为 true
    private fun purgeInviteAnswer() {
        try {
            val listenerClass = findClass(MSG_TAB_NOTIFY_MODEL + "\$b")
            listenerClass.declaredMethods.filter { method ->
                "onMessage" == method.name && method.parameterTypes.size == 1
            }.forEach { method ->
                hookBeforeMethod(method) { param -> param.setResult(null) }
            }
        } catch (ignored: Throwable) {
        }

        hookAfterConstructor(findClass(MSG_TAB_NOTIFY_MODEL)) { param ->
            try {
                // 静默只执行一次: MsgTabNotifyModel 每次进入消息页都会重建,
                // 重复写宿主 SP 并向全总线派发已读消息(幂等但重复触发宿主消息链)
                if (!sInviteAnswerSilenced) {
                    sInviteAnswerSilenced = true
                    silenceInviteAnswer()
                }
                val model = param.thisObject
                for (field in model.javaClass.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    field.isAccessible = true
                    val subject = field.get(model) ?: continue
                    val latest = getSubjectLatest(subject) ?: continue
                    if (latest is Boolean) {
                        // 12.74: BehaviorSubject 直接持有 Boolean 可见性
                        if (latest) {
                            XposedHelpers.callMethod(subject, "onNext", false)
                        }
                        break
                    } else if (latest is List<*>) {
                        // 22.11: BehaviorSubject 持有 List<fib>, fib 仅含 boolean(可见性)/int(未读)/long(时间)
                        // 三字段, 据此区分入口卡片数据与其他消息列表
                        val first = latest.firstOrNull() ?: continue
                        if (!isEntranceData(first)) continue
                        for (item in latest) {
                            if (item != null) {
                                setEntranceVisible(item, false)
                            }
                        }
                        XposedHelpers.callMethod(subject, "onNext", latest)
                        break
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    // 判定/目标字段按类缓存(含 null 负缓存, 哨兵占位): 判定结果与字段布局是
    // 类级稳定的, 通知列表灌入逐条目触发, 每次重扫 declaredFields 纯属浪费
    private val sEntranceDataCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>()
    private val sEntranceFieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    private val sNoEntranceField = Any()

    // 22.11 fib 入口卡片数据类识别: 去除titan注入字段后仅剩 boolean/int/long 三字段
    private fun isEntranceData(item: Any): Boolean =
        sEntranceDataCache.computeIfAbsent(item.javaClass) { clazz ->
            var booleanCount = 0
            var intCount = 0
            var longCount = 0
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) continue
                if (field.name == "\$ic" || field.name == "\$fh") continue
                when (field.type) {
                    Boolean::class.javaPrimitiveType -> booleanCount++
                    Int::class.javaPrimitiveType -> intCount++
                    Long::class.javaPrimitiveType -> longCount++
                    else -> return@computeIfAbsent false
                }
            }
            booleanCount == 1 && intCount == 1 && longCount == 1
        }

    private fun setEntranceVisible(item: Any, visible: Boolean) {
        val field = sEntranceFieldCache.computeIfAbsent(item.javaClass) { clazz ->
            clazz.declaredFields.firstOrNull { f ->
                f.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(f.modifiers)
            }?.also { it.isAccessible = true } ?: sNoEntranceField
        }
        if (field === sNoEntranceField) return
        try {
            (field as java.lang.reflect.Field).set(item, visible)
        } catch (ignored: Throwable) {
        }
    }

    // 屏蔽项未读切断(通知Tab红点的数据源头, 22.13 链路):
    // 1) 邀您回答: 服务端推送(cjb.c)每次都把 new_invite_num 重新写入 SP 并经
    //    qq6.L() 装入 NewsNotifyMessage, rq6.E 据此累计消息提醒——数据层一次性
    //    清零无效, 必须在写入层归零。setMsgInvitation(int) 为未混淆稳定锚点,
    //    宿主内仅 qq6 构建通知数据时调用, 置 0 不影响其他通道(点赞/回复等
    //    独立字段)。入口卡片可见性由 SP/BehaviorSubject 驱动(purgeInviteAnswer
    //    已处理), 与此互不干扰
    // 2) 吧广播: 消息列表过滤(purgeForumBroadcast)只挡渲染, 其 -8 聚合条目的
    //    unread_count 仍被 rq6.G 累计进 broadcastCount, 通知 Tab 红点判定
    //    (broadcastCount>0 && 系统通知开关)随之点亮。setBroadcastCount(int)
    //    为未混淆稳定锚点, 宿主内仅 rq6.d0() 派发提醒时调用, 置 0 后红点判定
    //    恒 false; notificationCount(官方账号通知)为独立字段不受影响
    private fun blockPurgedUnreadCounts() {
        // 邀您回答: NewsNotifyMessage.setMsgInvitation(int) 参数置 0
        runCatching {
            val newsClass = findClass(NEWS_NOTIFY_MESSAGE)
            val setter = newsClass.declaredMethods.firstOrNull { method ->
                "setMsgInvitation" == method.name &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (setter == null) {
                logBoth("MsgCenterPurge: setMsgInvitation 未定位, 邀您回答未读可能持续点亮红点")
            } else {
                hookBeforeMethod(setter) { param -> param.args[0] = 0 }
            }
        }.onFailure { logBoth("MsgCenterPurge: setMsgInvitation hook 失败 -- ${it.javaClass.simpleName}") }

        // 吧广播: NewsRemindMessage.setBroadcastCount(int) 参数置 0
        runCatching {
            val remindClass = findClass(NEWS_REMIND_MESSAGE)
            val setter = remindClass.declaredMethods.firstOrNull { method ->
                "setBroadcastCount" == method.name &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (setter == null) {
                logBoth("MsgCenterPurge: setBroadcastCount 未定位, 吧广播未读可能持续点亮红点")
            } else {
                hookBeforeMethod(setter) { param -> param.args[0] = 0 }
            }
        }.onFailure { logBoth("MsgCenterPurge: setBroadcastCount hook 失败 -- ${it.javaClass.simpleName}") }
    }

    // 开启邀您回答的消息免打扰(key_question_msg_no_remind)并清空其未读数,
    // 等同于在邀您回答页内手动开启免打扰的效果, 否则未读会累积到消息页角标。
    // 注: 服务端推送会重新写入未读数, 红点的持续归零由 blockPurgedUnreadCounts
    // 在写入层完成, 此处的一次性清理仅覆盖入口卡片可见性 SP 与既有消息总线
    private fun silenceInviteAnswer() {
        try {
            val helperClass = findClass(SHARED_PREF_HELPER)
            val helper = XposedHelpers.callStaticMethod(helperClass, "getInstance")
            XposedHelpers.callMethod(helper, "putBoolean", PREF_NO_REMIND, true)
            val unreadKey = XposedHelpers.callStaticMethod(
                helperClass, "getSharedPrefKeyWithAccount", PREF_UNREAD_NUM
            )
            XposedHelpers.callMethod(helper, "putInt", unreadKey, 0)
            dispatchInviteAnswerReadMessage()
        } catch (ignored: Throwable) {
        }
    }

    // 派发邀您回答已读消息(cmd 2008024), 通知角标等重新计算
    private fun dispatchInviteAnswerReadMessage() {
        try {
            val messageManager = XposedHelpers.callStaticMethod(
                findClass(MESSAGE_MANAGER), "getInstance"
            )
            val message = XposedHelpers.newInstance(
                findClass(CUSTOM_RESPONSE_MESSAGE), INVITE_ANSWER_READ_CMD
            )
            XposedHelpers.callMethod(messageManager, "dispatchResponsedMessage", message)
        } catch (ignored: Throwable) {
        }
    }

    // BehaviorSubject(混淆类)内部持有 rx.subjects.SubjectSubscriptionManager, 其类名与
    // getLatest() 未被混淆, 以此在未知包装类中读取当前值, 避免硬编码混淆名。
    // 整体防护: 反射失败返回 null 即跳过该字段继续扫描, 不能中断调用方的字段循环
    private fun getSubjectLatest(subject: Any): Any? = runCatching {
        val ssmField = subject.javaClass.declaredFields.firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) && SUBJECT_SUBSCRIPTION_MANAGER == field.type.name
        } ?: return null
        ssmField.isAccessible = true
        val ssm = ssmField.get(subject) ?: return null
        XposedHelpers.callMethod(ssm, "getLatest")
    }.getOrNull()

    companion object {
        // 邀您回答静默的进程内幂等标记(见 hookAfterConstructor 内注释)
        @Volatile
        private var sInviteAnswerSilenced = false

        // 消息页容器: 类名收编至 hooker/HostAnchors.kt（与 MsgCenterTab 共用）
        private const val MSG_TAB_NOTIFY_MODEL =
            "com.baidu.tieba.immessagecenter.msgtab.ui.model.MsgTabNotifyModel"
        private const val MSG_TAB_NOTIFY_ITEM_ADAPTER =
            "com.baidu.tieba.immessagecenter.msgtab.ui.adapter.MsgTabNotifyItemAdapter"
        private const val MSG_NOTIFY_TAB_MODEL =
            "com.baidu.tieba.immessagecenter.im.model.MsgNotifyTabModel"
        private const val FORUM_LIST_DATA =
            "com.baidu.tieba.immessagecenter.msgtab.data.ForumListData"
        private const val SUBJECT_SUBSCRIPTION_MANAGER = "rx.subjects.SubjectSubscriptionManager"
        private const val NAV_TYPE_FORUM_CHANNEL = 3
        private const val BROADCAST_TITLE = "吧广播"
        private const val BROADCAST_OWNER_NAME = "5"
        private const val SHARED_PREF_HELPER = "com.baidu.tbadk.core.sharedPref.SharedPrefHelper"
        private const val MESSAGE_MANAGER = "com.baidu.adp.framework.MessageManager"
        private const val CUSTOM_RESPONSE_MESSAGE =
            "com.baidu.adp.framework.message.CustomResponsedMessage"
        private const val NEWS_NOTIFY_MESSAGE = "com.baidu.tbadk.data.NewsNotifyMessage"
        private const val NEWS_REMIND_MESSAGE =
            "com.baidu.tbadk.coreExtra.messageCenter.NewsRemindMessage"
        private const val PREF_NO_REMIND = "key_question_msg_no_remind"
        private const val PREF_UNREAD_NUM = "msg_tab_entrance_invitation_answer_unread_num"
        private const val INVITE_ANSWER_READ_CMD = 2008024
    }
}
