package app.sila.di

import org.koin.core.module.Module

/**
 * وحدة فرز أرحام ومراقب الحالة الخاصة بالمنصة (T8-09): `expect`/`actual` لكل منصة،
 * والربط الفعلي في androidMain/iosMain لـ `:sharedLogic`. توفر مراقب حالة الهاتف
 * (حالة فقط بلا هوية — AC-17) ومتحكم دور الفرز حصراً؛ لا شبكة ولا Firebase
 * (خصوصية ADR-0009 §6). تُضمَّن في `createSilaSharedModule` قبل المستخدمين.
 * iOS فارغة في المرحلة الأولى (T8-12 محجوبة — بلا تحقق Swift/SKIE على Windows).
 */
internal expect val platformArhamScreeningModule: Module
