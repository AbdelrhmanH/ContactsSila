package app.sila.di

import android.content.Context
import app.sila.arham.emergency.AndroidEmergencyRingingController
import app.sila.feature.arham.domain.EmergencyRingingController
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * الفعل Android لمنفذ حلقة الطوارئ (T8-10): يربط [AndroidEmergencyRingingController]
 * فوق الخدمة الأمامية بوجود `android.content.Context` حصراً (SilaApplication يسجّل
 * `androidContext`). **فشلٌ هادئ على host JVM:** غياب الـ Context يربط بديل صامت —
 * النطاق لا يعتمد على وجود المنصة إطلاقاً (نمط T8-08/09).
 */
internal actual val platformArhamEmergencyModule: Module = module {
    single<EmergencyRingingController> {
        val context = getOrNull<Context>()
        if (context != null) {
            AndroidEmergencyRingingController(context = context.applicationContext)
        } else {
            HostAbsentEmergencyRingingController
        }
    }
}

/** بديل مضيف الاختبارات/JVM الصامت — نمط `HostAbsentPlatformSource` (ADR-0010 §5). */
internal object HostAbsentEmergencyRingingController : EmergencyRingingController {
    override fun start(sessionId: Long, contactId: Long) = Unit
    override fun stop() = Unit
    override fun isRunning(sessionId: Long): Boolean = false
}
