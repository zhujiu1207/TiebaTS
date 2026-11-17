package gm.tieba.tabswitch.hooker.auto

import android.os.Bundle
import gm.tieba.tabswitch.bridge.XposedBridge
import gm.tieba.tabswitch.XposedContext
import gm.tieba.tabswitch.dao.Adp
import gm.tieba.tabswitch.dao.Preferences.getIsSigned
import gm.tieba.tabswitch.dao.Preferences.getSignAttempt
import gm.tieba.tabswitch.dao.Preferences.putLikeForum
import gm.tieba.tabswitch.dao.Preferences.putLikeForumBduss
import gm.tieba.tabswitch.dao.Preferences.putSignAttempt
import gm.tieba.tabswitch.dao.Preferences.putSignDate
import gm.tieba.tabswitch.hooker.IHooker
import gm.tieba.tabswitch.hooker.LIKE_URL
import gm.tieba.tabswitch.hooker.MAIN_TAB_ACTIVITY
import gm.tieba.tabswitch.logBoth
import gm.tieba.tabswitch.widget.TbToast
import gm.tieba.tabswitch.widget.TbToast.Companion.showTbToast
import java.net.URLEncoder
import kotlin.concurrent.thread

class AutoSign : XposedContext(), IHooker {

    companion object {
        // LIKE_URL(关注列表端点)收编至 hooker/HostAnchors.kt（与 FollowFilter 共用）

        //获取用户的tbs
        private const val TBS_URL = "https://tieba.baidu.com/dc/common/tbs"

        //贴吧签到接口
        private const val SIGN_URL = "https://c.tieba.baidu.com/c/c/forum/sign"
    }

    private val mFollow = mutableListOf<String>()
    private val mSuccess = mutableListOf<String>()
    private var mTbs: String? = null
    private var mFollowNum = 0
    // 防止 Activity 重建导致多个签到线程并发操作状态列表
    private val mRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun key(): String {
        return "auto_sign"
    }

    override fun hook() {
        hookAfterMethod(
            MAIN_TAB_ACTIVITY,
            "onCreate", Bundle::class.java
        ) { _ ->
            if (!getIsSigned() && mRunning.compareAndSet(false, true)) {
                // 当日限次(持久化): 失败后允许下次冷启动/重建重试, 但当日最多 3 次,
                // 避免"某吧永远签不上 + 用户频繁重建页面"演变成请求风暴;
                // 计数落 SP, 杀进程重开不能绕过(与"当日 3 次"语义一致)
                val cal = java.util.Calendar.getInstance()
                val day = cal[java.util.Calendar.DAY_OF_YEAR]
                val year = cal[java.util.Calendar.YEAR]
                val attempts = getSignAttempt(day, year)
                if (attempts >= 3) {
                    mRunning.set(false)
                    return@hookAfterMethod
                }
                putSignAttempt(day, year, attempts + 1)
                thread {
                    try {
                        val result = main(Adp.BDUSS)
                        if (result.fullySigned) {
                            putSignDate(day, year)
                        }
                        // 关注列表在 getFollow 时已缓存(不再依赖全部签到成功)
                        runOnUiThread { showTbToast(result.message, TbToast.LENGTH_SHORT) }
                    } catch (tr: Throwable) {
                        // main 内部各环节已有局部 catch, 这里兜住未预料的 Error/异常
                        // (如 SP 异常), 否则会沿 UncaughtExceptionHandler 崩掉宿主
                        logBoth("AutoSign 未预期异常 -- $tr")
                        runOnUiThread { showTbToast("自动签到失败：${tr.message}", TbToast.LENGTH_SHORT) }
                    } finally {
                        mRunning.set(false)
                    }
                }
            }
        }
    }

    // 签到结果结构化: "全部成功"与否由字段判定, 不再依赖展示文案后缀
    // (endsWith 判定在文案改动时即破坏 putSignDate 持久化)
    private class SignResult(val fullySigned: Boolean, val message: String)

    private fun main(BDUSS: String?): SignResult {
        if (BDUSS == null) return SignResult(false, "暂未获取到 BDUSS")

        // 每轮签到前重置状态，避免 Activity 重建重入时残留旧数据
        mFollow.clear()
        mSuccess.clear()
        mFollowNum = 0
        mTbs = null

        AutoSignHelper.setCookie(BDUSS)
        getTbs()
        // tbs 拿不到时签到请求的 sign 服务端必拒, 别对每个吧白打 3 轮注定失败的请求
        if (mTbs == null) return SignResult(false, "获取tbs失败，签到未执行")
        // 获取关注列表失败(断网/接口改版)时 mFollowNum 保持 0, 若继续执行
        // 会被误判为"0 个吧全部签到成功"并锁死当日, 必须显式失败以便下次启动重试
        if (!getFollow()) return SignResult(false, "获取关注贴吧列表失败，签到未执行")
        if (mFollowNum == 0) return SignResult(false, "未关注任何贴吧，无需签到")
        runSign()

        val failNum = mFollowNum - mSuccess.size
        val result = "共${mFollowNum}个吧 - 成功：${mSuccess.size} - 失败：$failNum"
        logBoth(result)
        return if (failNum == 0) {
            SignResult(true, "共${mFollowNum}个吧 - 全部签到成功")
        } else {
            SignResult(false, result)
        }
    }

    private fun getTbs(force: Boolean = false) {
        // force=false 时可用 Adp.tbs 缓存; 重试轮必须强制刷新, 否则过期的 tbs
        // 会被 3 轮重试原样重发(每次都注定失败, 重试退化为同一请求重复 3 遍)
        if (!force) {
            mTbs = Adp.tbs
            if (mTbs != null) return
        }
        try {
            val jsonObject = AutoSignHelper.get(TBS_URL)
            if ("1" == jsonObject.getString("is_login")) {
                logBoth("获取tbs成功")
                mTbs = jsonObject.getString("tbs")
            } else logBoth("获取tbs失败 -- $jsonObject")
        } catch (e: Exception) {
            // 刷新失败保留旧值: 有旧 tbs 继续用(服务端可能仍接受), 不至于置 null
            logBoth("获取tbs部分出现错误 -- $e")
        }
    }

    private fun getFollow(): Boolean {
        return try {
            val jsonObject = AutoSignHelper.get(LIKE_URL)
            logBoth("获取贴吧列表成功")

            val jsonArray = jsonObject.getJSONObject("data").getJSONArray("like_forum")

            // 获取用户所有关注的贴吧
            // 逐条均用 opt 系列: 任一条目缺键/类型漂移只跳过该吧,
            // 否则单条脏数据会让当日全部签到被放弃
            for (i in 0 until jsonArray.length()) {
                val forumObject = jsonArray.optJSONObject(i) ?: continue
                val forumName = forumObject.optString("forum_name").takeIf { it.isNotEmpty() } ?: continue

                // 缺 is_sign 键时宽松视为已签(与 else 分支语义一致)
                when (forumObject.optString("is_sign")) {
                    "0" -> mFollow.add(forumName)
                    else -> mSuccess.add(forumName)
                }
            }

            // 计数必须取实际入列的条目数(mFollow+mSuccess), 而非 jsonArray.length():
            // 脏条目被 continue 跳过后两头都不进, 若计入总数则 failNum 永不为 0,
            // putSignDate 永不落盘 → 当日每次冷启动重复全量签到
            mFollowNum = mFollow.size + mSuccess.size

            // 获取到关注列表即缓存(未签到+已签到合集), 供只推荐已关注的吧使用;
            // 同步账号戳, 否则 FollowFilter 会把这份有效缓存判为过期而弃用重拉
            if (mFollowNum > 0) {
                putLikeForum(HashSet(mFollow + mSuccess))
                putLikeForumBduss(Adp.BDUSS)
            }
            true
        } catch (e: Exception) {
            logBoth("获取贴吧列表部分出现错误 -- $e")
            false
        }
    }

    private fun runSign() {
        // 当执行 3 轮所有贴吧还未签到成功就结束操作
        var roundCount = 3

        while (mSuccess.size < mFollowNum && roundCount > 0) {

            mFollow.removeAll { forumName ->
                // 单个吧的签到异常只影响该吧，不中断后续贴吧
                try {
                    val encodedS = URLEncoder.encode(forumName, "UTF-8")
                    val body = "kw=$encodedS&tbs=$mTbs&sign=${AutoSignHelper.enCodeMd5("kw=${forumName}tbs=${mTbs}tiebaclient!!!")}"

                    val post = AutoSignHelper.post(SIGN_URL, body)
                    when (post.optString("error_code", "-1")) {
                        "0" -> {
                            mSuccess.add(forumName)
                            logBoth("$forumName: 签到成功")
                            true
                        }
                        // 已签过(160002): 幂等成功。否则索引数据滞后/存在永远签不上的吧时,
                        // failNum 永不为 0 → putSignDate 不执行 → 当日每次冷启动重复全量签到
                        "160002" -> {
                            mSuccess.add(forumName)
                            logBoth("$forumName: 今日已签")
                            true
                        }
                        else -> {
                            logBoth("$forumName: 签到失败")
                            false
                        }
                    }
                } catch (e: Exception) {
                    logBoth("$forumName: 签到部分出现错误 -- $e")
                    false
                }
            }

            // 先减轮次再判断: 末轮仍有失败时不再退避重试,
            // 省一次 2.5s 等待与 tbs 网络请求
            roundCount--
            if (roundCount > 0 && mSuccess.size != mFollowNum) {
                Thread.sleep(2500)
                getTbs(force = true)
            }
        }
    }
}
