package app.sila.di

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * الفعل iOS لانعاش الودجت (T8-10): فارغ — ودجت iOS (WidgetKit + لقطة D6) مسؤولية
 * T8-13 (BLOCKED بلا تحقق Swift/SKIE على Windows — قيد W-1).
 */
internal actual val platformArhamWidgetModule: Module = module { }
