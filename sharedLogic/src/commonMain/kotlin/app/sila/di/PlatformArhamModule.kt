package app.sila.di

import org.koin.core.module.Module

/**
 * وحدة إشعارات أرحام الخاصة بالمنصة (T8-08 · D2): `expect`/`actual` لكل منصة،
 * والربط الفعلي في androidMain/iosMain لـ `:sharedLogic`.
 * توفر تنفيذ `ArhamAlarmScheduler` حصراً (عقد الجدولة المشترك — بلا شبكة ولا Firebase
 * — خصوصية ADR-0009 §6)، وتُضمَّن في `createSilaSharedModule` قبل المستخدمين.
 * iOS فارغة في المرحلة الأولى (T8-12 محجوبة — بلا تحقق Swift/SKIE على Windows).
 */
internal expect val platformArhamNotificationModule: Module
