package app.sila.di

import app.sila.arham.notification.ArhamAlarmScheduler
import app.sila.arham.notification.IosArhamNotificationCenterDelegate
import app.sila.arham.notification.IosArhamNotificationScheduler
import app.sila.arham.notification.IosArhamNotificationStrings
import app.sila.arham.notification.IosArhamReminderScheduling
import app.sila.arham.notification.IosBundleArhamNotificationStrings
import app.sila.arham.notification.IosNotificationCapabilityProbe
import app.sila.core.datastore.UserPreferencesDataSource
import app.sila.feature.arham.domain.PlanArhamDailyNotificationUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ربط iOS لوحدة إشعارات أرحام (T8-12 · D2 المصادَق · خطة سطر 552): جدولة
 * `UNUserNotificationCenter` بزنّاد يومي متكرر، وبوابة استقصاء القدرة (D7)، ومندوب
 * مركز الإشعارات (أفعال «اتصل الآن»/«ذكّرني غداً») — كل القرارات في النطاق المشترك
 * والمنصة تنفّذ حصراً.
 *
 * التنفيذ حثيف التحميل — لا تلمس `UNUserNotificationCenter` إلا عند الحل الفعلي،
 * والاختبارات تربط بدائلها بلا مركز إشعارات حقيقي (نمط `HostAbsentPlatformSource`
 * عبر الحقن المباشر في المُنشئات).
 */
internal actual val platformArhamNotificationModule: Module = module {
    single { IosBundleArhamNotificationStrings() as IosArhamNotificationStrings }
    single {
        IosArhamNotificationScheduler(
            planner = get<PlanArhamDailyNotificationUseCase>(),
            preferences = get<UserPreferencesDataSource>(),
            strings = get<IosArhamNotificationStrings>(),
        )
    }
    // اسم الواجهة المشترك (مرآة ربط أندرويد) — يُحلّ إلى نفس المفرد السابق:
    single<ArhamAlarmScheduler> { get<IosArhamNotificationScheduler>() }
    // عقد إزاحة iOS («ذكّرني غداً»/المهيئ) — نفس المفرد (التنفيذ ينفّذ العقدَين):
    single<IosArhamReminderScheduling> { get<IosArhamNotificationScheduler>() }
    single { IosNotificationCapabilityProbe() }
    single {
        IosArhamNotificationCenterDelegate(
            preferences = get<UserPreferencesDataSource>(),
            planner = get<PlanArhamDailyNotificationUseCase>(),
            scheduler = get<IosArhamNotificationScheduler>(),
        )
    }
}
