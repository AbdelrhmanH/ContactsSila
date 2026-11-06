package app.sila.di

import app.sila.arham.widget.IosArhamWidgetSnapshotPublisher
import app.sila.core.common.AppClock
import app.sila.core.database.dao.ArhamDataChangeListener
import app.sila.feature.arham.domain.GetArhamWidgetSnapshotUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * وحدة iOS لمنصة الودجت (T8-13 · D6): تربط [IosArhamWidgetSnapshotPublisher] كمصدر لقطةٍ
 * من use cases المجال + ساعة النظام، وتُعرّفه كواجهة [ArhamDataChangeListener] الشائعة حتى
 * تسجّله البوتستراب على `LocalSilaDatabase` (مرآة `GlanceWidgetRefresher` الأندرويدية).
 *
 * الالتقاط داخل `snapshotProvider` لمستقبل Koin متعمد: تُقرأ الاعتماديات لحظة النشر (لا عند
 * الإنشاء) — فتبقى دورة النشر داخل نطاق الوحدة نفسه.
 */
internal actual val platformArhamWidgetModule: Module = module {
    single {
        IosArhamWidgetSnapshotPublisher(
            snapshotProvider = { get<GetArhamWidgetSnapshotUseCase>()() },
            clock = get<AppClock>(),
        )
    }
    single<ArhamDataChangeListener> { get<IosArhamWidgetSnapshotPublisher>() }
}
