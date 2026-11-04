package app.sila.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import app.sila.core.database.entity.ArhamEntity

/**
 * DAO كيانات صلة الرحم (أرحام) فوق Room 3 (طبقة data حصراً — ADR-0011).
 */
@Dao
interface ArhamEntityDao {

    @Query("SELECT * FROM arham")
    suspend fun getAll(): List<ArhamEntity>

    @Query("SELECT * FROM arham WHERE contactId = :contactId LIMIT 1")
    suspend fun getByContactId(contactId: Long): ArhamEntity?

    @Upsert
    suspend fun upsert(entity: ArhamEntity)

    @Query("UPDATE arham SET lastContactedAt = :timestampMillis WHERE contactId = :contactId")
    suspend fun recordContactCall(contactId: Long, timestampMillis: Long)

    /**
     * تحديث آخر تواصل بحدّه الأقصى (T8-03 · قاعدة ADR-0006 §6):
     * `lastContactedAt = max(COALESCE(lastContactedAt, :ts), :ts)` — القيمة الأحدث قائماً
     * لا تُنقص، والفارغة (لم يُتواصل أبداً) تُعامل كـ −∞، والصف الغائب لا يُنشأ.
     * العبارة نفسها تُستخدم حرفياً داخل معاملة `upsertCallEvent` المركّبة في الواجهة
     * (نمط T8-02: بيان COUNT/DELETE في `cleanupExpiredData` و DAO كيان `call_events`).
     */
    @Query(
        "UPDATE arham SET lastContactedAt = MAX(COALESCE(lastContactedAt, :timestampMillis), :timestampMillis) " +
            "WHERE contactId = :contactId",
    )
    suspend fun updateLastContactedMax(contactId: Long, timestampMillis: Long)

    /** حذف صف أرحام (يُستدعى داخل معاملة حذف جهة — تكامل تديره طبقة البيانات). */
    @Query("DELETE FROM arham WHERE contactId = :contactId")
    suspend fun deleteByContactId(contactId: Long)
}
