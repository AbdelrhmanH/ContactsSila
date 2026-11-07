package app.sila.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import app.sila.core.database.entity.EmergencyRingingSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO كيان جلسات رنين الطوارئ — Room 3 المولَّد (T8-10 · Issue #29). الجدول v3 حرفي
 * (قرار D4)؛ القراءة التفاعلية للجلسة الجارية وحصراً هنا — الكتابات المركّبة/المقيَّدة
 * (الحسم idempotent، المحاولة الذرّية) SQL خام في [LocalSilaDatabase] حصراً.
 */
@Dao
interface EmergencyRingingSessionEntityDao {

    /** الجلسة الجارية (أحدث صف `IN_PROGRESS`) — سطح الواجهة التفاعلية (SSOT). */
    @Query(
        "SELECT * FROM emergency_ringing_sessions WHERE status = 'IN_PROGRESS' ORDER BY id DESC LIMIT 1"
    )
    fun observeInProgress(): Flow<EmergencyRingingSessionEntity?>

    /** إدراج جلسة جديدة — يعيد المعرف التوليدي (§N.2). */
    @Insert
    suspend fun insert(entity: EmergencyRingingSessionEntity): Long
}
