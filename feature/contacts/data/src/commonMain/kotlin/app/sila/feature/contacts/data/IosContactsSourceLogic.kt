package app.sila.feature.contacts.data

import app.sila.core.model.ContactName
import app.sila.core.model.Email
import app.sila.core.model.Organization
import app.sila.core.model.Phone
import app.sila.feature.contacts.domain.PlatformContact
import app.sila.feature.contacts.domain.ContactsDelta
import app.sila.feature.contacts.domain.ContactsSnapshot

/**
 * المنطق النقي لمصدر جهات الاتصال على iOS — خالٍ من أنواع `platform.*` ويُختبر على JVM
 * (المقاصفة/التجميع) وعلى محاكي iOS (تحويل `CNContact` الفعلي) — Issue #22.
 *
 * [IosContactsPlatformSource] يحوّل `CNContact`/أحداث `CNChangeHistoryFetchRequest`
 * إلى القيم النقية هنا حصراً، فيبقى التجميع قابلاً للاختبار دون طلب صلاحية.
 */

/** قيمة بعنصر مفصّل (بريد/رابط) كما تظهر في `CNLabeledValue`. */
data class IosLabeledValue(val label: String?, val value: String)

/** رقم هاتف من `CNPhoneNumber` (digits = النسخة الرقمية الموحّدة). */
data class IosPhoneValue(val raw: String, val digits: String? = null, val label: String? = null)

/**
 * القيم النقية لجهة اتصال iOS بعد فك `CNContact` (تُبنى في iosMain من `CNContact`
 * فعلي، وتُختبر من `sharedLogic/src/iosTest` بمستحيلات `CNMutableContact`).
 */
data class IosContactValues(
    /** `CNContact.identifier` — الهوية الدائمة (ADR-0009 §4). */
    val identifier: String,
    val displayName: String? = null,
    val namePrefix: String? = null,
    val givenName: String? = null,
    val middleName: String? = null,
    val familyName: String? = null,
    val nameSuffix: String? = null,
    val nickname: String? = null,
    val phoneticFirstName: String? = null,
    val phoneticMiddleName: String? = null,
    val phoneticFamilyName: String? = null,
    val organizationName: String? = null,
    val departmentName: String? = null,
    val jobTitle: String? = null,
    val note: String? = null,
    val phones: List<IosPhoneValue> = emptyList(),
    val emails: List<IosLabeledValue> = emptyList(),
    val urlAddresses: List<IosLabeledValue> = emptyList(),
    // العناوين البريدية والتواريخ/الحقول المخصصة غير منقولة في العقد المجمد (يؤجَّل بأثر تحديث حقل PlatformContact).
)

/** حدث من سجل التغييرات `CNChangeHistoryFetchRequest` بعد فك النقية. */
sealed interface IosHistoryEvent {
    data class Upsert(val contact: IosContactValues) : IosHistoryEvent
    data class Delete(val systemContactId: String) : IosHistoryEvent

    /** `.dropEverything`: يفقد الرمز دوره — إعادة مزامنة كاملة بلا حذف (ADR-0010 §4). */
    data object DropEverything : IosHistoryEvent

    /** أحداث تجري على المجموعات/الأعضاء — لا علاقة لها بدلتا جهات الاتصال. */
    data object Ignored : IosHistoryEvent
}

private const val IOS_LABEL_HOME = "home"
private const val IOS_LABEL_WORK = "work"
private const val IOS_LABEL_MOBILE = "mobile"
private const val IOS_LABEL_OTHER = "other"
private const val IOS_LABEL_NONE = "none"

/** تحويل قيم iOS النقية إلى [PlatformContact] — `systemContactId = identifier` و`lookupKey` فارغ. */
fun mapIosContact(values: IosContactValues): PlatformContact {
    val nameParts = listOfNotNull(values.givenName, values.familyName)
        .filter { it.isNotBlank() }
    val displayName = values.displayName
        ?: nameParts.joinToString(" ").takeIf { it.isNotBlank() }
        ?: values.organizationName?.takeIf { it.isNotBlank() }
        ?: values.nickname?.takeIf { it.isNotBlank() }
        ?: values.identifier
    val phonetic = listOfNotNull(
        values.phoneticFirstName,
        values.phoneticMiddleName,
        values.phoneticFamilyName
    ).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }

    val organization = if (
        values.organizationName.isNullOrBlank() &&
        values.departmentName.isNullOrBlank() &&
        values.jobTitle.isNullOrBlank()
    ) {
        null
    } else {
        Organization(
            company = values.organizationName,
            department = values.departmentName,
            jobTitles = listOfNotNull(values.jobTitle?.takeIf { it.isNotBlank() })
        )
    }

    return PlatformContact(
        systemContactId = values.identifier,
        lookupKey = null,
        name = ContactName(
            displayName = displayName,
            fileAs = listOfNotNull(values.familyName, values.givenName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { values.organizationName ?: displayName },
            prefix = values.namePrefix,
            firstName = values.givenName,
            middleName = values.middleName,
            lastName = values.familyName,
            suffix = values.nameSuffix,
            nickname = values.nickname?.takeIf { it.isNotBlank() },
            phoneticName = phonetic
        ),
        phones = values.phones
            .filter { it.raw.isNotBlank() }
            .map { phone ->
                Phone(
                    raw = phone.raw,
                    e164 = phone.digits?.takeIf { it.isNotBlank() } ?: phone.raw,
                    type = mapIosLabelType(phone.label, defaultType = "MOBILE", fallback = "OTHER"),
                    label = labelType(phone.label)
                )
            },
        emails = values.emails
            .filter { it.value.isNotBlank() }
            .map { email ->
                Email(
                    address = email.value,
                    type = mapIosLabelType(email.label, defaultType = "PERSONAL", fallback = "OTHER"),
                    label = labelType(email.label)
                )
            },
        organization = organization,
        websites = values.urlAddresses.mapNotNull { it.value.takeIf { it.isNotBlank() } },
        notes = values.note?.takeIf { it.isNotBlank() },
        account = null
    )
}

private fun labelType(label: String?): String? = when (label?.lowercase()) {
    IOS_LABEL_HOME -> "HOME"
    IOS_LABEL_WORK -> "WORK"
    IOS_LABEL_MOBILE -> "MOBILE"
    IOS_LABEL_OTHER, IOS_LABEL_NONE -> null
    null -> null
    else -> label
}

private fun mapIosLabelType(label: String?, defaultType: String, fallback: String): String =
    when (label?.lowercase()) {
        IOS_LABEL_HOME -> "HOME"
        IOS_LABEL_WORK -> "WORK"
        IOS_LABEL_MOBILE -> "MOBILE"
        IOS_LABEL_NONE, null -> defaultType
        else -> fallback
    }

/**
 * تجميع أحداث سجل التغييرات إلى [ContactsDelta] (ADR-0010 §4).
 *
 * القانون:
 * - **`.dropEverything` يُلغي الحذف نهائياً**: إذا ظهر في الدفعة فالرمز القديم فقد دوره،
 *   تُهمل كل أحداث الحذف، وتُحسب النتيجة إعادة مزامنة كاملة مع إضافة كل جهات القائمة.
 * - أحداث إضافة/تحديث تحمل الحالة النهائية الكاملة (upsert idempotent بمفتاح
 *   `systemContactId`)؛ الحذف يُعالج بالهوية الموحّدة (`CNContact.identifier`).
 * - [newSnapshot] تُكتَب داخل نفس معاملة Room مع تطبيق الدلتا.
 */

fun assembleIosDelta(
    events: List<IosHistoryEvent>,
    newSnapshot: ContactsSnapshot,
): ContactsDelta {
    val hadDropEverything = events.any { it is IosHistoryEvent.DropEverything }
    val upserted = events
        .filterIsInstance<IosHistoryEvent.Upsert>()
        .map { mapIosContact(it.contact) }
    val deletedSystemContactIds = if (hadDropEverything) {
        emptyList()
    } else {
        events
            .filterIsInstance<IosHistoryEvent.Delete>()
            .map { it.systemContactId }
            .distinct()
    }
    return ContactsDelta(
        upserted = upserted,
        deletedSystemContactIds = deletedSystemContactIds,
        newSnapshot = newSnapshot,
        isFullResync = hadDropEverything
    )
}
