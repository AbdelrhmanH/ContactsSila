package app.sila.feature.contacts.data

import app.sila.core.model.ContactName
import app.sila.core.model.Email
import app.sila.core.model.Organization
import app.sila.core.model.Phone
import app.sila.feature.contacts.domain.AndroidRawContactState
import app.sila.feature.contacts.domain.ContactsDelta
import app.sila.feature.contacts.domain.ContactsSnapshot
import app.sila.feature.contacts.domain.PlatformContact

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
    const val DATA8 = "data8"
    const val DATA9 = "data9"
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

    // 3) فصل/حذف أحد أعضاء التجميع (RawContact) لا يعني حذف جهة الاتصال المجمّعة نفسها:
    //    يُلغى الحذف المُبلَّغ عن أي مفتاح LOOKUP_KEY ما دام هناك سجل خام آخر نشط (بلا
    //    DELETED=1) بنفس المفتاح المحفوظ — وإلا حُذفت جهة حيّة من Room (أخطاء المزامنة).
    val activeRawIds = currentRows.filter { !it.deleted }.map { it.rawContactId }.toSet()
    val stillActiveLookupKeys = savedRawContacts
        .filterKeys { it in activeRawIds }
        .values
        .mapNotNull { it.lookupKey }
        .toSet()
    deletedLookupKeys.removeAll(stillActiveLookupKeys)

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
 * المفاتيح القديمة الملغاة (superseded) لسجلات خام تغيّر `LOOKUP_KEY` لها بعد آخر لقطة —
 * بدلالة التجمّعة (aggregate-centric).
 *
 * المزود يُعيد توليد `LOOKUP_KEY` عند إعادة التجميع/بعض المزامنات (ADR-0009 §4 يعترف
 * بتغيره)، والهوية في Room فريدة على `systemContactId` — فدلتا تُفسّر upsert بالمفتاح
 * الجديد دون بلاغ عن القديم تُراكم صفوف مكررة لنفس الشخص. المفتاح القديم هنا «هوية ماتت»
 * تُبلَّغ في `deletedSystemContactIds` مع upsert المفتاح الجديد.
 *
 * الحيوية تُحسم لكل سجل خام متغير: مفتاحه القديم لا يُبلَّغ محذوفاً إلا إن كان ميتاً
 * فعلاً — لا يحمله سجل خام **غير متغير** تجمّعته لم تُجلب من جديد (مفتاحه المحفوظ ما يزال
 * مفتاح المزود الحالي). إعادة ربط سجل بتجمّعة أخرى (F1 في مراجعة PR #32) لا تُبطل مفتاح
 * التجمّعة القديمة: الشقيقات غير المتغيرة تحمله حية، والإبلاغ عنه كان يمحو جهة حيّة من
 * Room. المفاتيح تأتي من [diffRawContactVersions] المسح الكامل، فلا يُبلَّغ إلا عن مفاتيح
 * سجلات متغيرة فعلاً — المسح الناقص لا يمس هذا المسار (مصالحة المسح الكامل مؤجلة لعنصر
 * المزامنة — ADR-0010 §3).
 */
internal fun supersededLookupKeys(
    resolvedLookupKeys: Map<String, String>,
    changedAggregatesById: Map<String, Long?>,
    savedRawContacts: Map<String, AndroidRawContactState>,
): List<String> {
    val changedRawIds = changedAggregatesById.keys
    val changedAggregateIds = changedAggregatesById.values.filterNotNull().toSet()
    // المفاتيح الحيّة الموثوقة: سجلات خام لم تتغير إصدارها وتجمّعتها لم تُجلب من جديد.
    val trustedLiveKeys = (
        resolvedLookupKeys.values +
            savedRawContacts
                .filterKeys { it !in changedRawIds }
                .filterValues { it.aggregateContactId == null || it.aggregateContactId !in changedAggregateIds }
                .values.mapNotNull { it.lookupKey }
        ).toSet()
    return resolvedLookupKeys.mapNotNull { (rawId, currentKey) ->
        val savedKey = savedRawContacts[rawId]?.lookupKey
        savedKey?.takeIf { it != currentKey && it !in trustedLiveKeys }
    }.distinct()
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
 * زوج (معرف مجمّعة، `LOOKUP_KEY`) كما تعيده قراءة استعلام المزوّد — المفتاح قد يكون `null`.
 * الصفوف المُدخلة هي **العائدة فعلاً من المزوّد** لا معرفات المسح (شرط C-6).
 */
internal data class ResolvedAggregateLookup(
    val aggregateContactId: Long,
    val lookupKey: String?,
)

/**
 * فهرس `LOOKUP_KEY` بمعرف التجمّعة من أزواج استعلام المزوّد — الفلتر الوحيد المشترك
 * (مفتاح غير فارغ ∧ معرف موجب) الذي يبني «المُعادَّ» ويغذي حلّ مفاتيح السجلات الخام.
 * «غير فارغ» حرفياً: غير `null` وغير السلسلة الفارغة/الفراغات (`isNotBlank`) — المفتاح
 * الفارغ لا يصلح هوية استيراد (`systemContactId`) فليست صفه في الكتالوج القابل للاستيراد.
 * الصفوف المُدخلة هي **العائدة فعلاً من المزوّد** لا معرفات المسح (شرط C-6).
 */
internal fun resolvableLookupIndex(
    resolvedPairs: List<ResolvedAggregateLookup>,
): Map<Long, String> = resolvedPairs.mapNotNull { pair ->
    pair.lookupKey
        ?.takeIf { key -> pair.aggregateContactId > 0L && key.isNotBlank() }
        ?.let { key -> pair.aggregateContactId to key }
}.toMap()

/**
 * استخراج «المجموعة المُعادَّة» (`resolvableAggregateContactIds`) — شرط C-1 من بوابة
 * مراجعة التصميم (`G7_DESIGN_REVIEW_GATE.md`): دالة نقية قابلة للاختبار على JVM حتى
 * لا يصمت انحسار التوصيل في `androidMain` (F-1/High).
 *
 * المجموعة = معرفات التجمّع **المعلنة الحية الموجبة في المسح** ∧ **العائدة فعلاً من
 * استعلام المزوّد بمفتاح `LOOKUP_KEY` غير فارغ** ([resolvableLookupIndex]):
 * - مفتاح `null` أو فارغ («»/فراغات) ⇒ ليس مُعادَّ (لا يمكن بناء استيراد سليم منها —
 *   `mapAndroidContactRows` يرمي بلا مفتاح، والمفتاح الفارغ لا يصلح هوية استيراد).
 * - معرف `0` أو سالب ⇒ ليس مُعادَّ (`Contacts._ID` يبدأ من 1؛ `0` القيمة المرسلة
 *   «بلا تجمّعة بعد»).
 * - معرف لم يعلنه سجل خام حي (محذوف أو غائب عن المسح) ⇒ ليس مُعادَّ — التعريف من
 *   أزواج المزوّد لا من معرفات المسح وحدها (شرط C-6)، والقياس مستقل عن ترقيم
 *   `Contacts` (وهو ما يجعل التمييز بين «معلّق» و«مقطوع» ممكناً — مانع التافتة).
 * - توقيت القياس: قبل الترقيم في المسار الكامل (`fullResyncDelta` في `androidMain`) —
 *   «مُعادَّ» = **وقت القياس** (نافذة تعديل متزامنة فشل-آمن: تعطي فشلاً كاذباً عابراً
 *   يشفى بإعادة المحاولة وفق ADR-0010 §8).
 *
 * **دقة دلالية (شرط C-4):** «مُعادَّ» شرط **ضروري غير كافٍ** للاستيراد (يُضاف إليه
 * الاسم) — الإعفاء للغير-مُعادَّ مبنى على عدم القابلية-بنائها، وهي فجوة مُعادَّ-بلا-اسم
 * سابقة موثقة كالمخاطرة المتبقية (1) في ADR-0022 لا يوسّعها هذا الإصلاح.
 */
internal fun extractResolvableAggregateContactIds(
    scanRows: List<RawContactScanRow>,
    resolvedPairs: List<ResolvedAggregateLookup>,
): Set<Long> =
    resolvableLookupIndex(resolvedPairs).keys.intersect(declaredLivePositiveAggregateIds(scanRows))

/**
 * حكم تغطية القراءة الكاملة (FINDING-SYNC-1 · ADR-0022 · تعديل G7 2026-11-16):
 *
 * القراءة الكاملة «ناقصة» إذا خلت صفوف `Contacts` المقروءة للتصدير عن تغطية **الكتالوج
 * القابل للاستيراد** — أي تجمّعة [extractResolvableAggregateContactIds «مُعادَّ»] غائبة
 * عن [pagedAggregateContactIds]. المصدران جدولان واستعلامان مختلفان ومستقلان (سجلات خام
 * مقابل مجمّعة) وقد يختلفان لحظياً (تأخر تجميع أو مؤشر فارغ عابر أثناء بذر جماعي).
 * تقديم لقطة كاملة مع صفر استيراد في هذه الحالة يختم بوابة المصالحة على دفتر فارغ
 * ويجمّد إصدارات السجلات الخام دون استيرادها، فيغيب أصحابها عن الدلتا التزايدية إلى
 * المصالحة التالية (العطل المرصود: لقطة 5000 سجل + ختم بينما `contacts = 0` في Room
 * لأكثر من 13 دقيقة).
 *
 * نطاق الحكم تغطية **المعرفات المجمّعة** حصراً، مع ثلاث فئات إعفاء:
 * - الصفوف `DELETED=1` والسجلات بلا تجمّعة بعد (بما فيها القيمة المرسلة `CONTACT_ID = 0`
 *   عند بعض مزودات البائعين قبل إسناد تجمّعة) لا تُطالَب تغطية.
 * - **المرجع المعلّق (إعفاء G7):** تجمّعة معلنة موجبة حية **غير مُعادَّ** — لا صف لها في
 *   `Contacts` ⇒ لا اسم ولا `LOOKUP_KEY` ⇒ غير قابلة للاستيراد بنائها ([mapAndroidContactRows]
 *   يرمي بلا مفتاح)، فمطالبتها «تغطيةً» مطالبة بالمستحيل — ويشمل المعيار البنيوي ذاته
 *   الصف الموجود بمفتاح `LOOKUP_KEY` غير صالح للاستيراد (فارغ/فراغات). مرصود ميدانياً على MIUI
 *   (`DIAG_rootcause_summary.md`: 804 معلنة / 717 مُعادَّ — 63 مزامنة تيليجرام و34
 *   واتساب قراءة-فقط، تقلب 87–97 مع مزامنة الحسابات). محايد-المزود: بالبنية لا بالاسم.
 * - كتالوج فارغ (لا مُعادَّ) مع صفر upserts = دفتر فارغ شرعي؛ وفي حالة البذر العابر
 *   (كتالوج فارغ + مسح غير فارغ) يمر هذا الحكم ويَرفُض **حارس المحرك الثالث**
 *   «صفر استيراد مع لقطة غير فارغة» — تحوّل موضع الرفض موثق في تعديل ADR-0022 (G7).
 * - الإسقاط في التحويل النهائي (صف بلا اسم وبلا مفتاح) خارج نطاق هذا الحكم — سلوك
 *   سابق مستقل (يُوثَّق كمخاطرة متبقية في ADR-0022).
 *
 * **استقلالية القياس (مانع التافتة):** [resolvableAggregateContactIds] يقاس من استعلام
 * حلّ المفاتيح **قبل** الترقيم، و[pagedAggregateContactIds] من الترقيم ذاته — لولا
 * الاستقلالية لصار «المطلوب = المقروء» تافتة تُعطّل حماية القطع الجزئي. نافذة تعديل
 * متزامنة بين القياسين فشل-آمن فقط: قد تنتج فشلاً كاذباً عابراً يشفى بإعادة المحاولة
 * (ADR-0010 §8) — لا اتجاه «يمرّ الحارس مع فاقد حقيقي» (تحليل البوابة B-1).
 *
 * **دقة دلالية (شرط C-4):** «مُعادَّ» شرط ضروري غير كافٍ للاستيراد (يضاف إليه الاسم) —
 * والفجوة مُعادَّ-بلا-اسم سابقة موثقة في ADR-0022 لا يوسّعها ولا يضيقها هذا الإصلاح.
 */
internal fun isFullReadIncomplete(
    scanRows: List<RawContactScanRow>,
    resolvableAggregateContactIds: Set<Long>,
    pagedAggregateContactIds: Set<Long>,
): Boolean {
    val expectedAggregateIds =
        declaredLivePositiveAggregateIds(scanRows) intersect resolvableAggregateContactIds
    if (expectedAggregateIds.isEmpty()) return false
    return !pagedAggregateContactIds.containsAll(expectedAggregateIds)
}

/** المعرفات المجمّعة المعلنة من صفوف المسح الحية الموجبة (الإعفاءان الأولان). */
private fun declaredLivePositiveAggregateIds(scanRows: List<RawContactScanRow>): Set<Long> =
    scanRows.asSequence()
        .filterNot { it.deleted }
        .mapNotNull { it.aggregateContactId?.takeIf { id -> id > 0L } }
        .toSet()

/**
 * قرار دلتا إعادة المزامنة الكاملة لنقطة التفتيش (FINDING-SYNC-1 · ADR-0022 · G7):
 *
 * دمج حكم التغطية [isFullReadIncomplete] مع بناء اللقطة في قرار واحد نقي قابل
 * للاختبار على JVM بلا `ContentResolver` — «مسح غير فارغ + قراءة تجمّعية بلا تغطية»
 * يُعاد [ContactsDelta.newSnapshot] = `null` فلا يقدَّم شيء (ADR-0010 §8)، وإلا
 * تُبنى اللقطة بلحظة T0 عبر [buildNextAndroidSnapshot] وتُختم بعد اكتمال الاستيراد.
 * [resolvableAggregateContactIds] تمرر كما هي من التوصيل إلى حكم التغطية — نطاق
 * التغطية هو الكتالوج القابل للاستيراد (تعديل G7 2026-11-16).
 * القراءة الكاملة لا تحذف أبداً — الحذف يبقى مصالحة كاملة دورية (ADR-0010 §3).
 */
internal fun buildFullResyncDelta(
    scanRows: List<RawContactScanRow>,
    resolvableAggregateContactIds: Set<Long>,
    pagedAggregateIds: Set<Long>,
    resolvedLookupKeys: Map<String, String>,
    upserted: List<PlatformContact>,
    previousSnapshot: ContactsSnapshot?,
): ContactsDelta {
    if (isFullReadIncomplete(
            scanRows = scanRows,
            resolvableAggregateContactIds = resolvableAggregateContactIds,
            pagedAggregateContactIds = pagedAggregateIds
        )
    ) {
        return ContactsDelta(
            upserted = upserted,
            deletedSystemContactIds = emptyList(),
            newSnapshot = null,
            isFullResync = true
        )
    }
    return ContactsDelta(
        upserted = upserted,
        deletedSystemContactIds = emptyList(),
        newSnapshot = buildNextAndroidSnapshot(scanRows, resolvedLookupKeys, previousSnapshot),
        isFullResync = true
    )
}

/**
 * تحويل صفوف `Contacts` + سجلات `Data` المنتمية إليها إلى قائمة [PlatformContact] —
 * المفتاح الموحّد يأتي من `LOOKUP_KEY` حصراً، ولا يُخزن `Contacts._ID` (ADR-0009 §4).
 * المفتاح الموحّد يجب أن يكون **غير فارغ** حرفياً (غير `null` وغير «»/فراغات) — الصف
 * بمفتاح منحل يُرمى معاملة بلا مفتاح: هوية «» تنهار عبر upsert أول-مطابقة فتسقط جهات
 * متعددة في صف واحد (مواءمة مع عقد «غير فارغ» في [resolvableLookupIndex]).
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
            // أعمدة CommonDataKinds.StructuredName الحقيقية (AOSP):
            // DISPLAY_NAME=DATA1, GIVEN_NAME=DATA2, FAMILY_NAME=DATA3, PREFIX=DATA4,
            // MIDDLE_NAME=DATA5, SUFFIX=DATA6, PHONETIC_GIVEN/MIDDLE/FAMILY=DATA7/8/9.
            prefix = nameRecord?.values?.get(AndroidContactsColumns.DATA4),
            firstName = nameRecord?.values?.get(AndroidContactsColumns.DATA2),
            middleName = nameRecord?.values?.get(AndroidContactsColumns.DATA5),
            lastName = nameRecord?.values?.get(AndroidContactsColumns.DATA3),
            suffix = nameRecord?.values?.get(AndroidContactsColumns.DATA6),
            nickname = null,
            phoneticName = listOfNotNull(
                nameRecord?.values?.get(AndroidContactsColumns.DATA7),
                nameRecord?.values?.get(AndroidContactsColumns.DATA8),
                nameRecord?.values?.get(AndroidContactsColumns.DATA9)
            ).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }
        )
        PlatformContact(
            systemContactId = row.lookupKey?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null,
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

/** بناء قائمة الهواتف من سجلات `CommonDataKinds.Phone` (NUMBER=DATA1, TYPE=DATA2, LABEL=DATA3, NORMALIZED_NUMBER=DATA4). */
private fun List<AndroidDataRecord>.toAndroidPhones(): List<Phone> = mapNotNull { record ->
    val rawNumber = record.values[AndroidContactsColumns.DATA1] ?: return@mapNotNull null
    val typeInt = record.values[AndroidContactsColumns.DATA2]?.toIntOrNull()
    val customLabel = record.values[AndroidContactsColumns.DATA3]
    Phone(
        raw = rawNumber,
        e164 = record.values[AndroidContactsColumns.DATA4]?.takeIf { it.isNotBlank() } ?: rawNumber,
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

/** بناء المعلومات الوظيفية من سجلات `CommonDataKinds.Organization` (COMPANY=DATA1, TITLE=DATA4, DEPARTMENT=DATA5). */
private fun AndroidDataRecord.toAndroidOrganization(): Organization? {
    val company = values[AndroidContactsColumns.DATA1]
    val department = values[AndroidContactsColumns.DATA5]
    val jobTitle = values[AndroidContactsColumns.DATA4]
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
