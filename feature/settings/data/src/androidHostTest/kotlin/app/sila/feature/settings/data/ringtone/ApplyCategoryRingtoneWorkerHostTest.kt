package app.sila.feature.settings.data.ringtone

import android.Manifest
import android.content.Context
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.sila.core.model.Category
import app.sila.core.model.Contact
import app.sila.core.model.ContactName
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * اختبارات مضيف عامل تطبيق نغمة التصنيف (S2-10 · تذكرة #63 · معيار «اختبارات Worker
 * بلا Koin حقيقية») فوق Robolectric وWorkManager الاختباري: الحقن بإنشاء مباشر
 * ([CategoryMembersSource]/[ContactRingtoneWriteBoundary]) — فالشروط الثلاثة في
 * التذكرة مثبتة حرفاً: بلا نغمة فردية ⇒ تطبيق نغمة الفئة؛ صفر أعضاء ⇒ سلوك موثق
 * بعدّ لا نجاح منظر؛ التهيئة الغائبة ⇒ `retry` بدل النجاح الصامت — والصلاحية
 * المرفوضة تخطٍّ موثق في ناتج العمل (عهد ADR-0009/0012)، وتغيير نغمة الفئة
 * يُعيد التطبيق عبر الاسم الفريد REPLACE بلا تراكم.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApplyCategoryRingtoneWorkerHostTest {

    /** مصدر أعضاء ثابت بلا قاعدة بيانات — `null` يجسّد «التهيئة غير محسومة». */
    private class FixedMembersSource(private val contacts: List<Contact>?) : CategoryMembersSource {
        var requestedCategories = mutableListOf<Category>()

        override suspend fun contactsFor(category: Category): List<Contact>? {
            requestedCategories += category
            return contacts
        }
    }

    /** مسجّل بوبة الكتابة: يوثق من طُلب له الكتابة وبأي نغمة — وبرمجة فشل/رمي لعضو واحد. */
    private class RecordingWriteBoundary : ContactRingtoneWriteBoundary {
        data class Write(val contactId: Long, val ringtoneUri: String?)

        val writes = mutableListOf<Write>()
        var failForContactIds: Set<Long> = emptySet()
        var throwForContactIds: Set<Long> = emptySet()

        override fun updateCustomRingtone(contact: Contact, ringtoneUri: String?): Boolean {
            if (contact.id in throwForContactIds) {
                throw IllegalStateException("حقن فشل كتابة وسط الدفعة (اختبار S2-10)")
            }
            writes += Write(contact.id, ringtoneUri)
            return contact.id !in failForContactIds
        }
    }

    private val app get() = RuntimeEnvironment.getApplication()

    private fun contact(
        id: Long,
        category: Category,
        ringtoneUri: String? = null,
        lookupKey: String? = "lookup_$id",
    ) = Contact(
        id = id,
        systemContactId = "sys_$id",
        lookupKey = lookupKey,
        name = ContactName(displayName = "جهة $id"),
        category = category,
        ringtoneUri = ringtoneUri,
    )

    private fun worker(
        source: CategoryMembersSource?,
        boundary: ContactRingtoneWriteBoundary?,
        data: Data = ApplyCategoryRingtoneWorker.workData(Category.FAMILY, "content://media/family.ogg"),
        grantPermission: Boolean = true,
    ): ApplyCategoryRingtoneWorker {
        if (grantPermission) shadowOf(app).grantPermissions(Manifest.permission.WRITE_CONTACTS)
        return TestListenableWorkerBuilder<ApplyCategoryRingtoneWorker>(app)
            .setInputData(data)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    parameters: WorkerParameters,
                ): ListenableWorker = ApplyCategoryRingtoneWorker(appContext, parameters, source, boundary)
            })
            .build()
    }

    // ===== البند الأول: بلا نغمة فردية ⇒ تطبيق نغمة الفئة =====

    @Test
    fun `member without individual ringtone gets the category ringtone written`() = runBlocking {
        val boundary = RecordingWriteBoundary()
        val source = FixedMembersSource(
            listOf(
                contact(1L, Category.FAMILY), // بلا نغمة فردية — الهدف
                contact(2L, Category.FAMILY, ringtoneUri = "content://individual/2"), // فردية — لا تُمسّ
            )
        )
        val result = worker(source, boundary).doWork()

        assertEquals(
            listOf(RecordingWriteBoundary.Write(1L, "content://media/family.ogg")),
            boundary.writes,
            "بلا نغمة فردية ⇒ نغمة الفئة تُكتب له حصراً؛ صاحب الفردية لا يُلمس (عهد §11)",
        )
        assertEquals(
            ListenableWorker.Result.success(
                workDataOf(
                    ApplyCategoryRingtoneWorker.KEY_PENDING_MEMBERS to 1,
                    ApplyCategoryRingtoneWorker.KEY_WRITTEN_COUNT to 1,
                    ApplyCategoryRingtoneWorker.KEY_REASON to ApplyCategoryRingtoneWorker.REASON_APPLIED,
                )
            ),
            result,
            "كل كتابة تُحصى — النتيجة تعلن ما فعلته",
        )
        assertEquals(listOf(Category.FAMILY), source.requestedCategories, "طلب الأعضاء بالإسناد الصحيح (الفئة)")
    }

    // ===== البند الثاني: صفر أعضاء ⇒ سلوك موثق لا نجاح منظر =====

    @Test
    fun `zero category members yields documented success with zero writes`() = runBlocking {
        val boundary = RecordingWriteBoundary()
        val result = worker(FixedMembersSource(emptyList()), boundary).doWork()

        assertEquals(
            ListenableWorker.Result.success(
                workDataOf(
                    ApplyCategoryRingtoneWorker.KEY_PENDING_MEMBERS to 0,
                    ApplyCategoryRingtoneWorker.KEY_WRITTEN_COUNT to 0,
                    ApplyCategoryRingtoneWorker.KEY_REASON to ApplyCategoryRingtoneWorker.REASON_APPLIED,
                )
            ),
            result,
            "صفر جهات ⇒ نجاح موثق بعدّ صفر — ناتج العمل يعلنه لا نجاح بصمت",
        )
        assertTrue(boundary.writes.isEmpty(), "لا أعضاء ⇒ صفر محاولات كتابة")
    }

    @Test
    fun `members with individual ringtones only count as pending zero`() = runBlocking {
        val boundary = RecordingWriteBoundary()
        val result = worker(
            FixedMembersSource(listOf(contact(1L, Category.WORK, ringtoneUri = "content://individual/1"))),
            boundary,
        ).doWork()

        assertEquals(
            workDataOf(
                ApplyCategoryRingtoneWorker.KEY_PENDING_MEMBERS to 0,
                ApplyCategoryRingtoneWorker.KEY_WRITTEN_COUNT to 0,
                ApplyCategoryRingtoneWorker.KEY_REASON to ApplyCategoryRingtoneWorker.REASON_APPLIED,
            ),
            CheckOutcomeData.from(result),
            "جميعهم لديهم فردية ⇒ عضوية صفر موثقة وبلا كتابات",
        )
        assertTrue(boundary.writes.isEmpty())
    }

    // ===== البند الثالث: التهيئة الغائبة ⇒ retry بدل النجاح الصامت =====

    @Test
    fun `unresolved setup retries instead of silent success`() = runBlocking {
        // بلا حقن ولا Koin (لم تُشغَل في هذا الصنف أبداً) — الممرّ الافتراضي يجد الحاوية فارغة
        // ويُبنى العامل عبر المصنع الافتراضي بالانعكاس (حارس للبنت الثنائي في الإنتاج).
        val result = worker(source = null, boundary = RecordingWriteBoundary()).doWork()

        assertEquals(
            ListenableWorker.Result.retry(),
            result,
            "حاوية غائبة (عملية مبكرة) ⇒ إعادة مؤقتة — لا نجاح منظر (S2-10)",
        )
    }

    // ===== الصلاحية المرفوضة ⇒ تخطٍّ موثق (ADR-0009/0012) =====

    @Test
    fun `permission denied is a documented skip with zero touch`() = runBlocking {
        val boundary = RecordingWriteBoundary()
        val source = FixedMembersSource(listOf(contact(1L, Category.FAMILY)))
        val result = worker(source, boundary, grantPermission = false).doWork()

        assertEquals(
            workDataOf(
                ApplyCategoryRingtoneWorker.KEY_PENDING_MEMBERS to 0,
                ApplyCategoryRingtoneWorker.KEY_WRITTEN_COUNT to 0,
                ApplyCategoryRingtoneWorker.KEY_REASON to ApplyCategoryRingtoneWorker.REASON_PERMISSION_DENIED,
            ),
            CheckOutcomeData.from(result),
            "بلا WRITE_CONTACTS ⇒ skip موثق في الناتج (لا لمس جهات ولا نجاح صامت)",
        )
        assertTrue(boundary.writes.isEmpty(), "بلا صلاحية ⇒ صفر كتابات إطلاقاً")
        assertTrue(source.requestedCategories.isEmpty(), "حتى قراءة الأعضاء لا تحدث قبل حرس الصلاحية")
    }

    // ===== أوساط العمل: رمي/جفاف كتابة ⇒ retry، وجفاف مفرد يُحصى لا يُبتلَع =====

    @Test
    fun `mid-batch exception yields retry`() = runBlocking {
        val boundary = RecordingWriteBoundary().apply { throwForContactIds = setOf(1L) }
        val result = worker(
            FixedMembersSource(listOf(contact(1L, Category.FAMILY))),
            boundary,
        ).doWork()

        assertEquals(
            ListenableWorker.Result.retry(),
            result,
            "كوارث وسط العمل ⇒ retry (تُدار بلا ابتلاع — عهد doWork السابق محفوظ)",
        )
    }

    @Test
    fun `per-member write failure is counted as not written`() = runBlocking {
        val boundary = RecordingWriteBoundary().apply { failForContactIds = setOf(1L) }
        val result = worker(
            FixedMembersSource(listOf(contact(1L, Category.FAMILY))),
            boundary,
        ).doWork()

        assertEquals(
            workDataOf(
                ApplyCategoryRingtoneWorker.KEY_PENDING_MEMBERS to 1,
                ApplyCategoryRingtoneWorker.KEY_WRITTEN_COUNT to 0,
                ApplyCategoryRingtoneWorker.KEY_REASON to ApplyCategoryRingtoneWorker.REASON_APPLIED,
            ),
            CheckOutcomeData.from(result),
            "فشل كتابة مفرد ⇒ محاولة موثقة وعدّ صفر كتابات — لا ابتلاع ولا رمياً",
        )
        assertEquals(1, boundary.writes.size, "محاولة الكتابة تمت ودمُ قياسها")
    }

    // ===== تغيير نغمة الفئة ⇒ إعادة تطبيق بلا تراكم (الاسم الفريد REPLACE) =====

    @Test
    fun `re-setting a category ringtone replaces the unique work`() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val source = AndroidRingtoneSource(app)
        source.applyCategoryRingtone(Category.FAMILY, "content://old.ogg")
        source.applyCategoryRingtone(Category.FAMILY, "content://new.ogg")

        val infos = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(ApplyCategoryRingtoneWorker.WORK_NAME_PREFIX + Category.FAMILY.name)
            .get()

        assertEquals(1, infos.size, "تغيير نغمة الفئة ⇒ إعادة تطبيق واحدة معلَّقة — REPLACE تدمج لا تُراكم")
    }

    /** القراءة الوثائقية لناتج العمل من نتيجة (استخراج الحزمة فقط لبلاغات نجاح). */
    private object CheckOutcomeData {
        fun from(result: ListenableWorker.Result): Data = (result as ListenableWorker.Result.Success).outputData
    }
}
