package app.sila.feature.services.domain

import app.sila.core.model.Service
import kotlinx.coroutines.flow.Flow

/**
 * حالة استخدام أحادية المسؤولية: مراقبة دليل الخدمات حسب المهنة ونص البحث (SRP).
 */
class ObserveServicesDirectoryUseCase(
    private val repository: ServicesRepository
) {
    operator fun invoke(professionId: String? = null, searchQuery: String = ""): Flow<List<Service>> =
        repository.observeDirectory(professionId = professionId, searchQuery = searchQuery)
}
