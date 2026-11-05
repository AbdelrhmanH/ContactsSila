package app.sila.di

import android.content.Context
import app.sila.arham.observer.AndroidCallStateObserver
import app.sila.arham.observer.ArhamCallStateObserver
import app.sila.arham.observer.HostAbsentCallStateObserver
import app.sila.arham.screening.ScreeningRoleController
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ربط Android للفرز والمراقبة (T8-09): يوفر [AndroidCallStateObserver] فوق
 * TelephonyManager و[ScreeningRoleController] فوق RoleManager حين يُسجَّل
 * `android.content.Context` في الحاوية (SilaApplication يزوّده عبر `androidContext`).
 *
 * **مسار مضيف الاختبارات فقط (host JVM):** حين لا يوجد Context تُربَط المراقبة
 * الصامتة [HostAbsentCallStateObserver] بدل الطرح — نمط `HostAbsentPlatformSource`
 * (ADR-0010 §5). متحكم الدور بلا Context لا معنى له فلا يُسجَّل في هذا المسار.
 */
internal actual val platformArhamScreeningModule: Module = module {
    single<ArhamCallStateObserver> {
        val context = getOrNull<Context>()
        if (context != null) {
            AndroidCallStateObserver(
                context = context.applicationContext,
                correlate = get(),
                clock = get(),
            )
        } else {
            HostAbsentCallStateObserver
        }
    }
    factory {
        val context = getOrNull<Context>()
            ?: error("ScreeningRoleController يتطلب Context — حاوية بلا androidContext غير صالحة للفرز")
        ScreeningRoleController(context.applicationContext)
    }
}
