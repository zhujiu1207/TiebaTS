package gm.tieba.tabswitch.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity
data class AcRule(
    @PrimaryKey(autoGenerate = true) val id: Int,
    @ColumnInfo(name = "matcher") val matcher: String,
    @ColumnInfo(name = "class") val clazz: String,
    @ColumnInfo(name = "method") val method: String
) {
    companion object {
        fun create(matcher: String, clazz: String, method: String) =
            AcRule(0, matcher, clazz, method)
    }
}

@Dao
interface AcRuleDao {
    @Query("SELECT COUNT(*) FROM AcRule")
    fun count(): Int

    @Query("SELECT * FROM AcRule WHERE matcher IN (:matchers)")
    fun loadAllMatch(vararg matchers: String): List<AcRule>

    @Insert
    fun insertAll(vararg rules: AcRule)

    // 单条语句自带事务语义: 逐行 delete 中途失败会留下半清空状态,
    // 后续完整性校验按"库不完整"误判并触发不必要的全量重建
    @Query("DELETE FROM AcRule")
    fun deleteAll()
}

@Database(entities = [AcRule::class], version = 1, exportSchema = false)
abstract class AcRuleDatabase : RoomDatabase() {
    abstract fun acRuleDao(): AcRuleDao
}
