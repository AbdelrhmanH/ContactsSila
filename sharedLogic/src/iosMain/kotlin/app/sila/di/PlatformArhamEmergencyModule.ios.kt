package app.sila.di

import app.sila.arham.emergency.IosBundleArhamEmergencyStrings
import app.sila.arham.emergency.IosEmergencyCallPlacer
import app.sila.arham.emergency.IosEmergencyRingingController
import app.sila.feature.arham.domain.EmergencyRingingController
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * وحدة iOS لمنصة الطوارئ (T8-13): تربط [IosEmergencyRingingController] بجلسات Room الحقيقية
 * مرآةً للوحدة الأندرويدية (FGS هناك). المضيف غائب؟ لا حاجة له هنا — اختبارات iosTest تعمل
 * داخل الإطار الحقيقي وتبني الرسم البياني كاملاً (مرآة `KoinWiringSmokeTest`).
 *
 * `mainDispatcherProvider` داخل المُتحكم مُقيَّم لحظة الاتصال فقط (وليس عند الإنشاء)، فيظل حلُّ
 * الاعتماديات آمناً في الاختبارات بلا Dispatchers.Main حقيقي.
 */
internal actual val platformArhamEmergencyModule: Module = module {
    single {
        IosEmergencyRingingController(
            repository = get(),
            strings = IosBundleArhamEmergencyStrings(),
        )
    }
    // ربط الواجهة المشتركة باسمٍ على نفس المفرد — كل use cases الطوارئ (Start/Stop/Record/
    // Reconcile) تحقن `EmergencyRingingController` الواجهة، ومرآة الوحدة الأندرويدية
    // (`single<EmergencyRingingController>`) — غيابه كان سيسقط الحلّ كلّه وقت التشغيل.
    single<EmergencyRingingController> { get<IosEmergencyRingingController>() }
    // اسم مستعار للنقشة الخاصة بـ iOS (مرآة `IosArhamReminderScheduling` في T8-12):
    // واجهة إعادة الاتصال اليدوية (تأكيد المستخدم لكل محاولة — §N.3) تُفصل عن عقد الحلقة المشترك.
    single<IosEmergencyCallPlacer> { get<IosEmergencyRingingController>() }
}
