package app.sila.feature.contacts.domain

import app.sila.core.common.ArabicTokenizer
import app.sila.core.model.Relation

/**
 * القاموس المضمّن للفلترة الذكية (T7 · Issue #28 — spec 04 §ب؛ PRD §8):
 * مصطلحات عربية بلهجات متعددة (مصر/الخليج/الشام/الفصحى) **مكتوبة كلها بصيغة
 * ما-بعد-التطبيع** ([app.sila.core.common.ArabicTextNormalizer.normalize]: همزات→ا،
 * ة→ه، ى→ي) — العرف الموثق؛ أي مصطلح جديد يُكتب بهذه الصيغة حصراً.
 *
 * المطابقة **رمز-كامل حصراً** ([termMatches]) — لا substring إطلاقاً: «محمد دكتوري»
 * لا يطابق «دكتور»، و«العم حسن» يطابق «عمي» بعد اقتطاع «ال». المصطلحات مركّبة من
 * كلمتين («ابن عمي») تُطابق نافذة منزلقة بطول المصطلح.
 *
 * ملاحظة نطاق متعمّدة: القاموس مضمّن في الحزمة بلا تحميل عن بعد — تحديث القاموس
 * = إصدار تطبيق (قرار #28 الملزِم: بلا Remote Config).
 */
object SmartFilterDictionary {

    /**
     * مصطلحات العائلة → مرشّح القرابة (agy F5: علاقة معلومة تُقدَّم لجسر أرحام بعد
     * تطبيق التصنيف). كل المصطلحات الثنائية أول شخص («عمي» لا «العم الفلاني»).
     */
    val FAMILY_TERMS: Map<String, Relation> = mapOf(
        // الأم
        "امي" to Relation.MOTHER,
        "ماما" to Relation.MOTHER,
        "يمه" to Relation.MOTHER,
        "والدتي" to Relation.MOTHER,
        // الأب
        "ابي" to Relation.FATHER,
        "بابا" to Relation.FATHER,
        "ابوي" to Relation.FATHER,
        "يبه" to Relation.FATHER,
        "والدي" to Relation.FATHER,
        // الأجداد
        "جدي" to Relation.GRANDFATHER,
        "جد" to Relation.GRANDFATHER,
        "جدتي" to Relation.GRANDMOTHER,
        "تيتا" to Relation.GRANDMOTHER,
        "سيتي" to Relation.GRANDMOTHER,
        // الإخوة
        "اخي" to Relation.BROTHER,
        "اخويا" to Relation.BROTHER,
        "خوي" to Relation.BROTHER,
        "اختي" to Relation.SISTER,
        // الأبناء
        "ابني" to Relation.SON,
        "ولدي" to Relation.SON,
        "بنتي" to Relation.DAUGHTER,
        "ابنتي" to Relation.DAUGHTER,
        // الزوجية
        "زوجي" to Relation.SPOUSE,
        "زوجتي" to Relation.SPOUSE,
        // الأعمام — الصيغة العامة تُغطي «العم فلان» عبر اقتطاع أداة التعريف في tokensMatch
        // (ملاحظة: «عم فلان» لقب اجتماعي محتمل — مرشّح القرابة يعرض على المستخدم ولا يُطبق آلياً)
        "عمي" to Relation.UNCLE_PATERNAL,
        "عمو" to Relation.UNCLE_PATERNAL,
        "عم" to Relation.UNCLE_PATERNAL,
        "عمتي" to Relation.AUNT_PATERNAL,
        "عمه" to Relation.AUNT_PATERNAL,
        // الأخوال
        "خالي" to Relation.UNCLE_MATERNAL,
        "خالو" to Relation.UNCLE_MATERNAL,
        "خال" to Relation.UNCLE_MATERNAL,
        "خالتي" to Relation.AUNT_MATERNAL,
        "خاله" to Relation.AUNT_MATERNAL,
        // أبناء العمومة (نوافذ ثنائية — ابن/بنت + عم/عمه/خال/خاله)
        "ابن عمي" to Relation.COUSIN_PATERNAL,
        "بنت عمي" to Relation.COUSIN_PATERNAL,
        "ابن عمتي" to Relation.COUSIN_PATERNAL,
        "بنت عمتي" to Relation.COUSIN_PATERNAL,
        "ابن خالي" to Relation.COUSIN_MATERNAL,
        "بنت خالي" to Relation.COUSIN_MATERNAL,
        "ابن خالتي" to Relation.COUSIN_MATERNAL,
        "بنت خالتي" to Relation.COUSIN_MATERNAL,
        // مصاهرة
        "حماتي" to Relation.IN_LAW,
        "حمايا" to Relation.IN_LAW,
        "حموي" to Relation.IN_LAW,
        // الأصدقاء المقربون
        "صاحبي" to Relation.CLOSE_FRIEND,
        "صاحبتي" to Relation.CLOSE_FRIEND,
        "رفيقي" to Relation.CLOSE_FRIEND,
        "رفيقتي" to Relation.CLOSE_FRIEND,
    )

    /** مصطلحات الخدمات → معرّف مهنة مستقر (spec 04:51 — agy F6). */
    val SERVICE_TERMS: Map<String, String> = mapOf(
        "صيدليه" to "pharmacy",
        "pharmacy" to "pharmacy",
        "دكتور" to "doctor",
        "دكتوره" to "doctor",
        "عياده" to "clinic",
        "مستشفي" to "hospital",
        "مختبر" to "lab",
        "تحاليل" to "lab",
        "مطعم" to "restaurant",
        "restaurant" to "restaurant",
        "كافيه" to "cafe",
        "سوبر ماركت" to "supermarket",
        "ماركت" to "supermarket",
        "بقاله" to "grocery",
        "سباك" to "plumber",
        "كهربايي" to "electrician",
        "نجار" to "carpenter",
        "ورشه" to "workshop",
        "ميكانيكي" to "mechanic",
        "سمكري" to "mechanic",
        "تكييف" to "ac",
        "صيانه" to "maintenance",
        "شركه" to "company",
        "موسسه" to "company",
        "مقاول" to "contractor",
        "توصيل" to "delivery",
        "دراي كلين" to "dry_cleaning",
        "مغسله" to "laundry",
        "تنظيف" to "cleaning",
        "محامي" to "lawyer",
        "محاميه" to "lawyer",
        "محاسب" to "accountant",
    )

    /** كلمات الطوارئ (عربية فصحى + إنجليزية شائعة). */
    val EMERGENCY_TERMS: Set<String> = setOf(
        "اسعاف", "شرطه", "مطافي", "نجده", "طواري", "emergency", "police", "ambulance",
    )

    /**
     * رموز الطوارئ القصيرة المعروفة (مصر/الخليج/عالمي GSM) — تُطابق على **الرقم الوطني
     * القصير حصراً** (≤5 خانات) بشرط التساوي أو اللاحقة — «+201000000001» لا يطابق
     * «001» إطلاقاً (طول الرقم الكامل يفشل حارس القصر).
     */
    val EMERGENCY_CODES: Set<String> = setOf(
        "112", "122", "123", "180", "911", "999", "997", "998",
    )

    /** ألقاب مهنية في الاسم → WORK بثقة متوسطة (ليست HIGH — لقب اجتماعي محتمل). */
    val WORK_TITLE_TOKENS: Set<String> = setOf("مهندس", "م.")

    /**
     * مطابقة مصطلح ضد رموز اسم مطبَّعة: مفرد = رمز-كامل (مع سماح أداة ال/و في أي من
     * الطرفين)، ومركّب = نافذة منزلقة بطول أجزاء المصطلح. لا substring إطلاقاً.
     */
    fun termMatches(tokens: List<String>, term: String): Boolean {
        val parts = term.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        if (tokens.size < parts.size) return false
        if (parts.size == 1) {
            return tokens.any { token -> ArabicTokenizer.tokensMatch(token, term) }
        }
        return tokens.windowed(parts.size).any { window ->
            parts.indices.all { k -> ArabicTokenizer.tokensMatch(window[k], parts[k]) }
        }
    }

    /** أول مصطلح مطابق بترتيب الخريطة الحتمي — الدليل المعروض ومفتاح التغذية الراجعة. */
    fun <T> firstMatch(terms: Map<String, T>, tokens: List<String>): String? =
        terms.keys.firstOrNull { term -> termMatches(tokens, term) }
}
