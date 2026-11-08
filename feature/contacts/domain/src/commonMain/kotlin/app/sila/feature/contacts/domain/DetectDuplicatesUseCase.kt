package app.sila.feature.contacts.domain

import app.sila.core.common.ArabicTextNormalizer
import app.sila.core.common.ArabicTokenizer
import app.sila.core.common.JaroWinklerSimilarity
import app.sila.core.common.PhoneNormalizer
import app.sila.core.model.Contact

/**
 * كشف جهات الاتصال المكررة محلياً حصراً (Issue #28 — spec 04 §أ) — دالة نقية بلا I/O ولا DI.
 *
 * الخوارزمية (union-find على أدلة مستقلة):
 * 1. تطبيع كل جهة مرة واحدة: E.164 لكل رقم من `phone.raw` (لا يُؤتمن على `e164` المخزَّن —
 *    مسار المزامنة يسقط فيه قيماً خاماً)، بريد lowercase، واسم مقارنة عبر
 *    [ArabicTextNormalizer.normalizeForComparison] (إزالة اللقب عند المقارنة حصراً).
 * 2. أدلة الحواف: نفس E.164 → HIGH (و**الرمز القصير/الخط الساخن بلا «+» → MEDIUM حصراً —
 *    agy F7: «دمج كل المؤكدة» لا يطوي فروع بنوك/خدمات تتشارك خطاً قصيراً**)، نفس البريد → HIGH،
 *    نفس اسم المقارنة → MEDIUM، وJaro-Winkler ≥ 0.92 → LOW اقتراح فقط.
 * 3. مرشّحو JW يُولَّدون داخل دلو (الرمز الأول، طوله/4) مع تقليم رخيص قبل الحساب:
 *    الأسماء متعددة الكلمات تشترط **تطابق الرمز الأخير** وJW ≥ 0.92 — يحجب الباترونيمي المزيف
 *    («عبدالرحمن محمد»/«عبدالرحمن أحمد» ≈ 0.943 بوالدين مختلفين)؛ الأحادية تشترط فرق طول ≤ 2.
 * 4. الأزواج المرفوض (`dismissedPairKeys` — مفاتيح ثنائيات `duplicate_dismissals`، نفس اشتقاق
 *    معرف البطاقة الثنائية) **لا تُنشئ حافة أصلاً**: ثنائية مرفوضة لا تعود، وثلاثية تتجمع عبر
 *    أدلة أخرى تظل مشروعة (الدليل الجديد ليس الزوج المرفوض نفسه).
 * 5. ترتيب حتمي: الثقة تنازلياً ثم الحجم ثم المعرف — ثابت عبر الفحوص المتكررة.
 *
 * الأسماء الفارغة لا تُجمِّع أبداً، ولا تطابق جزئية للأرقام (مطابقة قيمة-كاملة حصراً).
 */
class DetectDuplicatesUseCase {

    operator fun invoke(
        contacts: List<Contact>,
        dismissedPairKeys: Set<String> = emptySet(),
    ): List<DuplicateGroup> {
        if (contacts.size < 2) return emptyList()

        val normalized = contacts.mapIndexed { index, contact -> NormalizedContact.of(index, contact) }

        val edges = mutableListOf<Edge>()
        edges += phoneEdges(normalized)
        edges += emailEdges(normalized)
        edges += nameEdges(normalized)
        edges += similarNameEdges(normalized, edges)

        val allowed = edges.filter { it.pairKey !in dismissedPairKeys }
        val parent = IntArray(normalized.size) { it }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var current = x
            while (parent[current] != root) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }

        for (edge in allowed) {
            val a = find(edge.from)
            val b = find(edge.to)
            if (a != b) parent[a] = b
        }

        val membersByRoot = normalized.groupBy { find(it.index) }
        return membersByRoot.values.mapNotNull { members ->
            if (members.size < 2) return@mapNotNull null
            buildGroup(members, allowed)
        }.sortedWith(
            compareBy({ it.confidence }, { -it.contacts.size }, { it.id }),
        )
    }

    private fun phoneEdges(normalized: List<NormalizedContact>): List<Edge> {
        val byPhone = mutableMapOf<String, MutableList<NormalizedContact>>()
        for (n in normalized) {
            for (e164 in n.e164s) byPhone.getOrPut(e164) { mutableListOf() }.add(n)
        }
        return buildList {
            for ((e164, list) in byPhone) {
                if (list.size < 2) continue
                val confidence = if (e164.startsWith("+")) DuplicateConfidence.HIGH else DuplicateConfidence.MEDIUM
                pairwise(list) { a, b ->
                    add(Edge(a, b, confidence, DuplicateReasonKind.SAME_PHONE, e164))
                }
            }
        }
    }

    private fun emailEdges(normalized: List<NormalizedContact>): List<Edge> {
        val byEmail = mutableMapOf<String, MutableList<NormalizedContact>>()
        for (n in normalized) {
            for (email in n.emails) byEmail.getOrPut(email) { mutableListOf() }.add(n)
        }
        return buildList {
            for ((email, list) in byEmail) {
                if (list.size < 2) continue
                pairwise(list) { a, b ->
                    add(Edge(a, b, DuplicateConfidence.HIGH, DuplicateReasonKind.SAME_EMAIL, email))
                }
            }
        }
    }

    private fun nameEdges(normalized: List<NormalizedContact>): List<Edge> {
        val byName = mutableMapOf<String, MutableList<NormalizedContact>>()
        for (n in normalized) {
            if (n.comparisonName.isNotEmpty()) byName.getOrPut(n.comparisonName) { mutableListOf() }.add(n)
        }
        return buildList {
            for ((name, list) in byName) {
                if (list.size < 2) continue
                pairwise(list) { a, b ->
                    add(Edge(a, b, DuplicateConfidence.MEDIUM, DuplicateReasonKind.SAME_NAME, name))
                }
            }
        }
    }

    private fun similarNameEdges(
        normalized: List<NormalizedContact>,
        existing: List<Edge>,
    ): List<Edge> {
        val alreadyLinked = existing.mapTo(mutableSetOf()) { it.pairKey }
        // مفتاح الدلو متسامح عمداً (أول حرفين + طوله/4): خطأ مطبعي في أول كلمة («محمود»/«محمودد»)
        // لا يجوز أن يفصل المرشحين دلوين. كلفة JW تُقيَّد بالتقليم المسبق لا بحجم الدلو.
        val buckets = normalized.filter { it.comparisonName.isNotEmpty() }
            .groupBy {
                it.comparisonName.take(2) + "|" + (it.comparisonName.length / 4)
            }

        return buildList {
            for (bucket in buckets.values) {
                if (bucket.size < 2 || bucket.size > MAX_BUCKET_FOR_SIMILARITY) continue
                for (i in bucket.indices) {
                    for (j in i + 1 until bucket.size) {
                        val a = bucket[i]
                        val b = bucket[j]
                        val pairKey = a.pairKeyWith(b)
                        if (pairKey in alreadyLinked) continue
                        val similarity = candidateSimilarity(a, b) ?: continue
                        if (similarity >= SIMILARITY_THRESHOLD) {
                            add(
                                Edge(
                                    a, b, DuplicateConfidence.LOW,
                                    DuplicateReasonKind.SIMILAR_NAME, a.comparisonName,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * تقليم المرشح قبل حساب JW (agy F7): متعدد الكلمات يشترط تطابق الرمز الأخير (يحجب
     * الباترونيمي)، والأحادية تشترط فرق طول ≤ 2. يُعيد null عند رفض الترشيح.
     */
    private fun candidateSimilarity(a: NormalizedContact, b: NormalizedContact): Double? {
        val lengthGap = a.comparisonName.length - b.comparisonName.length
        return if (a.tokens.size > 1 || b.tokens.size > 1) {
            if (a.tokens.lastOrNull() != b.tokens.lastOrNull()) return null
            JaroWinklerSimilarity.similarity(a.comparisonName, b.comparisonName)
        } else {
            if (lengthGap > 2 || lengthGap < -2) return null
            JaroWinklerSimilarity.similarity(a.comparisonName, b.comparisonName)
        }
    }

    private fun buildGroup(members: List<NormalizedContact>, edges: List<Edge>): DuplicateGroup {
        val memberIndexes = members.mapTo(mutableSetOf()) { it.index }
        val groupEdges = edges.filter { it.from in memberIndexes && it.to in memberIndexes }
        val best = groupEdges.minWith(
            compareBy({ it.confidence }, { it.kind.ordinal }, { it.detail }),
        )
        val sortedMembers = members.sortedWith(
            compareBy({ it.comparisonName }, { it.contact.systemContactId }),
        )
        return DuplicateGroup(
            id = stableSuggestionId(
                SuggestionCard.Kind.DUPLICATES,
                sortedMembers.map { it.contact.systemContactId },
            ),
            contacts = sortedMembers.map { it.contact },
            confidence = best.confidence,
            reasonKind = best.kind,
            reasonDetail = best.detail,
        )
    }

    private inline fun pairwise(list: List<NormalizedContact>, block: (NormalizedContact, NormalizedContact) -> Unit) {
        for (i in list.indices) {
            for (j in i + 1 until list.size) block(list[i], list[j])
        }
    }

    private class NormalizedContact(
        val index: Int,
        val contact: Contact,
        val e164s: List<String>,
        val emails: List<String>,
        val comparisonName: String,
        val tokens: List<String>,
    ) {
        fun pairKeyWith(other: NormalizedContact): String =
            stableSuggestionId(
                SuggestionCard.Kind.DUPLICATES,
                listOf(contact.systemContactId, other.contact.systemContactId),
            )

        companion object {
            fun of(index: Int, contact: Contact): NormalizedContact {
                val e164s = contact.phones
                    .map { PhoneNormalizer.normalizeToE164(it.raw) }
                    .filter { it.isNotEmpty() }
                    .distinct()
                val emails = contact.emails
                    .map { it.address.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                val comparisonName = ArabicTextNormalizer.normalizeForComparison(contact.name.displayName)
                return NormalizedContact(
                    index = index,
                    contact = contact,
                    e164s = e164s,
                    emails = emails,
                    comparisonName = comparisonName,
                    tokens = ArabicTokenizer.split(comparisonName),
                )
            }
        }
    }

    private class Edge(
        val from: Int,
        val to: Int,
        val confidence: DuplicateConfidence,
        val kind: DuplicateReasonKind,
        val detail: String,
        /** مفتاح الثنائية المستقر — نفس اشتقاق معرف البطاقة الثنائية، فيطابق مفاتيح `duplicate_dismissals`. */
        val pairKey: String,
    ) {
        constructor(a: NormalizedContact, b: NormalizedContact, confidence: DuplicateConfidence, kind: DuplicateReasonKind, detail: String) :
            this(a.index, b.index, confidence, kind, detail, a.pairKeyWith(b))
    }

    private companion object {
        const val SIMILARITY_THRESHOLD = 0.92

        /** سقف دلو JW — الحماية من البيانات المرضية؛ فوقه تُضحّى باقتراحات LOW لا بالصحة. */
        const val MAX_BUCKET_FOR_SIMILARITY = 1024
    }
}
