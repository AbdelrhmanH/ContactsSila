package app.sila.feature.contacts.data

import app.sila.core.model.Contact
import app.sila.feature.contacts.domain.PlatformContact

/**
 * انعكاس جهة منصة إلى نموذج النطاق بغرض كتابتها في Room عبر دفعة المزامنة (Issue #23 · ADR-0010 §7).
 *
 * - `id = 0`: يُحلّ داخل [app.sila.core.database.dao.ContactsSyncDao.applySyncBatch]
 *   (الجديد بمعرّف تلقائي، والموجود بدمج read-write يحفظ معرّفه وأعمدة صِلَة).
 * - أعمدة صِلَة (`category`, `isFavorite`, `arham`, `ringtoneUri`, …)
 *   **لا تُملك من المنصة إطلاقاً** — قيمها الافتراضية هنا تُهمَل عند الدمج مع صف موجود.
 * - `normalizedName` يُطبَّع في الـ DAO (`ArabicTextNormalizer`) — هنا يُترك فارغاً عمداً.
 * - الحقول بلا عمود في المخطط المخفَّض (`emails`, `websites`, `addresses`, `dates`,
 *   `tags`, `customFields`) تُسقَط — قيد مخطط v2 الحالي الموثّق في PR الميزة.
 */
internal fun PlatformContact.toDomainContact(nowMillis: Long): Contact = Contact(
    id = 0L,
    systemContactId = systemContactId,
    lookupKey = lookupKey,
    name = name,
    photoUri = photoUri,
    phones = phones,
    organization = organization,
    notes = notes,
    account = account,
    normalizedName = "",
    createdAt = nowMillis,
    updatedAt = nowMillis
)
