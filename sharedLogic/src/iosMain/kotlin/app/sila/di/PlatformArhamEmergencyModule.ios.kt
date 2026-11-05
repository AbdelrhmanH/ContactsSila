package app.sila.di

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * الفعل iOS لمنفذ حلقة الطوارئ (T8-10): فارغ — لا رنين طوارئ iOS في هذه المرحلة
 * (T8-13 BLOCKED بلا تحقق Swift/SKIE على Windows — قيد W-1). أي مستقبِل iOS يُربط
 * هنا وقت T8-13 حصراً.
 */
internal actual val platformArhamEmergencyModule: Module = module { }
