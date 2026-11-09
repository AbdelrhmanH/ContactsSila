package app.sila.di

import android.content.Context
import app.sila.arham.notification.AndroidAlarmScheduler
import app.sila.arham.notification.AndroidArhamReminderSchedulingPort
import app.sila.arham.notification.ArhamAlarmScheduler
import app.sila.arham.notification.HostAbsentAlarmScheduler
import app.sila.feature.settings.domain.ArhamReminderSchedulingPort
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ربط Android لجدولة إشعارات أرحام (T8-08 · D2): يوفر [AndroidAlarmScheduler] فوق
 * AlarmManager حين يُسجَّل `android.content.Context` في الحاوية (SilaApplication
 * يزوّده عبر `androidContext`).
 *
 * **مسار مضيف الاختبارات فقط (host JVM):** حين لا يوجد Context (اختبارات JVM للمنطق
 * المشترك — لا تطبيق أندرويد) تُربَط [HostAbsentAlarmScheduler] الصامتة بدل الطرح —
 * نمط `HostAbsentPlatformSource` (ADR-0010 §5) نفسه. على الجهاز الحقيقي يوجد Context
 * دائماً فلا يُنفَّذ هذا المسار أبداً.
 */
internal actual val platformArhamNotificationModule: Module = module {
    single<ArhamAlarmScheduler> {
        val context = getOrNull<Context>()
        if (context != null) {
            AndroidAlarmScheduler(context.applicationContext)
        } else {
            HostAbsentAlarmScheduler
        }
    }
    // منفذ إعادة الجدولة الفورية من الإعدادات (S2-13 · تذكرة #71 · ADR-0016):
    // بلا Context أصلًا — الجدولة في العقد المشترك، فمسار مضيف الاختبارات يُحلّ
    // عبر [AndroidArhamReminderSchedulingPort] مباشرة (لا مسار صامت مزدوج).
    single<ArhamReminderSchedulingPort> {
        AndroidArhamReminderSchedulingPort(
            preferences = get(),
            planner = get(),
            scheduler = get(),
        )
    }
}
