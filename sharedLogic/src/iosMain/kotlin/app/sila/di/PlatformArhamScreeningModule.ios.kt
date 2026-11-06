package app.sila.di

import app.sila.arham.observer.ArhamCallStateObserver
import app.sila.arham.observer.IosCallStateObserver
import app.sila.core.model.CallSource
import app.sila.feature.arham.presentation.ArhamScreeningConsentSource
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ربط iOS للفرز والمراقبة (T8-12 · خطة سطر 550): يوفر [IosCallStateObserver] فوق
 * `CXCallObserver` (ضمن حياة التطبيق فقط — حالة فقط بلا هوية، AC-6/AC-17).
 *
 * **لا فرز مكالمات على iOS** (خيار أندرويد حصراً — `ROLE_CALL_SCREENING` لا نظير له في
 * iOS عملاً وتشريعاً): مصدر الموافقة يُربط بمصدرٍ صامت يعيد `null` — تُترجَم إلى إخفاء
 * بطاقة الفرز في الواجهة (نمط المسار المضيف الغائب أندرويد نفسه)، ويربطه T8-14 عبر
 * `ArhamViewModel` المحلول من هذه الوحدة.
 */
internal actual val platformArhamScreeningModule: Module = module {
    single<ArhamCallStateObserver> {
        IosCallStateObserver(
            correlate = get(),
            clock = get(),
        )
    }
    factory<ArhamScreeningConsentSource> { ArhamScreeningConsentSource { null } }
}

/** مصدر دليل مراقب iOS — مراقب `CXCallObserver` (العقد المصادَق في `CallSource`). */
internal actual val platformObserverCallSource: CallSource = CallSource.IOS_CALL_OBSERVER
