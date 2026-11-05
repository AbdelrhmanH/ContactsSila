package app.sila.di

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * ربط iOS للفرز والمراقبة (T8-09): فارغة في المرحلة الأولى — مراقب `CXCallObserver`
 * ودور الفرز (إن وُجد) مسؤولية T8-12، المحجوبة هذا الجلسة (بلا تحقق Swift/SKIE على
 * Windows — قيود W-1). أي مستهلك iOS لهذه الواجهات لن يجد تعريفاً حتى T8-12.
 */
internal actual val platformArhamScreeningModule: Module = module { }
