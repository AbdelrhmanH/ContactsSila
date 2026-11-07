package app.sila.arham.widget

import app.sila.core.common.AppClock
import app.sila.core.database.dao.ArhamDataChangeListener
import app.sila.feature.arham.domain.ArhamWidgetSnapshot
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

/**
 * ناشر لقطة الودجت على iOS (T8-13 · D6 · AC-15) — المرآة الدقيقة لـ
 * [app.sila.arham.widget.GlanceWidgetRefresher] على أندرويد لكن بميكانيكية App Group:
 *
 * - يُسجَّل كمستمعٍ [ArhamDataChangeListener] على `LocalSilaDatabase` (من البوتستراب، مرآة
 *   `SilaApplication.registerWidgetRefreshBridge`)، فكل تغيير بيانات أرحام يُشغّل النشر.
 * - عند كل حدث: تُشتق اللقطة عبر use cases المجال (قراءة Room حصراً) ← تُسلسل JSON ← تُكتب
 *   **ذرّياً** في حاوية App Group. الإضافة (عملية منفصلة) تقرأ الملف حصراً — **لا تلمس Room
 *   إطلاقاً** (D6: قاعدة البيانات لا تُنقل ولا تُشارك، والودجت لا يكتب).
 * - لا كادر دوري هنا (خلافاً لأندرويد D3): إيقاع التحديث على iOS قرار WidgetKit نفسه +
 *   إعادة تحميل صريحة عند تغيّر البيانات (§M: "scheduled refresh + data-change refresh").
 * - بعد نجاح الكتابة يُستدعى مُشغِّل إعادة التحميل الذي يحقنه Swift (`WidgetCenter.
 *   reloadTimelines` غير متاح في K/N — لا klib لـ WidgetKit، تم التحقق) — إذا لم يُضبط
 *   (اختبارات/بدل ذلك) فيُكتب الملف ويمتلك الودجت الملف الحديث عند دورته القادمة.
 *
 * الدمج: قناة CONFLATED تذيب انفجارات الأحداث المتلاحقة في نشر واحد (مرآة دلالة
 * `ExistingWorkPolicy.REPLACE` الأندرويدية دون WorkManager).
 */
class IosArhamWidgetSnapshotPublisher(
    private val snapshotProvider: suspend () -> ArhamWidgetSnapshot,
    private val clock: AppClock,
    private val containerPathProvider: () -> String? = ::resolveAppGroupContainerPath,
    publishingContext: CoroutineContext = Dispatchers.Default,
) : ArhamDataChangeListener {

    private val scope = CoroutineScope(SupervisorJob() + publishingContext)

    private val requests = Channel<Unit>(capacity = Channel.CONFLATED)

    /** مُشغِّل إعادة التحميل الذي يضبطه Swift في عملية التطبيق نفسها (استدعاء داخل العملية). */
    private val reloadTrigger = AtomicReference<(() -> Unit)?>(null)

    private val collectorJob: Job = scope.launch {
        requests.receiveAsFlow().collect { publishOnce() }
    }

    /** يضبط (أو يصفّر) دالة إعادة تحميل الودجت التي يستدعيها Kotlin بعد كل كتابة ناجحة. */
    fun setReloadTrigger(trigger: (() -> Unit)?) {
        reloadTrigger.value = trigger
    }

    override fun onArhamDataChanged() {
        runCatching { requests.trySend(Unit) }
            .onFailure { logTypeOnly("Widget snapshot request enqueue failed", it) }
    }

    /**
     * إغلاق الناشر القابل للانتظار (تكامل دورة حياة الموارد — يُستدعى من التصريف
     * قبل إغلاق الحاوية حصراً): يغلق قناة الأحداث ثم **ينضم للجامع** حتى يُكمل أيّ
     * نشر معلّق في القناة وينتهي بنفسه، والإلغاء بعدها حاجز خلفي لمسارٍ مرضي فقط —
     * فلا قراءة Room ولا كتابة لقطة على حاوية مغلقة بعد عودته. لا يلمس ملف اللقطة المكتوب سابقاً (الإضافة تقرأه على دورتها).
     * Idempotent — إغلاق قناة مغلقة وإنضمام مُتَمٍّ بلا أثر.
     */
    suspend fun close() {
        // 1) إغلاق القناة يجعل الجامع يُكمل أيّ نشر معلّق في القناة ثم ينتهي بنفسه؛
        // الانضمام أولاً هو الضمان — الإلغاء حاجزٌ خلفي فقط لمسار مرضي لا يكمل.
        requests.close()
        try {
            collectorJob.join()
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    /**
     * دورة النشر الكاملة — داخلي وقابل للاستدعاء المباشر من الاختبارات (بلا اعتماد على توقيت
     * القناة). صامت عند غياب حاوية App Group (بناء غير مُوقّع على المُحاكي مثلاً) أو فشل الكتابة.
     */
    internal suspend fun publishOnce() {
        runCatching {
            val containerPath = containerPathProvider()
            if (containerPath.isNullOrBlank()) {
                logTypeOnly("Widget App Group container unavailable; snapshot skipped")
                return
            }
            val snapshot = snapshotProvider()
            val json = json.encodeToString(snapshot.toDto(generatedAtMillis = clock.nowEpochMillis()))
            val written = writeAtomically(
                path = "$containerPath/$SNAPSHOT_FILE_NAME",
                content = json.encodeToByteArray(),
            )
            if (!written) {
                logTypeOnly("Widget snapshot write failed; reload trigger skipped")
                return
            }
            reloadTrigger.value?.invoke()
        }.onFailure {
            // إعادة رمي الإلغاء إلزامية: كتمها يجعل انضمام الجامع في [close] يُسجَّل
            // زوراً كإخفاق نشر ويُفقد ضمان «لا عمل حي بعد العودة».
            if (it is CancellationException) throw it
            logTypeOnly("Widget snapshot publish failed", it)
        }
    }

    internal companion object {

        /** اسم ملف اللقطة داخل جذر حاوية App Group — الثابت المقابل في Swift `SnapshotReader`. */
        const val SNAPSHOT_FILE_NAME = "arham-widget-snapshot.json"

        /**
         * كتابة ذرّية (مرآة دلالة `writeToFile(atomically: true)`): تُكتب البايتات في ملف
         * `.tmp` مجاور ثم `rename` — الاستبدال الذرّي على نفس نظام الملفات، فلا تقرأ إضافة
         * الودجت ملفاً ممزقاً أبداً.
         *
         * لماذا POSIX وليس `NSString.writeToFile`؟ في K/N 2.4 (الاستخدام التجريبي كُشِف عبر
         * بوابات الترجمة) أعضاء `NSString/NSData` مُخفاة عن الاستيراد — بوابات `fopen/fwrite/
         * rename` هي المسار المضمون، والترميز UTF-8 مضمون عبر `ByteArray` الخالص.
         */
        @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
        internal fun writeAtomically(path: String, content: ByteArray): Boolean {
            val tmpPath = "$path.tmp"
            val file = platform.posix.fopen(tmpPath, "wb") ?: return false
            val fullyWritten = content.usePinned { pinned ->
                platform.posix.fwrite(
                    pinned.addressOf(0),
                    1u,
                    content.size.toULong(),
                    file,
                ) == content.size.toULong()
            }
            platform.posix.fclose(file)
            if (!fullyWritten || platform.posix.rename(tmpPath, path) != 0) {
                platform.posix.remove(tmpPath)
                return false
            }
            return true
        }

        /** مفتاح Info.plist الذي يحمل مُعرِّف App Group (يُوسَّع من `APP_GROUP_ID` في xcconfig). */
        const val INFO_PLIST_KEY_APP_GROUP_ID = "AppGroupId"

        private val json = Json { encodeDefaults = true }

        /**
         * يقرأ مُعرِّف المجموعة من Info.plist (مصدر الحقيقة الوحيد: `APP_GROUP_ID` في
         * Config.xcconfig يُوسَّع وقت البناء في ملفَّي Entitlements وInfo.plist) ثم يحلّ حاوية
         * المجموعة. `null` = المجموعة غير مُوثقة/غير متاحة ← يتخطى النشر بهدوء (fail-safe).
         */
        fun resolveAppGroupContainerPath(): String? {
            val groupId = NSBundle.mainBundle
                .objectForInfoDictionaryKey(INFO_PLIST_KEY_APP_GROUP_ID) as? String
                ?: return null
            val containerUrl = NSFileManager.defaultManager
                .containerURLForSecurityApplicationGroupIdentifier(groupIdentifier = groupId)
                ?: return null
            return containerUrl.path
        }

        fun logTypeOnly(message: String, error: Throwable? = null) {
            // بلا بيانات شخصية في السجلات إطلاقاً (§O.5) — الأنواع فقط.
            if (error == null) println(message) else println("$message: ${error::class.simpleName}")
        }
    }
}
