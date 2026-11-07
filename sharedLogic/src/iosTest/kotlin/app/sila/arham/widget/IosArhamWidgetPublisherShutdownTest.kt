package app.sila.arham.widget

import app.sila.core.common.AppClock
import app.sila.core.model.ArhamStatus
import app.sila.core.model.Relation
import app.sila.feature.arham.domain.ArhamWidgetEntry
import app.sila.feature.arham.domain.ArhamWidgetSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import kotlin.random.Random

/**
 * اختبارات ملكية ناشر لقطة الودجت وإغلاقه القابل للانتظار (تكامل دورة حياة الموارد —
 * دمج PR #46 في T8): كانت قناة النشر تُجمَع أبداً على نطاقٍ خاص بلا مالك ولا إغلاق —
 * فبقي الجامع معلقاً على القناة عبر إغلاق الحاوية، و`publishOnce` قد يقرأ Room من
 * حاوية مغلقة. الآن [IosArhamWidgetSnapshotPublisher.close] يغلق القناة (فُتَكتمل
 * النشر المعلق بنفسه) ثم يلغي وينتظر الجامع — والإغلاق المزدوج آمن، وما بعد الإغلاق
 * لا ينشر شيئاً. حتمي: سياق Unconfين + بوابات `CompletableDeferred` + مسار حاوية
 * حقيقي مؤقت — بلا نوم ولا افتراضات توقيت.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IosArhamWidgetPublisherShutdownTest {

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
        ),
        totalFamilyCalls = 14,
        longestCallSec = 321,
        goalCompletionFraction = 0.75f,
    )

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun newContainerDir(): String {
        val dir = NSTemporaryDirectory() + "t813-publisher-shutdown-" + Random.nextLong() + "/"
        val created = NSFileManager.defaultManager.createDirectoryAtPath(
            path = dir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        assertTrue(created, "مجلد الحاوية المؤقت يجب أن يُنشأ: $dir")
        return dir
    }

    private fun fileExists(path: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(path)

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private fun readText(path: String): String? {
        val file = fopen(path, "rb") ?: return null
        try {
            fseek(file, 0, SEEK_END)
            val size = ftell(file)
            fseek(file, 0, SEEK_SET)
            if (size <= 0L) return ""
            val buffer = ByteArray(size.toInt())
            buffer.usePinned { pinned ->
                fread(pinned.addressOf(0), 1u, size.toULong(), file)
            }
            return buffer.decodeToString()
        } finally {
            fclose(file)
        }
    }

    @Test
    fun close_awaitsInFlightPublish_writesSnapshotBeforeJoining() = runTest {
        val dir = newContainerDir()
        val snapshotPath = dir + IosArhamWidgetSnapshotPublisher.SNAPSHOT_FILE_NAME
        val publishStarted = CompletableDeferred<Unit>()
        val releasePublish = CompletableDeferred<Unit>()
        var publishCount = 0
        val publisher = IosArhamWidgetSnapshotPublisher(
            snapshotProvider = {
                publishCount++
                publishStarted.complete(Unit)
                releasePublish.await()
                snapshot
            },
            clock = clock,
            containerPathProvider = { dir },
            publishingContext = Dispatchers.Unconfined,
        )

        publisher.onArhamDataChanged() // الجامع (Unconfined) يجري حتى بوابة النشر ويتعلّق
        assertTrue(publishStarted.isCompleted, "the enqueue must start an in-flight publish")

        // الإغلاق يبدأ على خيط Default **قبل** تحرير البوابة — الانضمام ينتظر فعلياً
        // اكتمال النشر المعلق على خيطٍ آخر (لا Unconfined يخفي الانتظار)، ثم كتابة
        // الملف تُرى بعد عودة الإغلاق حصراً.
        runBlocking {
            withTimeout(5_000) {
                // UNDISPATCHED: requests.close() + التعليق على انضمام الجامع يقعان
                // حصراً قبل تحرير البوابة — الانتظار حقيقي ولا سباق ترتيب.
                val closeJob = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { publisher.close() }
                releasePublish.complete(Unit)
                closeJob.join()
            }
        }

        assertEquals(1, publishCount, "the pending publish must complete exactly once before close returns")
        assertTrue(fileExists(snapshotPath), "the in-flight snapshot must be written before close returns")
        assertTrue(readText(snapshotPath)?.contains("0.75") ?: false, "snapshot content must reflect the completed publish")
    }

    @Test
    fun noPublishAfterClose_evenOnNewEvents() = runTest {
        val dir = newContainerDir()
        val snapshotPath = dir + IosArhamWidgetSnapshotPublisher.SNAPSHOT_FILE_NAME
        var publishCount = 0
        val publisher = IosArhamWidgetSnapshotPublisher(
            snapshotProvider = {
                publishCount++
                snapshot
            },
            clock = clock,
            containerPathProvider = { dir },
            publishingContext = Dispatchers.Unconfined,
        )

        runBlocking { withTimeout(5_000) { publisher.close() } }
        publisher.onArhamDataChanged() // القناة مغلقة — المحاولة الصامتة بعد الإغلاق

        assertEquals(0, publishCount, "no publish may run after close (channel closed, collector joined)")
        assertFalse(fileExists(snapshotPath), "no snapshot file may appear after close")
    }

    @Test
    fun close_isIdempotent() = runTest {
        val publisher = IosArhamWidgetSnapshotPublisher(
            snapshotProvider = { snapshot },
            clock = clock,
            containerPathProvider = { null }, // لا حاوية — النشر يُتخطى صمتاً؛ الإغلاق وحده تحت الاختبار
            publishingContext = Dispatchers.Unconfined,
        )
        runBlocking {
            withTimeout(5_000) {
                publisher.close()
                publisher.close() // الاستدعاء المتكرر آمن — القناة والنطاق مُتَمّان
            }
        }
    }
}
