package app.sila.core.database.dao

import app.sila.core.model.Service
import kotlinx.coroutines.flow.Flow

/**
 * عقد DAO لبيانات الخدمات المحلية بنمط Offline-First (ARCHITECTURE.md §5).
 *
 * PR B (ADR-0011): عقد نطاق بلا شروح Room — التنفيذ الفعلي في طبقة data
 * (`LocalSilaDatabase` + `ServiceEntityDao`).
 */
interface ServiceDao {

    fun observeServices(professionId: String? = null, searchQuery: String = ""): Flow<List<Service>>

    suspend fun upsertServices(services: List<Service>)

    suspend fun findByPhoneE164(e164: String): Service?
}
