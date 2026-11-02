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
 * - T6 (v2 · Issue #27 · ADR-0012): `emails` و`websites` أصبحتا أطفال معكوسة من
 *   المنصة — تنعكس هنا حرفياً (كانتا تُسقَطان في مخطط v1). `notes` تُسقَط عند
 *   الإنشاء الأول فقط (تبنّي حالة المنصة عند التبني — Android يقرأها فيصير محتواها
 *   بذر صِلَة)، ثم **لا تُعاد قراءتها أبداً** في الدمج (صِلَة-فقط بعد التبني). أما
 *   `addresses`/`dates`/`customFields` فلا تُقرأ من المنصة أصلاً (ليست في
 *   [PlatformContact] المجمد — العقد T1 لا يحملها)، و`tags` خارج نطاق T6 موثقاً.
 */
internal fun PlatformContact.toDomainContact(nowMillis: Long): Contact = Contact(
    id = 0L,
    systemContactId = systemContactId,
    lookupKey = lookupKey,
    name = name,
    photoUri = photoUri,
    phones = phones,
    emails = emails,
    websites = websites,
    organization = organization,
    notes = notes,
    account = account,
    normalizedName = "",
    createdAt = nowMillis,
    updatedAt = nowMillis
)
