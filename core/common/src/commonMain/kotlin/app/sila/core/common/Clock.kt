package app.sila.core.common

import kotlinx.datetime.Clock as KtxClock
import kotlinx.datetime.Instant

/**
 * ساعة مجردة قابلة للحقن والاختبار لحسابات أرحام وTTL.
 */
interface AppClock {
    fun now(): Instant
    fun nowEpochMillis(): Long = now().toEpochMilliseconds()
}

object SystemAppClock : AppClock {
    override fun now(): Instant = KtxClock.System.now()
}
