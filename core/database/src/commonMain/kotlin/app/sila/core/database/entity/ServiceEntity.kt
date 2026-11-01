package app.sila.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import app.sila.core.model.ServiceVisibility

/**
 * كيان الخدمة المخزنة محلياً بنمط Offline-First — الصورة الفيزيائية الحرفية لجدول
 * `services` بـ 16 عموداً (DATA_MODEL.md §2.4 · ADR-0011).
 *
 * حُذفت الحقول العشرة غير المُخزَّنة فيزيائياً (geohash/lat/lng/workingHoursJson/
 * myRating/myCallCount/myLastCallAt/possiblyBroken/syncedAt/previousPhonesJson)
 * التي كانت تعلنها الشل القديمة — المخطط الفيزيائي هو المرجع (PR B).
 */
@Entity(
    tableName = "services",
    indices = [
        Index(name = "idx_services_phone", value = ["phoneE164"]),
        Index(name = "idx_services_prof", value = ["professionId"]),
    ],
)
data class ServiceEntity(
    @PrimaryKey val cloudId: String,
    val name: String,
    val professionId: String,
    val professionNameAr: String,
    val professionNameEn: String,
    val phoneE164: String,
    val visibility: ServiceVisibility,
    val trustScore: Double,
    val uniqueCallers: Int,
    val retentionRate: Double,
    val ratingAvg: Double,
    val ratingCount: Int,
    @ColumnInfo(defaultValue = "0") val isHot: Boolean,
    val city: String? = null,
    @ColumnInfo(defaultValue = "0") val ownerVerified: Boolean,
    @ColumnInfo(defaultValue = "0") val isSavedByUser: Boolean,
)
