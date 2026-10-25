package app.sila.feature.arham.data

import app.sila.core.database.dao.ArhamDao
import app.sila.core.model.ArhamInfo
import app.sila.core.model.Contact
import app.sila.feature.arham.domain.ArhamRepository
import kotlinx.coroutines.flow.Flow

/**
 * تنفيذ مستودع أرحام (طبقة Data داخل :feature:arham:data).
 * يعمل محلياً 100% فوق ArhamDao ولا يتصل بالشبكة أبداً.
 */
class ArhamRepositoryImpl(
    private val arhamDao: ArhamDao
) : ArhamRepository {

    override fun observeArhamContacts(): Flow<List<Contact>> =
        arhamDao.observeArhamContacts()

    override suspend fun upsertArhamInfo(contactId: Long, info: ArhamInfo) {
        arhamDao.upsertArhamInfo(contactId, info)
    }

    override suspend fun recordContactCall(contactId: Long, timestampMillis: Long) {
        arhamDao.recordContactCall(contactId, timestampMillis)
    }
}
