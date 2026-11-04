package app.sila.feature.arham.domain

import app.sila.core.model.ArhamItem
import app.sila.core.model.Contact
import app.sila.core.model.calculateUrgency
import app.sila.core.model.toArhamStatus
import kotlinx.datetime.Instant

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * بانٍ موحَّد لعنصر قائمة الأولويات من جهة اتصال (T8-07).
 *
 * المصدر الوحيد لتحويل `Contact` ← `ArhamItem` حتى لا تنحرف حقول العرض بين القائمة
 * المرتبة ومنصة اللوحة (درس O-7: القاعدة نفسها في موضعين تنحرف). يفترض وجود `arham`
 * — يستدعيه النطاق بعد فلترة غير الأرحام.
 */
internal fun Contact.toArhamItem(now: Instant): ArhamItem {
    val info = requireNotNull(arham) { "toArhamItem يتطلب جهةً أرحام (arham != null)" }
    val nowMs = now.toEpochMilliseconds()
    val urgency = info.calculateUrgency(now)
    val daysSince = info.lastContactedAt?.let { last ->
        ((nowMs - last).coerceAtLeast(0L) / MILLIS_PER_DAY).toInt()
    }
    return ArhamItem(
        rank = 0, // يُعاد ترقيمه من المستدعي (ترتيب نهائي بلا فراغات)
        contactId = id,
        displayName = name.displayName,
        photoUri = photoUri,
        relation = info.relation,
        frequency = info.frequency,
        daysSinceLastContact = daysSince,
        urgency = urgency,
        status = urgency.toArhamStatus(),
        primaryPhone = phones.firstOrNull()?.e164 ?: ""
    )
}
