package gm.tieba.tabswitch.dao

import android.content.Context
import androidx.room.Room.databaseBuilder
import gm.tieba.tabswitch.hooker.deobfuscation.Matcher

object AcRules {
    private const val ACRULES_DATABASE_NAME = "Deobfs.db"
    private lateinit var ruleDao: AcRuleDao

    @Synchronized
    fun init(context: Context) {
        // 幂等守卫: 初始化失败重试会重跑本方法, 重复 build 会创建新 Room 实例,
        // 旧实例的连接池不释放; 已初始化成功则直接复用。
        // check-then-act 须同步: 两线程同时通过守卫会各 build 一个实例, 输家的
        // 连接池永不释放(文件句柄/内存泄漏)
        if (::ruleDao.isInitialized) return
        ruleDao = databaseBuilder(
            context.applicationContext, AcRuleDatabase::class.java, ACRULES_DATABASE_NAME
        )
            .allowMainThreadQueries()
            // 规则库是可重建缓存(反混淆全量重写): 实体将来升级而无迁移时破坏性重建,
            // 由启动路径的规则缺失检测触发自动补扫自愈。不加此项时升级用户首次打开
            // 抛 IllegalStateException 且被 hook 包装层吞掉, 模块整体静默失效
            .fallbackToDestructiveMigration()
            .build()
            .acRuleDao()
    }

    fun dropAllRules() {
        ruleDao.deleteAll()
    }

    // 批量写入: 逐条 putRule 是每条一个 Room 事务, 反混淆重建约百条规则即百次
    // 事务; 合并为单次 insertAll 后重建耗时线性下降
    fun putRules(rules: List<AcRule>) {
        if (rules.isEmpty()) return
        ruleDao.insertAll(*rules.toTypedArray())
    }

    fun countAll(): Int = ruleDao.count()

    fun findRule(matchers: List<Matcher>, callback: (String, String, String) -> Unit) {
        // 空列表时 Room 会生成 "matcher IN ()" 非法 SQL 直接抛 SQLiteException,
        // 规则全失效的 hooker(如 22.11 后的 PurgeEnter)走这里必须静默返回
        if (matchers.isEmpty()) return
        val matcherStrings = matchers.map { it.toString() }.toTypedArray()
        ruleDao.loadAllMatch(*matcherStrings).forEach { rule ->
            callback(rule.matcher, rule.clazz, rule.method)
        }
    }

    fun findRule(str: String, callback: (String, String, String) -> Unit) {
        ruleDao.loadAllMatch(str).forEach { rule ->
            callback(rule.matcher, rule.clazz, rule.method)
        }
    }

    // 批量版缺失判定: 启动路径对约 50 个 matcher 逐一查询时, 合并为单条 IN 查询
    fun findMissing(matchers: List<String>): List<String> {
        if (matchers.isEmpty()) return emptyList()
        val found = ruleDao.loadAllMatch(*matchers.toTypedArray())
            .mapTo(HashSet()) { it.matcher }
        return matchers.filter { it !in found }
    }
}
