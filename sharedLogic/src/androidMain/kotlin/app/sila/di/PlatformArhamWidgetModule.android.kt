package app.sila.di

import android.content.Context
import app.sila.arham.widget.GlanceWidgetRefresher
import app.sila.core.database.dao.ArhamDataChangeListener
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * الفعل Android لانعاش الودجت (T8-10 · D3): يربط [GlanceWidgetRefresher] بوجود
 * `android.content.Context` — التسجيل على خطاف [ArhamDataChangeListener]
 * يُجرى في SilaApplication (جذر التركيب — نمط الجسر الموجود).
 *
 * بلا Context (بيئات اختبار/مركّبات بديلة بلا `androidContext`) — مستمعٌ صامت لا
 * يفعل شيئاً (مراجعة T8-10 · P3-5): فشلٌ هادئ idempotent بدل انهيار الجذر (§O.5).
 */
internal actual val platformArhamWidgetModule: Module = module {
    factory<ArhamDataChangeListener> {
        val context = getOrNull<Context>()
        if (context == null) {
            ArhamDataChangeListener { } // صامت — بلا Context لا انعاش ودجت ولا انهيار
        } else {
            GlanceWidgetRefresher(context.applicationContext)
        }
    }
}
