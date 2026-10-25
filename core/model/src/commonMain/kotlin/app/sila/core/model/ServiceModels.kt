package app.sila.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Service(
    val cloudId: String,
    val name: String,
    val professionId: String,
    val professionNameAr: String = "",
    val professionNameEn: String = "",
    val phoneE164: String,
    val previousPhones: List<String> = emptyList(),
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
    val distanceKm: Double? = null,
    val isAvailableNow: Boolean = true,
    val ownerVerified: Boolean = false,
    val isSavedByUser: Boolean = false,
    val myRating: Rating? = null,
    val myCallCount: Int = 0,
    val myLastCallAt: Long? = null,
    val possiblyBroken: Boolean = false,
    val syncedAt: Long = 0L
)

@Serializable
data class Profession(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val icon: String,
    val keywords: List<String> = emptyList(),
    val parentId: String? = null,
    val suppliers: List<String> = emptyList(),
    val isB2B: Boolean = false
)

@Serializable
data class DisputeLog(
    val id: Long,
    val serviceCloudId: String,
    val description: String,
    val localImageUris: List<String> = emptyList(),
    val cloudImageUrls: List<String> = emptyList(),
    val status: DisputeStatus = DisputeStatus.OPEN,
    val createdAt: Long,
    val resolvedAt: Long? = null
)

@Serializable
data class TrendStat(
    val value: Int,
    val sparkline: List<Float>,
    val trendUp: Boolean? = null
)

@Serializable
data class StatCard(
    val value: String,
    val status: StatStatus = StatStatus.NORMAL
)

@Serializable
data class ProDashboard(
    val period: DashboardPeriod,
    val trustScore: Int,
    val trustLabel: String,
    val impressions: TrendStat,
    val profileViews: TrendStat,
    val saves: TrendStat,
    val inboundCalls: StatCard,
    val positiveRatingPercent: StatCard,
    val avgCallDurationSec: StatCard,
    val shares: StatCard,
    val uniqueCallers: StatCard,
    val returnRate: StatCard,
    val trendText: String,
    val trendPositive: Boolean
)
