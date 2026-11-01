package app.sila.core.database.legacy

/**
 * DDL جدول `contacts_fts` (FTS4 مستقل) — المصدر الوحيد لعبارة الإنشاء في الإنتاج:
 * الاعتماد الآمن ([LegacyDatabaseAdopter]) وـ Callback فتح Room في الواجهة الخارجية
 * (`LocalSilaDatabase`) يستخدمان العبارة نفسها حرفياً (ADR-0011 §FTS4).
 *
 * الجدول **ليس كياناً مُداراً من Room** — القاعدة المعمارية (Decision 3 · ADR-0011):
 * KSP على أهداف Native (iOS) لا يستطيع معالجة كيانات `@Fts4` المستقلة؛
 * `FtsTableEntityProcessor` يفشل في حلّ القيمة الافتراضية لـ `contentEntity` من klib
 * فيرمي «Cannot find external content entity class» (خلل Room 3.0.3 — أندرويد/JVM
 * غير متأثرَين). لذا يُنشأ الجدول عبر `RoomDatabase.Callback` بنفس الدلالات الفيزيائية
 * الحرفية للقاعدة اليدوية السابقة: مستقل بلا `content=`، بلا توكنِزِر صريح
 * (SIMPLE الافتراضي)، و`docid == contacts.id` بمزامنة يدوية عبر SQL خام بلا مشغّلات.
 */
internal object LegacyFtsDdl {
    const val CREATE_CONTACTS_FTS: String =
        "CREATE VIRTUAL TABLE IF NOT EXISTS contacts_fts USING fts4(" +
            "displayName, nickname, company, department, notes, normalizedName)"

    /**
     * خيارات أي تعريف FTS حي (كل رمز يحوي «=» بعد الأعمدة: `tokenize=…` / `content=…`).
     * المصدر المشترك لبوابة الشكل في [LegacySchemaFingerprint] و[LegacyDatabaseAdopter]
     * حتى لا تنحرف المقارنة عن تعريف الإنتاج أبداً.
     */
    fun optionsOf(createSql: String): Set<String> {
        val using = createSql.substringAfter("USING", "")
        if (using.isEmpty()) return emptySet()
        val args = using.substringAfter("(", "").substringBeforeLast(")")
        return args.split(",")
            .map { it.trim() }
            .filter { it.contains("=") }
            .toSet()
    }

    /** خيارات الثابت الموحد — **فارغة** عمداً: مستقل بلا `content=` وبلا `tokenize=` صريح (SIMPLE الافتراضي). */
    val TARGET_OPTIONS: Set<String> = optionsOf(CREATE_CONTACTS_FTS)
}
