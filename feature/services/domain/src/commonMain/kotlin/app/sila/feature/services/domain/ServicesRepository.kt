package app.sila.feature.services.domain

import app.sila.core.model.Service
import kotlinx.coroutines.flow.Flow

/**
 * عقد مستودع دليل الخدمات (طبقة Domain داخل :feature:services:domain — DIP).
 */
interface ServicesRepository {
    fun observeDirectory(professionId: String? = null, searchQuery: String = ""): Flow<List<Service>>
    suspend fun lookupByPhoneE164(phone: String): Service?
    suspend fun upsertServices(services: List<Service>)
}
