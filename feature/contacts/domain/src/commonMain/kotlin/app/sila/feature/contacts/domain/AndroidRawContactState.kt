package app.sila.feature.contacts.domain

import kotlinx.serialization.Serializable

/**
 * الحالة الدائمة لجهة اتصال خام واحدة على Android، تُستخدم حصراً كمدخل لحساب الفروق
 * بين لقطات إصدارات `RawContacts.VERSION` (ADR-0010 §3).
 *
 * - [version]: قيمة الرقم `RawContacts.VERSION` وقت اللقطة.
 * - [lookupKey]: مفتاح `LOOKUP_KEY` للجهة المجمّعة التابعة لهذا السجل الخام
 *   (والقيمة الدائمة الموحّدة [PlatformContact.systemContactId]). قيمتها `null` إن تعذر حلّها
 *   (مثلاً جهة غير مرئية في المجموعة الافتراضية) ولا تنتج حذفاً في الدلتا.
 * - [aggregateContactId]: قيمة الرقم `Contacts._ID` للجهة المجمّعة، تُستخدم حصراً لحساب الفروق
 *   ومطابقة سجلات `DeletedContacts` — **لا تُخزَّن ولا تُستخدم أبداً كهوية** (ADR-0009 §4).
 */
@Serializable
data class AndroidRawContactState(
    val version: Long,
    val lookupKey: String? = null,
    val aggregateContactId: Long? = null,
)
