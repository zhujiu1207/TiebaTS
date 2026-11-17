package gm.tieba.tabswitch.dao

import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.bridge.XposedHelpers
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Preferences.getLikeForum
import gm.tieba.tabswitch.dao.Preferences.getLikeForumBduss
import gm.tieba.tabswitch.dao.Preferences.putLikeForum
import gm.tieba.tabswitch.dao.Preferences.putLikeForumBduss
import gm.tieba.tabswitch.logBoth

object Adp : XposedContext() {
    // 跨线程可见: 主线程的 AccountData getter 钩子写入, FollowFilter 的后台
    // 解码线程/fetch 线程读取, 不加 @Volatile 时后台线程可能长期读到旧值 null,
    // 导致关注列表缓存恒判过期(fail-open 不过滤, 但缓存形同虚设)
    @Volatile var BDUSS: String? = null
    @Volatile var tbs: String? = null
    @Volatile var account: String? = null

    fun initializeAdp() {
        try {
            refreshAccountData()
        } catch (tr: Throwable) {
            logBoth(tr)
        }
        try {
            refreshCache()
        } catch (tr: Throwable) {
            // 22.11 移除了 tbclient.ForumRecommend.DataRes，like_forum 缓存降级不可用
            logBoth(tr)
        }
    }

    private fun refreshAccountData() {
        // 未登录阶段 getter 返回 null, 用 as? 避免每次回调 NPE 刷日志
        hookAfterMethod("com.baidu.tbadk.core.data.AccountData", "getBDUSS") { param ->
            BDUSS = param.result as? String
        }
        hookAfterMethod("com.baidu.tbadk.core.data.AccountData", "getTbs") { param ->
            tbs = param.result as? String
        }
        hookAfterMethod("com.baidu.tbadk.core.data.AccountData", "getAccount") { param ->
            account = param.result as? String
        }
    }

    private fun refreshCache() {
        // 22.11 移除了 tbclient.ForumRecommend.DataRes: 类不存在时直接跳过,
        // 避免每个进程启动都抛一次 ClassNotFoundError(噪音 + 反射开销);
        // like_forum 缓存由 FollowFilter 的 HTTP 兜底刷新
        if (XposedHelpers.findClassIfExists("tbclient.ForumRecommend.DataRes\$Builder", sClassLoader) == null) {
            return
        }
            hookBeforeMethod("tbclient.ForumRecommend.DataRes\$Builder",
                "build", Boolean::class.javaPrimitiveType) { param ->
                val forums: MutableSet<String?> = HashSet()
                // proto String 对象字段未设置时为 null, 逐条判空避免 NPE 刷日志
                val likeForumList = XposedHelpers.getObjectField(param.thisObject, "like_forum") as? List<*>
                likeForumList?.forEach { item ->
                    if (item == null) return@forEach
                    (XposedHelpers.getObjectField(item, "forum_name") as? String)?.let { forums.add(it) }
                }
                // 与现有缓存一致时跳过落盘: 该钩点在每条推荐响应上都触发, 内容
                // 几乎从不变化, 无条件写会每次白付两次 SP 异步磁盘写+监听器派发
                val bduss = BDUSS
                // 账号未就绪(推荐响应先于 AccountData.getBDUSS 钩到值)不落盘:
                // 否则会把上一会话写入的有效账号戳覆盖成 null, FollowFilter 的
                // isCacheStale 从此判过期, 这份有效缓存作废(虽有 HTTP 兜底自愈,
                // 但属白丢)
                if (bduss == null) {
                    return@hookBeforeMethod
                }
                if (forums == getLikeForum() && bduss == getLikeForumBduss()) {
                    return@hookBeforeMethod
                }
                putLikeForum(forums)
                // 同步账号戳: 否则 FollowFilter 的 isCacheStale 永远判此缓存过期,
                // 这份有效缓存对关注过滤完全不可用
                putLikeForumBduss(bduss)
            }
    }
}
