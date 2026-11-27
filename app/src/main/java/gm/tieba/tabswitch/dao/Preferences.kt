package gm.tieba.tabswitch.dao

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

object Preferences {
    private lateinit var sTsPreferences: SharedPreferences
    private lateinit var sTsConfig: SharedPreferences

    fun init(context: Context) {
        sTsPreferences = context.getSharedPreferences("TS_preferences", Context.MODE_PRIVATE)
        sTsConfig = context.getSharedPreferences("TS_config", Context.MODE_PRIVATE)
    }

    fun getAll(): Map<String, *> = sTsPreferences.all

    // 监听 TS_preferences 变化(如 Logger 的开关缓存失效); 注册失败静默,
    // 监听方自行决定降级行为
    fun registerOnSharedPreferenceChangeListener(
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        sTsPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun remove(key: String) {
        sTsPreferences.edit().remove(key).apply()
    }

    fun putBoolean(key: String, value: Boolean) {
        sTsPreferences.edit().putBoolean(key, value).apply()
    }

    @JvmStatic
    fun getBoolean(key: String): Boolean = sTsPreferences.getBoolean(key, false)

    fun putString(key: String, value: String) {
        sTsPreferences.edit().putString(key, value).apply()
    }

    fun getString(key: String): String? = sTsPreferences.getString(key, null)

    // 整集合覆写: 供配置弹窗以勾选结果整体替换
    fun putStringSet(key: String, values: Set<String>) {
        sTsPreferences.edit().putStringSet(key, values).apply()
    }

    fun getStringSet(key: String): Set<String> = sTsPreferences.getStringSet(key, emptySet()) ?: emptySet()

    // Config 通用读写: DeobfuscationHelper 的反混淆状态键(deobfs_version/
    // deobfs_fail_count/deobfs_module_id/deobfs_last_run 等)此前逐方法直开
    // TS_config, 收敛为 DAO 单点。int 写入统一 commit: 反混淆计数/版本标记
    // 紧随进程重启的场景(saveAndRestart/扫描收尾)不允许 apply 异步丢失
    fun getConfigString(key: String, defValue: String? = null): String? =
        sTsConfig.getString(key, defValue)

    fun getConfigInt(key: String, defValue: Int): Int = sTsConfig.getInt(key, defValue)

    fun putConfigString(key: String, value: String) {
        sTsConfig.edit().putString(key, value).commit()
    }

    fun putConfigInt(key: String, value: Int) {
        sTsConfig.edit().putInt(key, value).commit()
    }

    // Config
    fun putEULAAccepted() {
        sTsConfig.edit().putBoolean("EULA", true).apply()
    }

    fun getIsEULAAccepted(): Boolean = sTsConfig.getBoolean("EULA", false)

    fun putAutoSignEnabled() {
        sTsConfig.edit().putBoolean("auto_sign", true).apply()
    }

    fun getIsAutoSignEnabled(): Boolean = sTsConfig.getBoolean("auto_sign", false)

    // 默认 apply(): 冷启动主线程等调用方只表达"记录状态", 无需为同步落盘阻塞;
    // durable = true 用于紧随进程重启的写入(saveAndRestart 前置标记/反混淆收尾
    // 签名), commit 保证重启杀进程前已落盘, apply 的异步写可能丢失导致重启后
    // 误判 dex 变更再走一遍反混淆
    fun putSignature(i: Int, durable: Boolean = false) {
        val editor = sTsConfig.edit().putInt("signature", i)
        if (durable) editor.commit() else editor.apply()
    }

    fun getSignature(): Int = sTsConfig.getInt("signature", 0)

    fun putLikeForum(follow: Set<String?>?) {
        sTsConfig.edit().putStringSet("like_forum", follow).apply()
    }

    fun getLikeForum(): Set<String>? = sTsConfig.getStringSet("like_forum", null)

    // 关注列表缓存所属账号: 换号后缓存必须作废重拉, 否则按旧账号关注列表过滤
    fun putLikeForumBduss(bduss: String?) {
        sTsConfig.edit().putString("like_forum_bduss", bduss).apply()
    }

    fun getLikeForumBduss(): String? = sTsConfig.getString("like_forum_bduss", null)

    // 记录"签到开始时刻"所属日: 签到跨零点完成时(23:59 开始, 00:00 后写盘),
    // 用写入时刻会把新的一天标成已签, 当天自动签到被跳过
    fun putSignDate(dayOfYear: Int, year: Int) {
        sTsConfig.edit()
            .putInt("sign_date", dayOfYear)
            .putInt("sign_year", year)
            .apply()
    }

    fun getIsSigned(): Boolean {
        val cal = Calendar.getInstance()
        return cal[Calendar.DAY_OF_YEAR] == sTsConfig.getInt("sign_date", 0) &&
            cal[Calendar.YEAR] == sTsConfig.getInt("sign_year", 0)
    }

    // 签到当日尝试计数(持久化): 内存计数杀进程即绕过"当日 3 次"限流, 每次冷启动
    // 又是 3 轮 × N 个吧 POST。与 putSignDate 同库, 跨日自动作废(读取时按年/日比对)
    fun getSignAttempt(dayOfYear: Int, year: Int): Int {
        val parts = sTsConfig.getString("sign_attempt", "")?.split(":") ?: return 0
        return if (parts.size == 3 &&
            parts[0].toIntOrNull() == year && parts[1].toIntOrNull() == dayOfYear
        ) {
            parts[2].toIntOrNull() ?: 0
        } else {
            0
        }
    }

    fun putSignAttempt(dayOfYear: Int, year: Int, count: Int) {
        sTsConfig.edit().putString("sign_attempt", "$year:$dayOfYear:$count").apply()
    }

    // 自动打开签到界面的当日标记: 语义与 putSignDate 不同——发起拉起即落盘,
    // 不依赖用户是否完成签到("每天一次"是展示频控而非完成状态);
    // 拉起失败时由调用方清除标记回滚, 让当天后续冷启动可重试
    fun putOpenSignDate(dayOfYear: Int, year: Int) {
        sTsConfig.edit()
            .putInt("open_sign_date", dayOfYear)
            .putInt("open_sign_year", year)
            .apply()
    }

    fun clearOpenSignDate() {
        sTsConfig.edit()
            .remove("open_sign_date")
            .remove("open_sign_year")
            .apply()
    }

    fun getIsOpenSignShown(): Boolean {
        val cal = Calendar.getInstance()
        return cal[Calendar.DAY_OF_YEAR] == sTsConfig.getInt("open_sign_date", 0) &&
            cal[Calendar.YEAR] == sTsConfig.getInt("open_sign_year", 0)
    }
}
