package jp.co.crossmap.crawl

import java.net.URI

data class ChurchNameComponent(
    val japanese: String,
    val role: ChurchNamePartRole,
    val english: String? = null,
    val evidence: String,
)

data class ChurchNameAnalysis(
    val denominationAlias: String?,
    val components: List<ChurchNameComponent>,
    val congregationJapanese: String,
    val congregationEnglish: String,
) {
    val unresolvedComponents: List<ChurchNameComponent>
        get() = components.filter { it.english.isNullOrBlank() }

    fun compose(translations: Map<String, String> = emptyMap()): String? {
        val translated = components.map { component ->
            component.english ?: translations[component.translationKey()] ?: return null
        }
        return (translated + congregationEnglish).filter(String::isNotBlank).joinToString(" ")
    }
}

fun ChurchNameComponent.translationKey(): String = "${role.name}:$japanese"

data class ChurchNameTranslationStats(
    var totalChurches: Int = 0,
    var denominationAliasesDetected: Int = 0,
    var congregationSuffixesDetected: Int = 0,
    var geonameParts: Int = 0,
    var traditionParts: Int = 0,
    var conceptualParts: Int = 0,
    var otherParts: Int = 0,
    var deterministicPartsTranslated: Int = 0,
    var dictionaryPartsTranslated: Int = 0,
    var unresolvedParts: Int = 0,
    var namesRequiringComponentCompletion: Int = 0,
    var programmaticNames: Int = 0,
    var componentLlmPartsRequested: Int = 0,
    var componentLlmUniqueExecutions: Int = 0,
    var componentLlmCacheHits: Int = 0,
    var componentLlmFallbackExecutions: Int = 0,
    var invalidComponentCacheEntries: Int = 0,
    var fullNameLlmFallbacks: Int = 0,
    var llmComposedNames: Int = 0,
    var errors: Int = 0,
    var timeouts: Int = 0,
) {
    val unresolvedPartCounts: MutableMap<String, Int> = linkedMapOf()
    val programmaticRuleCounts: MutableMap<String, Int> = linkedMapOf()

    fun record(analysis: ChurchNameAnalysis) {
        if (analysis.denominationAlias != null) denominationAliasesDetected++
        congregationSuffixesDetected++
        analysis.components.forEach { component ->
            when (component.role) {
                ChurchNamePartRole.GEONAME -> geonameParts++
                ChurchNamePartRole.TRADITION -> traditionParts++
                ChurchNamePartRole.CONCEPTUAL_NAME, ChurchNamePartRole.PROPER_NAME -> conceptualParts++
                ChurchNamePartRole.OTHER -> otherParts++
                ChurchNamePartRole.CONGREGATION -> Unit
            }
            if (component.english == null) {
                unresolvedParts++
                unresolvedPartCounts[component.translationKey()] =
                    unresolvedPartCounts.getOrDefault(component.translationKey(), 0) + 1
            } else {
                deterministicPartsTranslated++
                if (component.evidence.contains("dictionary")) dictionaryPartsTranslated++
            }
        }
        deterministicPartsTranslated++ // congregation suffix
        if (analysis.unresolvedComponents.isNotEmpty()) namesRequiringComponentCompletion++
    }

    fun recordProgrammatic(result: ProgrammaticEnglishName) {
        programmaticNames++
        val rule = result.evidence.substringBefore(" (")
        programmaticRuleCounts[rule] = programmaticRuleCounts.getOrDefault(rule, 0) + 1
    }
}

/**
 * Splits a church name before any LLM call. Known denominations, geonames, traditions, concepts, and
 * congregation words are translated deterministically; only the remaining typed spans need inference.
 */
class ChurchNameComponentAnalyzer(
    denominations: List<Denomination>,
    private val geonames: Map<String, String> = ChurchNameEnglishLexicon.geonames,
    private val traditions: Map<String, String> = ChurchNameEnglishLexicon.traditions,
    private val concepts: Map<String, String> = emptyMap(),
    private val romanize: (String) -> String? = JapaneseNameRomanizer::romanize,
    private val detectionOnlyGeonames: Set<String> = emptySet(),
    private val dictionaryEntries: Set<String> = emptySet(),
) {
    private data class KnownPart(val role: ChurchNamePartRole, val english: String?)
    private class TrieNode {
        val children = mutableMapOf<Char, TrieNode>()
        var part: KnownPart? = null
    }

    private val aliases = denominations.flatMap { denomination ->
        (listOf(denomination.name) + denomination.aliases).filter(String::isNotBlank)
            .map { alias -> alias to denomination.id }
    }.distinct().sortedByDescending { it.first.length }
    private val knownParts = TrieNode().also { root ->
        fun add(value: String, part: KnownPart) {
            // One-character entries fragment ordinary names (for example のぞみ -> の + ぞみ).
            // Exact one-character names are still handled by exactKnownPart below.
            if (value.length < 2) return
            var node = root
            value.forEach { character -> node = node.children.getOrPut(character, ::TrieNode) }
            node.part = part
        }
        detectionOnlyGeonames.forEach { add(it, KnownPart(ChurchNamePartRole.GEONAME, null)) }
        geonames.forEach { (japanese, english) -> add(japanese, KnownPart(ChurchNamePartRole.GEONAME, english)) }
        traditions.forEach { (japanese, english) -> add(japanese, KnownPart(ChurchNamePartRole.TRADITION, english)) }
        concepts.forEach { (japanese, english) -> add(japanese, KnownPart(ChurchNamePartRole.CONCEPTUAL_NAME, english)) }
        STRUCTURAL_COMPONENTS.forEach { (japanese, english) ->
            add(japanese, KnownPart(ChurchNamePartRole.OTHER, english))
        }
    }

    fun analyze(church: ChurchEnglishNameInput): ChurchNameAnalysis? {
        val compact = church.name.replace("&amp;", "&").replace(Regex("""\s+"""), "").trim().removePrefix("宗教法人")
            .replace(Regex("""(?:教会){2,}"""), "教会")
        val normalized = stripAncillaryFacility(stripTrailingQualifier(compact))
        val branch = Regex("""^(.+?)教会(.+?)(?:チャペル|伝道所)$""").matchEntire(normalized)
        val branchNormalized = branch?.let { match ->
            val parentName = match.groupValues[1]
            val locationName = match.groupValues[2]
            "${locationName}${parentName}教会"
        } ?: normalized
        val eligibleAliases = church.denominationId
            ?.takeUnless { it == NOT_DETERMINED }
            ?.let { knownId -> aliases.filter { (_, denominationId) -> denominationId == knownId } }
            ?: aliases
        val leadingAlias = eligibleAliases.firstOrNull { branchNormalized.startsWith(it.first) }?.first
        val aliasRemainder = leadingAlias?.let(branchNormalized::removePrefix)?.trim(' ', '・', '-', 'ー').orEmpty()
        val analysisName = if (leadingAlias != null && aliasRemainder.isNotBlank()) {
            aliasRemainder.takeIf { remainder -> CONGREGATIONS.any { remainder.endsWith(it.first) } }
                ?: "${aliasRemainder}教会"
        } else {
            branchNormalized
        }
        val suffix = CONGREGATIONS.firstOrNull { analysisName.endsWith(it.first) }
        val prefix = if (suffix == null) PREFIX_CONGREGATIONS.firstOrNull { analysisName.startsWith(it.first) } else null
        val infix = if (suffix == null && prefix == null) {
            INFIX_CONGREGATIONS.firstOrNull { analysisName.contains(it.first) }
        } else {
            null
        }
        val (suffixJapanese, suffixEnglish) = suffix ?: prefix ?: infix ?: return null
        var stem = when {
            prefix != null -> analysisName.removePrefix(suffixJapanese).trim(' ', '・', '-', 'ー')
            infix != null -> analysisName.replaceFirst(suffixJapanese, "").trim(' ', '・', '-', 'ー')
            else -> analysisName.removeSuffix(suffixJapanese).trim()
        }
        val alias = leadingAlias ?: eligibleAliases.firstOrNull { stem.startsWith(it.first) }?.first
        if (alias != null && leadingAlias == null) stem = stem.removePrefix(alias).trim()
        // A second internal 教会 is normally part of a denomination/parent-church prefix; the final
        // congregation suffix already contributes the single English "Church".
        if (suffixEnglish == "Church") stem = stem.replace("教会", "")
        if (stem.isBlank()) return null
        exactKnownPart(stem)?.let { part ->
            return ChurchNameAnalysis(
                alias,
                listOf(ChurchNameComponent(stem, part.role, part.english, "exact name lexicon")),
                suffixJapanese,
                suffixEnglish,
            )
        }

        val components = mutableListOf<ChurchNameComponent>()
        val unknown = StringBuilder()
        fun flushUnknown() {
            val value = unknown.toString().trim { !it.isLetterOrDigit() }
            unknown.clear()
            if (value.isBlank() || value.all { it == '々' || it == 'ー' }) return
            if (value in IGNORED_STRUCTURAL_PARTS) return
            exactKnownPart(value)?.let { part ->
                components += ChurchNameComponent(
                    japanese = value,
                    role = part.role,
                    english = part.english,
                    evidence = if (value in dictionaryEntries) {
                        "reviewed JP-EN dictionary"
                    } else if (part.english == null) {
                        "detected geoname without English translation"
                    } else {
                        "exact name lexicon"
                    },
                )
                return
            }
            val role = when {
                value.length >= 2 && church.address.contains(value) -> ChurchNamePartRole.GEONAME
                value.all(::isKanaOrSeparator) -> ChurchNamePartRole.CONCEPTUAL_NAME
                else -> ChurchNamePartRole.OTHER
            }
            // Detection-only place names have no authoritative English column. Kuromoji supplies a
            // deterministic phonetic reading; a plausibly matching URL path can still defer it to LLM.
            val reading = romanize(value)
            val deterministic = reading?.takeUnless { pathHintLikelyCorrectsReading(church.websiteUrl, it) }
            components += ChurchNameComponent(
                japanese = value,
                role = role,
                english = deterministic,
                evidence = if (deterministic == null) "unresolved typed name span" else "Kuromoji kana romanization",
            )
        }

        var index = 0
        while (index < stem.length) {
            val match = findLongestKnownPart(stem, index)
            if (match == null) {
                unknown.append(stem[index++])
            } else {
                flushUnknown()
                components += ChurchNameComponent(
                    match.first,
                    match.second.role,
                    match.second.english,
                    when {
                        match.first in dictionaryEntries -> "reviewed JP-EN dictionary"
                        match.second.english == null -> "detected geoname without English translation"
                        else -> "name lexicon"
                    },
                )
                index += match.first.length
            }
        }
        flushUnknown()
        return ChurchNameAnalysis(alias, components, suffixJapanese, suffixEnglish)
    }

    private fun findLongestKnownPart(text: String, startIndex: Int): Pair<String, KnownPart>? {
        var node = knownParts
        var index = startIndex
        var longest: Pair<String, KnownPart>? = null
        while (index < text.length) {
            node = node.children[text[index]] ?: break
            index++
            node.part?.let { longest = text.substring(startIndex, index) to it }
        }
        return longest
    }

    private fun exactKnownPart(value: String): KnownPart? =
        geonames[value]?.let { KnownPart(ChurchNamePartRole.GEONAME, it) }
            ?: value.takeIf(detectionOnlyGeonames::contains)?.let { KnownPart(ChurchNamePartRole.GEONAME, null) }
            ?: traditions[value]?.let { KnownPart(ChurchNamePartRole.TRADITION, it) }
            ?: concepts[value]?.let { KnownPart(ChurchNamePartRole.CONCEPTUAL_NAME, it) }

    private fun isKanaOrSeparator(character: Char): Boolean =
        character.isWhitespace() || character == '・' || character == 'ー' || character == '-' ||
            character in '\u3040'..'\u30ff'

    private fun pathHintLikelyCorrectsReading(url: String, reading: String): Boolean {
        val normalizedReading = reading.lowercase().replace(Regex("""[^a-z0-9]+"""), "")
        if (normalizedReading.length < 4) return false
        val tokens = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
            .lowercase().split(Regex("""[^a-z0-9]+"""))
            .filter { it.length >= 4 && it !in setOf("index", "html", "about", "contact", "church") }
        return tokens.any { token ->
            levenshteinDistance(normalizedReading, token) <= maxOf(2, normalizedReading.length * 2 / 5)
        }
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftCharacter ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightCharacter ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftCharacter == rightCharacter) 0 else 1,
                )
            }
            previous = current
        }
        return previous.last()
    }

    private fun stripTrailingQualifier(value: String): String {
        val prefix = Regex("""^(.+?)[（(].*[）)]$""").matchEntire(value)?.groupValues?.get(1) ?: return value
        return prefix.takeIf { candidate -> CONGREGATIONS.any { candidate.endsWith(it.first) } } ?: value
    }

    private fun stripAncillaryFacility(value: String): String =
        Regex("""^(.+?(?:教会|聖堂|チャペル))(?:&|＆|・)?(?:附属|付属)?(?:保育園|幼稚園|こども園|墓地|納骨堂)$""")
            .matchEntire(value)?.groupValues?.get(1) ?: value

    private companion object {
        val STRUCTURAL_COMPONENTS = mapOf("教会" to "Church")
        val CONGREGATIONS = listOf(
            "キリスト集会" to "Christian Assembly",
            "聖書塾" to "Bible School",
            "友の会" to "Fellowship",
            "大聖堂" to "Cathedral", "チャペル" to "Chapel", "礼拝堂" to "Chapel", "会堂" to "Chapel",
            "伝道所" to "Mission", "ミッション" to "Mission", "チャーチ" to "Church",
            "フェローシップ" to "Fellowship", "福音集会所" to "Gospel Assembly",
            "福音集会" to "Gospel Assembly", "集会所" to "Assembly", "集会" to "Assembly",
            "福音館" to "Gospel Hall", "祈りの家" to "House of Prayer", "センター" to "Center",
            "礼拝所" to "Chapel", "ハウス" to "House", "聖公会" to "Anglican Church",
            "エクレシア" to "Ecclesia", "キリスト会" to "Christian Fellowship", "教会堂" to "Church",
            "教会" to "Church", "聖堂" to "Chapel", "小隊" to "Mission",
        )
        val PREFIX_CONGREGATIONS = listOf(
            "チャペル" to "Chapel",
            "ミッション" to "Mission",
        )
        val INFIX_CONGREGATIONS = listOf(
            "教会" to "Church",
            "チャーチ" to "Church",
            "チャペル" to "Chapel",
        )
        val IGNORED_STRUCTURAL_PARTS = setOf("教", "教団", "会")
    }
}

internal object ChurchNameEnglishLexicon {
    val geonames = linkedMapOf(
        "東京" to "Tokyo", "川崎" to "Kawasaki", "赤羽" to "Akabane", "大阪" to "Osaka",
        "姫路" to "Himeji", "横浜" to "Yokohama", "京都" to "Kyoto", "神戸" to "Kobe",
        "名古屋" to "Nagoya", "札幌" to "Sapporo", "福岡" to "Fukuoka", "仙台" to "Sendai",
        "千葉" to "Chiba", "広島" to "Hiroshima", "岡山" to "Okayama", "奈良" to "Nara",
        "静岡" to "Shizuoka", "経堂" to "Kyodo", "雲内" to "Kumouchi",
        "北海道" to "Hokkaido", "青森" to "Aomori", "岩手" to "Iwate", "宮城" to "Miyagi",
        "秋田" to "Akita", "山形" to "Yamagata", "福島" to "Fukushima", "茨城" to "Ibaraki",
        "栃木" to "Tochigi", "群馬" to "Gunma", "埼玉" to "Saitama", "神奈川" to "Kanagawa",
        "新潟" to "Niigata", "富山" to "Toyama", "石川" to "Ishikawa", "福井" to "Fukui",
        "山梨" to "Yamanashi", "長野" to "Nagano", "岐阜" to "Gifu", "愛知" to "Aichi",
        "三重" to "Mie", "滋賀" to "Shiga", "兵庫" to "Hyogo", "和歌山" to "Wakayama",
        "鳥取" to "Tottori", "島根" to "Shimane", "山口" to "Yamaguchi", "徳島" to "Tokushima",
        "香川" to "Kagawa", "愛媛" to "Ehime", "高知" to "Kochi", "佐賀" to "Saga",
        "長崎" to "Nagasaki", "熊本" to "Kumamoto", "大分" to "Oita", "宮崎" to "Miyazaki",
        "鹿児島" to "Kagoshima", "沖縄" to "Okinawa", "さいたま" to "Saitama",
        "相模原" to "Sagamihara", "浜松" to "Hamamatsu", "堺" to "Sakai", "北九州" to "Kitakyushu",
        "八王子" to "Hachioji", "小倉" to "Kokura", "倉敷" to "Kurashiki", "金沢" to "Kanazawa",
        "岡崎" to "Okazaki", "宝塚" to "Takarazuka", "松山" to "Matsuyama", "豊橋" to "Toyohashi",
        "松本" to "Matsumoto", "藤沢" to "Fujisawa", "高槻" to "Takatsuki", "旭川" to "Asahikawa",
        "小山" to "Oyama", "八幡" to "Yahata", "長岡" to "Nagaoka", "松江" to "Matsue",
        "日本" to "Japan", "東" to "Higashi", "西" to "Nishi", "南" to "Minami", "北" to "Kita",
        "中央" to "Chuo",
        "川口" to "Kawaguchi", "甲府" to "Kofu", "宇都宮" to "Utsunomiya", "函館" to "Hakodate",
        "府中" to "Fuchu",
        "久留米" to "Kurume", "清水" to "Shimizu", "柏" to "Kashiwa", "那覇" to "Naha",
        "下関" to "Shimonoseki", "高松" to "Takamatsu", "高崎" to "Takasaki", "大津" to "Otsu",
        "船橋" to "Funabashi", "四日市" to "Yokkaichi", "所沢" to "Tokorozawa", "福山" to "Fukuyama",
        "水戸" to "Mito", "浦和" to "Urawa", "松戸" to "Matsudo", "川越" to "Kawagoe",
        "大和" to "Yamato", "米子" to "Yonago", "明石" to "Akashi", "尼崎" to "Amagasaki",
        "立川" to "Tachikawa", "前橋" to "Maebashi", "越谷" to "Koshigaya", "市川" to "Ichikawa",
        "郡山" to "Koriyama", "調布" to "Chofu",
    )
    val traditions = linkedMapOf(
        "バプテスト" to "Baptist", "ホーリネス" to "Holiness", "ルーテル" to "Lutheran",
        "長老" to "Presbyterian", "福音" to "Gospel", "聖公会" to "Anglican", "カトリック" to "Catholic",
        "ペンテコステ" to "Pentecostal", "メソジスト" to "Methodist", "アドベンチスト" to "Adventist",
        "キリスト兄弟団" to "Christian Brotherhood", "イエス之御霊" to "Spirit of Jesus",
        "ハリストス正" to "Orthodox", "キリスト" to "Christ", "基督" to "Christ", "聖書" to "Bible",
        "クリスチャン" to "Christian",
        "宣教" to "Mission", "同盟" to "Alliance", "国際" to "International", "部" to "Branch",
    )
}
