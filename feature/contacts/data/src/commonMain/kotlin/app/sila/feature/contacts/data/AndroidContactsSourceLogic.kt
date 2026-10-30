package app.sila.feature.contacts.data

import app.sila.core.model.ContactName
import app.sila.core.model.Email
import app.sila.core.model.Organization
import app.sila.core.model.Phone
import app.sila.feature.contacts.domain.AndroidRawContactState
import app.sila.feature.contacts.domain.ContactsSnapshot

/**
 * المنطق النقي لمصدر جهات الاتصال على Android — خالٍ تماماً من أنواع `android.*`
 * ويُختبر على JVM في `androidHostTest` بلا Robolectric (Issue #22).
 *
 * تحوّل [AndroidContactsPlatformSource] الصفوف الخام من `ContactsContract` حصراً
 * إلى الدخل النقي هنا (بأسماء أعمدة SQLite الثابتة) حسب التقسيم المعتمد (ADR-0009 §2).
 */

/**
 * أسماء أعمدة SQLite المستخدمة في الاستعلامات من ContactsProvider.
 * أسماء أعمدة SQLite ثابتة في المنصة (التي تُستخرج منها كذلك ثوابت `ContactsContract`)؛
 * تمركزها هنا يمنع أي اختلال بين الاستعلام في `androidMain` والتحويل النقي هنا.
 */
internal object AndroidContactsColumns {
    /** `Contacts._ID` / `RawContacts.CONTACT_ID` / `Data.CONTACT_ID` — لحساب الفرق والدفاتر فقط. */
    const val AGGREGATE_CONTACT_ID = "contact_id"
    const val RAW_CONTACT_ID = "raw_contact_id"
    const val LOOKUP_KEY = "lookup"
    const val DISPLAY_NAME = "display_name"
    const val PHOTO_URI = "photo_uri"
    const val STARRED = "starred"
    const val MIMETYPE = "mimetype"
    const val DATA1 = "data1"
    const val DATA2 = "data2"
    const val DATA3 = "data3"
    const val DATA4 = "data4"
    const val DATA5 = "data5"
    const val DATA6 = "data6"
    const val DATA7 = "data7"
}

/** أنواع سجلات `ContactsContract.Data` (قيم `MIMETYPE` الثابتة في SQLite). */
internal object AndroidDataMimeTypes {
    const val STRUCTURED_NAME = "vnd.android.cursor.item/name"
    const val PHONE = "vnd.android.cursor.item/phone_v2"
    const val EMAIL = "vnd.android.cursor.item/email_v2"
    const val ORGANIZATION = "vnd.android.cursor.item/organization"
    const val NOTE = "vnd.android.cursor.item/note"
    const val WEBSITE = "vnd.android.cursor.item/website"
}

/**
 * صف من جدول `ContactsContract.Contacts`. [aggregateContactId] مدخل `Contacts._ID`،
 * **يُستخدم حصراً لحساب الفروق وتحديد الدفاتر ولا يُخزَّن كهوية** (ADR-0009 §4).
 */
internal data class AndroidContactRow(
    val aggregateContactId: Long,
    val lookupKey: String?,
    val displayName: String?,
    val photoUri: String?,
    val isFavorite: Boolean,
)

/** صف من جدول `ContactsContract.Data` بقيم الأعمدة DATA1..DATA7. */
internal data class AndroidDataRecord(
    val aggregateContactId: Long,
    val mimeType: String,
    val values: Map<String, String?>,
)

/** صف من جدول `RawContacts` بمدخلات `VERSION`/`DELETED` كدخل حصري لمقاصفة الإصدارات. */
internal data class RawContactScanRow(
    val rawContactId: String,
    val version: Long,
    val deleted: Boolean,
    val aggregateContactId: Long? = null,
)

/** سجل من `ContactsContract.DeletedContacts` (مجهود أفضل: يحفظ حذفي السجلات المجمّعة ~30 يوماً فقط). */
internal data class DeletedAggregateContactRecord(
    val aggregateContactId: Long,
    val deletedAtMillis: Long? = null,
)

/** نتيجة مقاصفة السجلات الخام بين اللقطة المحفوظة والمسح الحالي (ADR-0010 §3). */
internal data class RawContactsDiffResult(
    /** سجلات خام أُضيفت أو تغيّر إصدارها — `Data` تُقرأ لهذه المعرفات حصراً. */
    val changedRawContactIds: List<String>,
    /** هويات `systemContactId` (مفاتيح LOOKUP_KEY) للحذف، مستنبطة من صفوف DELETED/الغائبة. */
    val deletedSystemContactIds: List<String>,
    /** مفاتيح السجلات الخام المحذوفة (تُستخرج من اللقطة التالية). */
    val deletedRawContactIds: List<String>,
)

/**
 * مقاصفة إصدارات السجلات الخام: أي تغيّر في `RawContacts.VERSION` أو سجل خام جديد
 * ينتج تغيراً، وأي صف `DELETED=1` أو غائب من المسح الكامل ينتج حذفاً — بحلّه عبر
 * مفتاح `LOOKUP_KEY` المحفوظ (ADR-0010 §3). لا تُعتمد `Contacts._ID` كهوية
 * عند أي تغيّر.
 */
internal fun diffRawContactVersions(
    savedRawContacts: Map<String, AndroidRawContactState>,
    currentRows: List<RawContactScanRow>,
): RawContactsDiffResult {
    val currentIds = currentRows.map { it.rawContactId }.toSet()

    val deletedLookupKeys = LinkedHashSet<String>()
    val deletedRawIds = LinkedHashSet<String>()

    // 1) سجلات خام معلَّمة DELETED=1 — يُحل حذفها عبر مفتاح LOOKUP_KEY المحفوظ.
    for (row in currentRows.filter { it.deleted }) {
        deletedRawIds.add(row.rawContactId)
        savedRawContacts[row.rawContactId]?.lookupKey?.let(deletedLookupKeys::add)
    }

    // 2) سجلات خام اختفت كلياً من المسح الحالي (المسح كامل دائماً في هذا التنفيذ).
    for ((rawId, state) in savedRawContacts) {
        if (rawId !in currentIds) {
            deletedRawIds.add(rawId)
            state.lookupKey?.let(deletedLookupKeys::add)
        }
    }

    val changedRawContactIds = currentRows.asSequence()
        .filter { !it.deleted }
        .filter { row -> savedRawContacts[row.rawContactId]?.version != row.version }
        .map { it.rawContactId }
        .toList()

    return RawContactsDiffResult(
        changedRawContactIds = changedRawContactIds,
        deletedSystemContactIds = deletedLookupKeys.toList(),
        deletedRawContactIds = deletedRawIds.toList()
    )
}

/**
 * حذف `DeletedContacts` أفضل جهد: يربط سجلات الحذف (معرّفات مفاتيح مجمّعة `Contacts._ID`)
 * بالمفاتيح المحفوظة حصراً في اللقطة — أولوية أقل من [diffRawContactVersions]، وتُدمج
 * معها بلا تكرار في [AndroidContactsPlatformSource].
 */
internal fun resolveProviderDeletedContacts(
    deletedRecords: List<DeletedAggregateContactRecord>,
    savedRawContacts: Map<String, AndroidRawContactState>,
): List<String> {
    val deletedContactIds = deletedRecords.map { it.aggregateContactId }.toSet()
    return savedRawContacts
        .filterValues { state ->
            state.aggregateContactId != null && state.aggregateContactId in deletedContactIds
        }
        .values
        .mapNotNull { it.lookupKey }
        .distinct()
}

/**
 * بناء اللقطة الجديدة (تُحفظ داخل نفس معاملة Room مع الدلتا — ADR-0010 §7).
 *
 * [resolvedLookupKeys] يحمل مفاتيح `LOOKUP_KEY` المُحلَّة للسجلات المتغيرة/الجديدة؛
 * السجلات غير المتغيرة تبقى بحالتها المحفوظة (إصدارها لم يتغير).
 */
internal fun buildNextAndroidSnapshot(
    currentRows: List<RawContactScanRow>,
    resolvedLookupKeys: Map<String, String>,
    previousSnapshot: ContactsSnapshot?,
): ContactsSnapshot {
    val previousStates = previousSnapshot?.androidRawContacts.orEmpty()
    return ContactsSnapshot(
        platformMarker = ContactsSnapshot.PLATFORM_ANDROID,
        iosHistoryTokenBytes = null,
        androidRawContacts = currentRows
            .filter { !it.deleted }
            .associate { row ->
                row.rawContactId to AndroidRawContactState(
                    version = row.version,
                    lookupKey = resolvedLookupKeys[row.rawContactId]
                        ?: previousStates[row.rawContactId]?.lookupKey,
                    aggregateContactId = row.aggregateContactId
                )
            }
    )
}

/**
 * تحويل صفوف `Contacts` + سجلات `Data` المنتمية إليها إلى قائمة [PlatformContact] —
 * المفتاح الموحّد يأتي من `LOOKUP_KEY` حصراً، ولا يُخزن `Contacts._ID` (ADR-0009 §4).
 */

internal fun mapAndroidContactRows(
    contactRows: List<AndroidContactRow>,
    dataRecords: List<AndroidDataRecord>,
): List<PlatformContact> {
    val recordsByContact = dataRecords.groupBy { it.aggregateContactId }
    return contactRows.mapNotNull { row ->
        val recordGroup = recordsByContact[row.aggregateContactId].orEmpty()
        val nameRecord = recordGroup.firstOrNull { it.mimeType == AndroidDataMimeTypes.STRUCTURED_NAME }
        val displayName = row.displayName ?: nameRecord?.values?.get(AndroidContactsColumns.DATA1)
            ?: return@mapNotNull null
        val contactName = ContactName(
            displayName = displayName,
            fileAs = row.displayName ?: displayName,
            prefix = nameRecord?.values?.get(AndroidContactsColumns.DATA2),
            firstName = nameRecord?.values?.get(AndroidContactsColumns.DATA3),
            middleName = nameRecord?.values?.get(AndroidContactsColumns.DATA4),
            lastName = nameRecord?.values?.get(AndroidContactsColumns.DATA5),
            suffix = nameRecord?.values?.get(AndroidContactsColumns.DATA6),
            nickname = null,
            phoneticName = nameRecord?.values?.get(AndroidContactsColumns.DATA7)
        )
        PlatformContact(
            systemContactId = row.lookupKey ?: return@mapNotNull null,
            lookupKey = row.lookupKey,
            name = contactName,
            photoUri = row.photoUri,
            phones = recordGroup
                .filter { it.mimeType == AndroidDataMimeTypes.PHONE }
                .toAndroidPhones(),
            emails = recordGroup
                .filter { it.mimeType == AndroidDataMimeTypes.EMAIL }
                .toAndroidEmails(),
            organization = recordGroup
                .firstOrNull { it.mimeType == AndroidDataMimeTypes.ORGANIZATION }
                ?.toAndroidOrganization(),
            websites = recordGroup
                .filter { it.mimeType == AndroidDataMimeTypes.WEBSITE }
                .mapNotNull { record -> record.values[AndroidContactsColumns.DATA1] },
            notes = recordGroup
                .firstOrNull { it.mimeType == AndroidDataMimeTypes.NOTE }
                ?.values?.get(AndroidContactsColumns.DATA1),
            account = null
        )
    }
}

/** بناء قائمة الهواتف من سجلات `CommonDataKinds.Phone` لتغطية الأرقام (DATA7 = NORMALIZED_NUMBER). */
private fun List<AndroidDataRecord>.toAndroidPhones(): List<Phone> = mapNotNull { record ->
    val rawNumber = record.values[AndroidContactsColumns.DATA1] ?: return@mapNotNull null
    val typeInt = record.values[AndroidContactsColumns.DATA2]?.toIntOrNull()
    val customLabel = record.values[AndroidContactsColumns.DATA3]
    Phone(
        raw = rawNumber,
        e164 = record.values[AndroidContactsColumns.DATA7] ?: rawNumber,
        type = mapAndroidPhoneType(typeInt, customLabel),
        label = customLabel
    )
}

/** بناء قائمة البريد الإلكتروني من سجلات `CommonDataKinds.Email`. */
private fun List<AndroidDataRecord>.toAndroidEmails(): List<Email> = mapNotNull { record ->
    val address = record.values[AndroidContactsColumns.DATA1] ?: return@mapNotNull null
    val typeInt = record.values[AndroidContactsColumns.DATA2]?.toIntOrNull()
    val customLabel = record.values[AndroidContactsColumns.DATA3]
    Email(
        address = address,
        type = mapAndroidEmailType(typeInt, customLabel),
        label = customLabel
    )
}

/** بناء المعلومات الوظيفية من سجلات `CommonDataKinds.Organization` (DATA1=Company, DATA3=Title, DATA4=Dept). */
private fun AndroidDataRecord.toAndroidOrganization(): Organization? {
    val company = values[AndroidContactsColumns.DATA1]
    val department = values[AndroidContactsColumns.DATA4]
    val jobTitle = values[AndroidContactsColumns.DATA3]
    if (company.isNullOrBlank() && jobTitle.isNullOrBlank() && department.isNullOrBlank()) return null
    return Organization(
        company = company,
        department = department,
        jobTitles = listOfNotNull(jobTitle?.takeIf { it.isNotBlank() })
    )
}

/**
 * أنواع أرقام الهاتف حسب جدول `ContactsContract.Phone` (1=HOME, 2=MOBILE, 3=WORK,…).
 * غير المعرف/المخصص يعرض علامة أخرى أو التسمية المخصصة من `DATA3`.
 */
internal fun mapAndroidPhoneType(typeInt: Int?, customLabel: String?): String = when (typeInt) {
    1 -> "HOME"
    2 -> "MOBILE"
    3 -> "WORK"
    4 -> "WORK_FAX"
    5 -> "HOME_FAX"
    6 -> "PAGER"
    7 -> "OTHER"
    8 -> "CALLBACK"
    9 -> "CAR"
    10 -> "COMPANY_MAIN"
    12 -> "MAIN"
    16 -> "WORK_MOBILE"
    17 -> "WORK_PAGER"
    else -> customLabel?.takeIf { it.isNotBlank() } ?: "OTHER"
}

/** أنواع البريد حسب جدول `ContactsContract.Email` (1=HOME, 2=WORK, 3=OTHER, 4=MOBILE). */
internal fun mapAndroidEmailType(typeInt: Int?, customLabel: String?): String = when (typeInt) {
    1 -> "HOME"
    2 -> "WORK"
    4 -> "MOBILE"
    else -> customLabel?.takeIf { it.isNotBlank() } ?: "PERSONAL"
}
