package app.sila.di

import android.content.Context
import android.os.Build
import app.sila.arham.observer.AndroidCallStateObserver
import app.sila.arham.observer.ArhamCallStateObserver
import app.sila.arham.observer.HostAbsentCallStateObserver
import app.sila.arham.screening.ScreeningRoleController
import app.sila.feature.arham.presentation.ArhamScreeningConsentSource
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
 *
 * T8-11 (AC-4): مصدر الموافقة [ArhamScreeningConsentSource] يلفّ المتحكم ببوابة
 * SDK — الدور غير موجود قبل API 29 و`isRoleHeld()` يعيد `false` هناك (ليست
 * «لا ينطبق»)، فالترجمة إلى `null` (إخفاء البطاقة) قرارُ هذا اللفاف حصراً. في
 * مسار بلا Context يُربط مصدرٌ صامت يعيد `null` — بلا انهيار (نمط المضيف الغائب).
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
    factory<ArhamScreeningConsentSource> {
        val context = getOrNull<Context>()
        if (context == null) {
            ArhamScreeningConsentSource { null }
        } else {
            val controller = ScreeningRoleController(context.applicationContext)
            ArhamScreeningConsentSource {
                if (Build.VERSION.SDK_INT >= 29) controller.isRoleHeld() else null
            }
        }
    }
}
