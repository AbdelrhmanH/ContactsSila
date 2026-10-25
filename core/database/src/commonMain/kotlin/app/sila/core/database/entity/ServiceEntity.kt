package app.sila.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.sila.core.model.ServiceVisibility

/**
 * كيان الخدمة المخزنة محلياً بنمط Offline-First (DATA_MODEL.md §2.4).
 */
@Entity(
    tableName = "services",
    indices = [
        Index(value = ["phoneE164"]),
        Index(value = ["professionId"]),
        Index(value = ["geohash"])
    ]
)
data class ServiceEntity(
    @PrimaryKey val cloudId: String,
    val name: String,
    val professionId: String,
    val professionNameAr: String = "",
    val professionNameEn: String = "",
    val phoneE164: String,
    val previousPhonesJson: String = "[]",
    val visibility: ServiceVisibility = ServiceVisibility.COMMUNITY,
    val trustScore: Double = 0.0,
    val uniqueCallers: Int = 0,
    val retentionRate: Double = 0.0,
    val ratingAvg: Double = 0.0,
    val ratingCount: Int = 0,
    val isHot: Boolean = false,
    val geohash: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val city: String? = null,
    val workingHoursJson: String? = null,
    val ownerVerified: Boolean = false,
    val isSavedByUser: Boolean = false,
    val myRating: Int? = null,
    val myCallCount: Int = 0,
    val myLastCallAt: Long? = null,
    val possiblyBroken: Boolean = false,
    val syncedAt: Long = 0L
)
