package app.sila.di

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ربط iOS لوحدة إشعارات أرحام (T8-08 · D2): **وحدة فارغة في المرحلة الأولى** —
 * جدولة الإشعارات المحلية (UNUserNotificationCenter) مسؤولية T8-12، وهي محجوبة هذا
 * الجلسة (بلا تحقق Swift/SKIE على Windows — سجل W-1). العقد المشترك
 * `ArhamAlarmScheduler` مشروط بهذه الوحدة عند تفعيل T8-12؛ إطلاق Android عبر
 * Koin الإطاري لا يتأثر أبداً.
 */
internal actual val platformArhamNotificationModule: Module = module { }
