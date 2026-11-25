package gm.tieba.tabswitch.dao

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import gm.tieba.tabswitch.util.Logger

// 帖子浏览缓存库(ThreadSnapshot.db)。功能"帖子浏览缓存"把进帖时宿主成功返回的 PbPage
// 响应完整原始字节落库(22.14 新栈 = PbListResIdl 字节; 老宿主兼容腿 =
// PbPageResIdl 字节, 以进帖首屏响应为准), 帖子被删/失效后用缓存字节回放
// (重走宿主自己的解析转换管线, 见 hooker/add/ThreadSnapshot.kt)。
//
// 与 AcRules 同款 framework SQLiteOpenHelper 手写范式: 单表少量操作, 不为它拖
// Room 整链。库文件独立于 Deobfs.db——快照体量大(单帖可达 MB 级), 与规则库
// 共库会让"清空规则"的破坏性重建误伤快照。
//
// 淘汰策略: 按 cached_at 升序(即插入序)逐出最旧——快照是"首屏定格"语义,
// 同帖不会重复写入(captureFull 的 contains 短路), cached_at 顺序就是
// 首次浏览顺序, 最旧的帖子最可能不再需要。
object ThreadSnapshotStore {
    private const val DB_NAME = "ThreadSnapshot.db"
    private const val DB_VERSION = 1

    // 快照条数硬上限(用户设置的最大值, 功能开启时由设置界面写入): 淘汰在写入
    // 路径内做, 超限时按 cached_at 升序逐出最旧的直到回到上限内
    @Volatile
    var capacity: Int = 100

    private lateinit var db: SQLiteDatabase

    private class OpenHelper(context: Context) : SQLiteOpenHelper(
        context.applicationContext, DB_NAME, null, DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `ThreadSnapshot` (" +
                    "`tid` TEXT PRIMARY KEY NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`data` BLOB NOT NULL, " +
                    "`cached_at` INTEGER NOT NULL, " +
                    "`size` INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 破坏性重建: 快照是可重建缓存(再浏览一遍即重新落库), 升版本不做迁移
            db.execSQL("DROP TABLE IF EXISTS `ThreadSnapshot`")
            onCreate(db)
        }
    }

    @Synchronized
    fun init(context: Context) {
        // 幂等守卫: 与 AcRules.init 同口径, 初始化失败重试会重复 open 造泄漏
        if (::db.isInitialized) return
        try {
            db = OpenHelper(context).writableDatabase
        } catch (tr: Throwable) {
            // 库打不开(磁盘满等)只损失快照功能, 不拖垮模块初始化: 调用方按
            // isReady() 判定, 全部读写点走此守卫静默降级
            Logger.log("ThreadSnapshotStore 初始化失败: $tr")
        }
    }

    fun isReady(): Boolean = ::db.isInitialized

    // 快照行: data = 进帖首屏 PbPage 响应的完整原始字节(新栈为 PbListResIdl
    // 字节, 老宿主兼容腿为 PbPageResIdl 字节), 回放时原样喂回宿主 convert 管线
    class Snapshot(
        val tid: String,
        val title: String,
        val data: ByteArray,
        val cachedAt: Long,
        val size: Int
    )

    @Synchronized
    fun put(tid: String, title: String, data: ByteArray) {
        if (!::db.isInitialized || tid.isEmpty() || data.isEmpty()) return
        try {
            val now = System.currentTimeMillis()
            db.execSQL(
                "INSERT OR REPLACE INTO `ThreadSnapshot`(`tid`,`title`,`data`,`cached_at`,`size`) VALUES(?,?,?,?,?)",
                arrayOf(tid, title, data, now, data.size)
            )
            evictOverflow()
        } catch (tr: Throwable) {
            // 快照写入失败静默: 主流程(浏览)不能被旁路功能拖垮
            Logger.log("缓存写入失败 tid=$tid: $tr")
        }
    }

    @Synchronized
    fun get(tid: String): Snapshot? {
        if (!::db.isInitialized || tid.isEmpty()) return null
        return try {
            db.rawQuery(
                "SELECT `tid`,`title`,`data`,`cached_at`,`size` FROM `ThreadSnapshot` WHERE `tid`=?",
                arrayOf(tid)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    Snapshot(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getBlob(2),
                        cursor.getLong(3),
                        cursor.getInt(4)
                    )
                } else null
            }
        } catch (tr: Throwable) {
            Logger.log("缓存读取失败 tid=$tid: $tr")
            null
        }
    }

    // 帖子是否存在(回放判定走轻查询, 避免无谓读出整块 BLOB)
    @Synchronized
    fun contains(tid: String): Boolean {
        if (!::db.isInitialized || tid.isEmpty()) return false
        return try {
            db.rawQuery(
                "SELECT 1 FROM `ThreadSnapshot` WHERE `tid`=? LIMIT 1", arrayOf(tid)
            ).use { it.moveToFirst() }
        } catch (ignored: Throwable) {
            false
        }
    }

    // 超限逐出: 按 cached_at 升序删除最旧直到条数回到 capacity 内。
    // 单条 DELETE 自带原子性, 无需显式事务
    private fun evictOverflow() {
        if (capacity <= 0) capacity = 100
        try {
            db.execSQL(
                "DELETE FROM `ThreadSnapshot` WHERE `tid` IN (" +
                    "SELECT `tid` FROM `ThreadSnapshot` ORDER BY `cached_at` ASC LIMIT " +
                    "MAX(0, (SELECT COUNT(*) FROM `ThreadSnapshot`) - ?))",
                arrayOf(capacity)
            )
        } catch (tr: Throwable) {
            Logger.log("缓存超限清理失败: $tr")
        }
    }

}
