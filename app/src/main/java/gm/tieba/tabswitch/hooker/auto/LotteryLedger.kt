package gm.tieba.tabswitch.hooker.auto

import gm.tieba.tabswitch.dao.Adp
import android.content.Context

// 当日台账子系统：按账号+日期登记"已领取/已发送"的房间集合，跨贴吧重启有效。
// 从 OneKeyLottery 原文搬移（逐字保留注释）。
internal object LotteryLedger {

    // 本地时区当天零点（福利按自然日领取）。运行期间取台账日快照（见 mRunDayString），
    // 保证跨午夜运行时回复窗口与台账键的日期口径一致
    private var mRunDayStart = 0L

    // 本地时区当天日期串（台账键的日期部分，跨前缀共用）。运行期间取开跑时快照：
    // 抽奖最长可跑 60 分钟并跨过午夜，若每次现算，午夜后 daySetLoad 会按新日期
    // 取到空集合并清理昨天的登记，午夜前"已发送/已领取"的证据全部失据 → 已发
    // 房间被判未发送而重发。开跑时快照一次、全程沿用，结束后清空恢复自然日判定
    @Volatile private var mRunDayString: String? = null

    // 开跑时对台账日做快照（全程沿用同一日期口径，见 mRunDayString 注释）
    internal fun beginRun() {
        mRunDayString = todayDateString()
        mRunDayStart = todayStartMillis()
    }

    // 结束运行：清空日快照，恢复按自然日现算
    internal fun endRun() {
        mRunDayString = null
        mRunDayStart = 0L
    }

    // 本地时区当天零点（福利按自然日领取）。运行期间取台账日快照，
    // 保证跨午夜运行时回复窗口与台账键的日期口径一致
    internal fun todayStartMillis(): Long {
        mRunDayStart.takeIf { it > 0 }?.let { return it }
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // 当前账号短标识（BDUSS 哈希的十六进制）：台账键与内存态按账号隔离，切换账号后互不可见。
    // BDUSS 尚未捕获时用 "na" 兜底（此时台账本就不应被信任）
    internal fun activeAccountId(): String {
        val bduss = runCatching { Adp.BDUSS }.getOrNull()
        if (bduss.isNullOrEmpty()) return "na"
        return Integer.toHexString(bduss.hashCode())
    }

    // 当日已领取登记：键为 claimed_账号_日期 / sent_账号_日期，值为 roomId 集合；读取时顺带清理非当日旧键。
    // claimed=已确认领取，sent=口令已发出（回复可能未确认）——同日重跑据此区分：
    // 已领取跳过、已发送只核查不重发、两者皆无（此前没发出去的）才真正发送。
    // 账号段为后加：无账号段的旧格式键与新键不匹配，切到新格式后的首个自然日内旧登记
    // 视为未登记（最坏重发一次，得到"已领过"回复即重新坐实），属一次性可接受过渡；
    // 旧键由 daySetLoad 的清理逻辑照常回收
    private fun daySetKey(prefix: String): String {
        return prefix + "_" + activeAccountId() + "_" + todayDateString()
    }

    private fun todayDateString(): String {
        mRunDayString?.let { return it }
        val cal = java.util.Calendar.getInstance()
        return "%04d%02d%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    internal fun daySetLoad(context: Context, prefix: String): Set<String> {
        val prefs = context.getSharedPreferences(LOTTERY_CLAIM_PREFS_NAME, Context.MODE_PRIVATE)
        // 只清理"过往日期"的键；claimed_/sent_ 两个当日键并存，
        // 不得互删（清理条件若按"非当日全键"写会把另一前缀的当日键误删）
        val today = todayDateString()
        prefs.all.keys.filter { it.startsWith("claimed_") || it.startsWith("sent_") }.forEach { key ->
            // 日期取最后一段：新格式 claimed_账号_日期 与旧格式 claimed_日期 均适用
            // （账号段为十六进制/"na"，不含下划线）
            val keyDate = key.substringAfterLast('_', "")
            if (keyDate.isNotEmpty() && keyDate != today) prefs.edit().remove(key).apply()
        }
        return prefs.getStringSet(daySetKey(prefix), emptySet()) ?: emptySet()
    }

    internal fun daySetAdd(context: Context, prefix: String, roomId: Long) {
        val prefs = context.getSharedPreferences(LOTTERY_CLAIM_PREFS_NAME, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(daySetKey(prefix), emptySet()) ?: emptySet()).toMutableSet()
        set.add(roomId.toString())
        prefs.edit().putStringSet(daySetKey(prefix), set).apply()
    }

    // 仅补发例外通道使用：把本次运行内证实未送达的房间移出"已发"登记，重发成功后重新登记
    internal fun daySetRemove(context: Context, prefix: String, roomId: Long) {
        val prefs = context.getSharedPreferences(LOTTERY_CLAIM_PREFS_NAME, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(daySetKey(prefix), emptySet()) ?: emptySet()).toMutableSet()
        set.remove(roomId.toString())
        prefs.edit().putStringSet(daySetKey(prefix), set).apply()
    }
}
