package app.sila.feature.arham.domain

import app.sila.core.model.ArhamInfo
import app.sila.core.model.Contact
import kotlinx.coroutines.flow.Flow

/**
 * عقد مستودع أرحام (طبقة Domain داخل :feature:arham:domain — DIP).
 */
interface ArhamRepository {
    fun observeArhamContacts(): Flow<List<Contact>>
    suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo)
    suspend fun recordContactCall(contactId: Long, timestampMillis: Long)
}
