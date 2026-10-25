package app.sila.feature.services.data

import app.sila.core.database.dao.ServiceDao
import app.sila.core.model.Service
import app.sila.feature.services.domain.ServicesRepository
import kotlinx.coroutines.flow.Flow

/**
 * تنفيذ مستودع دليل الخدمات (طبقة Data داخل :feature:services:data).
 * يطبق مبدأ Offline-First: الواجهة تقرأ من Room، والمزامنة تكتب في Room.
 */
class ServicesRepositoryImpl(
    private val serviceDao: ServiceDao
) : ServicesRepository {

    override fun observeDirectory(professionId: String?, searchQuery: String): Flow<List<Service>> =
        serviceDao.observeServices(professionId = professionId, searchQuery = searchQuery)

    override suspend fun lookupByPhoneE164(phone: String): Service? =
        serviceDao.findByPhoneE164(phone)

    override suspend fun upsertServices(services: List<Service>) {
        serviceDao.upsertServices(services)
    }
}
