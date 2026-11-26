package gm.tieba.tabswitch.dao

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher

// 反混淆规则库(Deobfs.db)。原为 Room 实现, 因单表 4 列、仅 5 个操作, 改为
// framework SQLiteOpenHelper 手写——省去 room-runtime/androidx.sqlite/
// flatbuffers 整链依赖(dex 字节码 ~50KB)。
//
// schema 兼容: 建表 DDL 与 Room 2.6 生成的形态一致(列名/类型/AUTOINCREMENT),
// 旧用户由 Room 建库的文件无需迁移, 直接按列名读(不依赖列序); Room 遗留的
// room_master_table 本实现不读不写, 留在库内无影响。
//
// 原 ROOM 语义的等价落点:
// - allowMainThreadQueries: framework SQLiteDatabase 天然支持主线程同步查询,
//   规则库查询都在 hook 回调里, 本来就要求同步
// - fallbackToDestructiveMigration: 库版本不匹配时整体 drop 重建——规则库是
//   可重建缓存, 由启动路径的规则缺失检测触发自动补扫自愈, 语义等价
object AcRules {
    private const val ACRULES_DATABASE_NAME = "Deobfs.db"

    // 库版本=1 与 Room 时期一致; 将来实体字段变更时递增, onUpgrade 走破坏性重建
    private const val ACRULES_DATABASE_VERSION = 1

    private lateinit var db: SQLiteDatabase

    private class OpenHelper(context: Context) : SQLiteOpenHelper(
        context.applicationContext, ACRULES_DATABASE_NAME, null, ACRULES_DATABASE_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                // 列名 class 加反引号: 与 Room 生成的 DDL 形态一致(SQLite 中非
                // 保留字, 但显式引用更稳)
                "CREATE TABLE IF NOT EXISTS `AcRule` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`matcher` TEXT NOT NULL, " +
                    "`class` TEXT NOT NULL, " +
                    "`method` TEXT NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 破坏性重建(对应原 fallbackToDestructiveMigration): 规则库是缓存,
            // 半清空状态由后续"缺失补扫"自愈; 单条 DROP 语句自身原子
            db.execSQL("DROP TABLE IF EXISTS `AcRule`")
            onCreate(db)
        }
    }

    @Synchronized
    fun init(context: Context) {
        // 幂等守卫: 初始化失败重试会重跑本方法, 重复 open 会创建新连接,
        // 旧连接不释放(文件句柄/内存泄漏); check-then-act 须同步
        if (::db.isInitialized) return
        db = OpenHelper(context).writableDatabase
    }

    fun dropAllRules() {
        db.execSQL("DELETE FROM `AcRule`")
    }

    // 批量写入: 单事务合并(逐条事务时反混淆重建约百条规则即百次 fsync,
    // 重建耗时线性下降)
    fun putRules(rules: List<AcRule>) {
        if (rules.isEmpty()) return
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT INTO `AcRule`(`matcher`,`class`,`method`) VALUES(?,?,?)"
            )
            for (rule in rules) {
                stmt.clearBindings()
                stmt.bindString(1, rule.matcher)
                stmt.bindString(2, rule.clazz)
                stmt.bindString(3, rule.method)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun countAll(): Int {
        db.rawQuery("SELECT COUNT(*) FROM `AcRule`", null).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private fun loadMatch(matchers: List<String>): List<AcRule> {
        // 动态 IN 占位: 空列表时 Room 会生成 "IN ()" 非法 SQL, 调用方已先判空,
        // 这里兜底返回空(防规则全失效的 hooker 路径抛 SQLiteException)
        if (matchers.isEmpty()) return emptyList()
        val placeholders = matchers.joinToString(",") { "?" }
        val args = matchers.toTypedArray()
        val out = ArrayList<AcRule>(matchers.size)
        db.rawQuery(
            "SELECT `id`,`matcher`,`class`,`method` FROM `AcRule` WHERE `matcher` IN ($placeholders)",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    AcRule(
                        cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("matcher")),
                        cursor.getString(cursor.getColumnIndexOrThrow("class")),
                        cursor.getString(cursor.getColumnIndexOrThrow("method"))
                    )
                )
            }
        }
        return out
    }

    fun findRule(matchers: List<Matcher>, callback: (String, String, String) -> Unit) {
        // 空列表直接静默返回(原 Room "IN ()" 抛异常路径, 注释保留在 loadMatch 兜底)
        if (matchers.isEmpty()) return
        loadMatch(matchers.map { it.toString() }).forEach { rule ->
            callback(rule.matcher, rule.clazz, rule.method)
        }
    }

    fun findRule(str: String, callback: (String, String, String) -> Unit) {
        loadMatch(listOf(str)).forEach { rule ->
            callback(rule.matcher, rule.clazz, rule.method)
        }
    }

    // 批量版缺失判定: 启动路径对约 50 个 matcher 逐一查询时, 合并为单条 IN 查询
    fun findMissing(matchers: List<String>): List<String> {
        if (matchers.isEmpty()) return emptyList()
        val found = loadMatch(matchers).mapTo(HashSet()) { it.matcher }
        return matchers.filter { it !in found }
    }

    // 规则内容抽样: 均匀取全表 id 对齐的 n 条(id 取模步进), 供"规则内容有效性"
    // 验证——条数完整性(isRuleStoreIntact)只拦"库被清空/写入中断", 拦不住
    // "条数对但内容错"(特征歧义写入错误方法/脏数据)。抽样返回 ≤n 条(库不足 n 时全取),
    // 随机性用 id 对齐而非 ORDER BY RANDOM: 全表排序代价 O(n log n), 步进 O(n) 且
    // 分布等价(本表 id 连续无空洞——单事务批量写入, AUTOINCREMENT 无删除路径)
    fun sampleRules(n: Int): List<AcRule> {
        if (n <= 0) return emptyList()
        val total = countAll()
        if (total <= 0) return emptyList()
        val step = maxOf(1, total / n)
        val out = ArrayList<AcRule>(n)
        db.rawQuery(
            "SELECT `id`,`matcher`,`class`,`method` FROM `AcRule` WHERE (`id` % ?) = 1 LIMIT ?",
            arrayOf(step.toString(), n.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(
                    AcRule(
                        cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("matcher")),
                        cursor.getString(cursor.getColumnIndexOrThrow("class")),
                        cursor.getString(cursor.getColumnIndexOrThrow("method"))
                    )
                )
            }
        }
        return out
    }
}
