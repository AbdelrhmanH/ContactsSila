package app.sila.core.database.dao

import app.sila.core.model.ArhamInfo
import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * عقد DAO لبيانات صلة الرحم (أرحام) المحلية (ARCHITECTURE.md §5).
 *
 * PR B (ADR-0011): عقد نطاق بلا شروح Room — التنفيذ الفعلي في طبقة data
 * (`LocalSilaDatabase` + `ArhamEntityDao`).
 */
interface ArhamDao {

    fun observeArhamContacts(): Flow<List<Contact>>

    suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo)

    suspend fun recordContactCall(contactId: Long, timestampMillis: Long)
}
