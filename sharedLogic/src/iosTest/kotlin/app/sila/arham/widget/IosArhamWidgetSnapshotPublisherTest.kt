package app.sila.arham.widget

import app.sila.core.common.AppClock
import app.sila.core.model.ArhamStatus
import app.sila.core.model.Relation
import app.sila.feature.arham.domain.ArhamWidgetEntry
import app.sila.feature.arham.domain.ArhamWidgetSnapshot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.SEEK_END
import platform.posix.SEEK_SET

/**
 * اختبارات ناشر لقطة الودجت على iOS (T8-13 · D6 · AC-15).
 *
 * حاوية App Group تُستبدل بمجلد مؤقت فريد لكل اختبار (`NSTemporaryDirectory`)، ومُشغِّل
 * إعادة التحميل مُرسَب يعدّ الاستدعاءات. الدلالات المُثبتة: الكتابة الذرّية في جذر الحاوية،
 * الصمت عند غياب الحاوية أو فشل الكتابة (بلا استدعاء للمُشغِّل)، وذوبان انفجارات الأحداث
 * في نشرٍ واحد عبر قناة CONFLATED (مرآة `ExistingWorkPolicy.REPLACE` الأندرويدية).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosArhamWidgetSnapshotPublisherTest {

    /** الساعة الثابتة — تُثبت وصول زمن النشر إلى حقل `generatedAtMillis` في الملف. */
    private val fixedMillis = 1_760_000_000_000L

    private val clock = object : AppClock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(fixedMillis)
    }

    private val snapshot = ArhamWidgetSnapshot(
        overdueCount = 2,
        entries = listOf(
            ArhamWidgetEntry(
                contactId = 1,
                displayName = "الأم",
                relation = Relation.MOTHER,
                daysSinceLastContact = 9,
                status = ArhamStatus.OVERDUE,
            ),
            ArhamWidgetEntry(
                contactId = 2,
                displayName = "الأب",
                relation = Relation.FATHER,
                daysSinceLastContact = 3,
                status = ArhamStatus.SOON,
            ),
        ),
        totalFamilyCalls = 14,
        longestCallSec = 321,
        goalCompletionFraction = 0.75f,
    )

    // ---------------------------------------------------------------- أدوات

    /** مجلد مؤقت فريد لكل اختبار — يحاكي جذر حاوية App Group بلا لمسٍ للبيئة الحقيقية. */
    @OptIn(ExperimentalForeignApi::class)
    private fun newContainerDir(): String {
        val dir = NSTemporaryDirectory() + "t813-widget-" + Random.nextLong() + "/"
        val created = NSFileManager.defaultManager.createDirectoryAtPath(
            path = dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        assertTrue(created, "مجلد الحاوية المؤقت يجب أن يُنشأ: $dir")
        return dir
    }

    private fun publisher(
        containerPath: String?,
        trigger: (() -> Unit)? = null,
        snapshotProvider: suspend () -> ArhamWidgetSnapshot = { snapshot },
    ): IosArhamWidgetSnapshotPublisher =
        IosArhamWidgetSnapshotPublisher(
            snapshotProvider = snapshotProvider,
            clock = clock,
            containerPathProvider = { containerPath },
            publishingContext = Dispatchers.Unconfined,
        ).also { it.setReloadTrigger(trigger) }

    private fun fileExists(path: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(path)

    /** قراءة الملف المكتوب (POSIX — الخيط الأعكس لـ writeAtomically) لتفكيك التسلسل حول الملف الفعلي. */
    @OptIn(ExperimentalForeignApi::class)
    private fun readText(path: String): String? {
        val file = fopen(path, "rb") ?: return null
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            fseek(file, 0, SEEK_SET)
            if (size <= 0L) return ""
            val buffer = ByteArray(size.toInt())
            val read = buffer.usePinned { pinned ->
                fread(pinned.addressOf(0), 1u, size.toULong(), file)
            }
            if (read != size.toULong()) return null
            return buffer.decodeToString()
        } finally {
            fclose(file)
        }
    }

    // ---------------------------------------------------------------- الاختبارات

    @Test
    fun publishOnce_writesSnapshotJsonIntoContainerRoot_andInvokesTheReloadTrigger() = runTest {
        val dir = newContainerDir()
        var triggers = 0
        val publisher = publisher(containerPath = dir, trigger = { triggers++ })

        publisher.publishOnce()

        val file = dir + IosArhamWidgetSnapshotPublisher.SNAPSHOT_FILE_NAME
        assertTrue(fileExists(file), "اللقطة تُكتب في جذر الحاوية باسم الملف الثابت")

        val text = assertNotNull(readText(file), "الملف المكتوب قابل للقراءة بترميز UTF-8")
        val decoded = Json.decodeFromString<IosArhamWidgetSnapshotDto>(text)
        assertEquals(fixedMillis, decoded.generatedAtMillis, "زمن النشر من الساعة المحقونة حصراً")
        assertEquals(
            snapshot,
            decoded.toDomain(),
            "ذهاباً وإياباً: المجال ← DTO ← JSON ← ملف ← DTO ← المجال بلا فاقد",
        )
        assertEquals(1, triggers, "الكتابة الناجحة تستدعي مُشغِّل إعادة التحميل مرة واحدة")
    }

    @Test
    fun publishOnce_republishesOverTheSameFile_andFiresTheTriggerForEachSuccess() = runTest {
        val dir = newContainerDir()
        var triggers = 0
        val publisher = publisher(containerPath = dir, trigger = { triggers++ })

        publisher.publishOnce()
        publisher.publishOnce()

        assertEquals(2, triggers, "كل كتابة ناجحة تُعيد التحميل — الاستبدال بالمُعرّف لا التراكم")
        assertTrue(
            fileExists(dir + IosArhamWidgetSnapshotPublisher.SNAPSHOT_FILE_NAME),
            "الملف نفسه يُستبدل ذرّياً في كل نشر",
        )
    }

    @Test
    fun publishOnce_skipsQuietly_whenTheAppGroupContainerIsUnavailable() = runTest {
        var triggers = 0
        val publisher = publisher(containerPath = null, trigger = { triggers++ })

        publisher.publishOnce() // بلا حاوية (بناء غير مُوقّع مثلاً): صمت تام بلا استثناء

        assertEquals(0, triggers, "بلا كتابة ⇒ بلا إعادة تحميل")
    }

    @Test
    fun publishOnce_skipsTheReloadTrigger_whenTheWriteFails() = runTest {
        val dir = newContainerDir()
        var triggers = 0
        // المسار الأب غائب ⇒ writeToFile يفشل — مرآة حاوية App Group غير مُوثقة
        val badPath = dir + "missing-parent/"
        val publisher = publisher(containerPath = badPath, trigger = { triggers++ })

        publisher.publishOnce()

        assertEquals(0, triggers, "فشل الكتابة يمنع إعادة التحميل — الودجت يبقى على لقطته السابقة")
        assertFalse(
            fileExists(badPath + IosArhamWidgetSnapshotPublisher.SNAPSHOT_FILE_NAME),
            "بلا ملف مُنشأ في مسار أبٍ غائب",
        )
    }

    @Test
    fun publishOnce_withoutAnAttachedTrigger_writesTheFileQuietly() = runTest {
        val dir = newContainerDir()
        val publisher = publisher(containerPath = dir, trigger = null)

        publisher.publishOnce()

        assertTrue(
            fileExists(dir + IosArhamWidgetSnapshotPublisher.SNAPSHOT_FILE_NAME),
            "غياب المُشغِّل (اختبارات/ميل Swift) لا يمنع الكتابة — الودجت يلتقط الملف في دورته",
        )
    }

    @Test
    fun onArhamDataChanged_coalescesABurstOfEvents_intoASinglePendingPublish() = runTest {
        val dir = newContainerDir()
        val gate = CompletableDeferred<Unit>()
        var publishes = 0
        val publisher = IosArhamWidgetSnapshotPublisher(
            snapshotProvider = {
                publishes++
                gate.await() // publishOnce الأولى تبقى في منتصفها
                snapshot
            },
            clock = clock,
            containerPathProvider = { dir },
            publishingContext = Dispatchers.Unconfined,
        )

        publisher.onArhamDataChanged()
        assertEquals(1, publishes, "الحدث الأول يبدأ النشر فوراً (Unconfined)")

        publisher.onArhamDataChanged()
        publisher.onArhamDataChanged()
        publisher.onArhamDataChanged()
        assertEquals(1, publishes, "انفجار الأحداث أثناء نشرٍ جارٍ لا يُشغل دورات متوازية")

        gate.complete(Unit)
        assertEquals(
            2,
            publishes,
            "قناة CONFLATED تذوب المتلاحق في نشرٍ واحد بعد انصراف الأول (REPLACE بلا WorkManager)",
        )
    }
}
