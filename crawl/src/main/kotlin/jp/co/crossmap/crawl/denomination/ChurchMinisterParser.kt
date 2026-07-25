package jp.co.crossmap.crawl.denomination

import jp.co.crossmap.ChurchMinister
import jp.co.crossmap.LocalizedName

internal object ChurchMinisterParser {
    private data class Role(
        val id: String,
        val japanese: String,
        val english: String,
        val korean: String,
        val portuguese: String,
        val indonesian: String,
        val aliases: List<String>,
    )

    private val roles = listOf(
        Role("senior_pastor", "主任牧師", "Senior Pastor", "담임목사", "Pastor titular", "Pendeta senior", listOf("主任牧師", "主管牧師")),
        Role("lead_pastor", "担任牧師", "Lead Pastor", "담임목사", "Pastor responsável", "Pendeta penanggung jawab", listOf("担任牧師", "主任担任教師", "担任教師")),
        Role("associate_pastor", "副牧師", "Associate Pastor", "부목사", "Pastor associado", "Pendeta pendamping", listOf("副牧師", "補教師")),
        Role("cooperating_pastor", "協力牧師", "Cooperating Pastor", "협력 목사", "Pastor cooperante", "Pendeta mitra", listOf("協力牧師")),
        Role("acting_pastor", "代理牧師", "Acting Pastor", "대리 목사", "Pastor interino", "Pendeta pelaksana", listOf("代理牧師")),
        Role("dispatched_pastor", "派遣牧師", "Dispatched Pastor", "파견 목사", "Pastor enviado", "Pendeta utusan", listOf("派遣牧師")),
        Role("youth_pastor", "ユースパスター", "Youth Pastor", "청소년 목사", "Pastor de jovens", "Pendeta pemuda", listOf("ユースパスター")),
        Role("advisor_pastor", "顧問牧師", "Advisory Pastor", "고문 목사", "Pastor conselheiro", "Pendeta penasihat", listOf("アドバイザー牧師", "顧問牧師")),
        Role("church_planting_pastor", "開拓担当牧師", "Church Planting Pastor", "개척 담당 목사", "Pastor de implantação de igrejas", "Pendeta perintis gereja", listOf("開拓担当牧師")),
        Role("supervising_pastor", "主管者", "Supervising Pastor", "주관 목사", "Pastor supervisor", "Pendeta pengawas", listOf("主管者")),
        Role("pastor_emeritus", "名誉牧師", "Pastor Emeritus", "원로목사", "Pastor emérito", "Pendeta emeritus", listOf("名誉牧師", "隠退牧師")),
        Role("concurrent_pastor", "兼任牧師", "Concurrent Pastor", "겸임 목사", "Pastor em acumulação", "Pendeta rangkap", listOf("兼任牧師", "牧師（兼任）", "牧師(兼任)", "兼牧")),
        Role("pastor", "牧師", "Pastor", "목사", "Pastor", "Pendeta", listOf("小隊士官（牧師）", "小隊士官(牧師)", "牧師", "正教師")),
        Role("evangelist", "伝道師", "Evangelist", "전도사", "Evangelista", "Penginjil", listOf("伝道師", "伝道者", "伝教者")),
        Role("missionary", "宣教師", "Missionary", "선교사", "Missionário", "Misionaris", listOf("宣教師")),
        Role("lifework_missionary", "ライフワーク宣教師", "Life-work Missionary", "라이프워크 선교사", "Missionário de carreira", "Misionaris pelayanan hidup", listOf("ライフワーク宣教師")),
        Role("missionary_doctor", "宣教医", "Missionary Doctor", "선교 의사", "Médico missionário", "Dokter misionaris", listOf("宣教医")),
        Role("priest", "司祭", "Priest", "사제", "Sacerdote", "Imam", listOf("司祭", "司牧")),
        Role("deacon", "輔祭", "Deacon", "부제", "Diácono", "Diakon", listOf("輔祭")),
        Role("retired_cooperating_minister", "引退協力教師", "Retired Cooperating Minister", "은퇴 협력 교역자", "Ministro cooperador aposentado", "Pelayan gereja mitra emeritus", listOf("引退協力教師")),
        Role("elder", "長老", "Elder", "장로", "Presbítero", "Penatua", listOf("長老")),
        Role("minister", "教職", "Minister", "교역자", "Ministro", "Pelayan gereja", listOf("教職", "教師")),
    )
    private val aliases = roles.flatMap { role -> role.aliases.map { it to role } }.sortedByDescending { it.first.length }
    private val rolePattern = aliases.joinToString("|") { Regex.escape(it.first) }
    private val inline = Regex(
        "($rolePattern)(?:\\s*[：:・]\\s*|\\s+)(.+?)(?=\\s*(?:(?:$rolePattern)(?:\\s*[：:・]|\\s+)|TEL|Tel|電話|FAX|Fax|MAIL|E-mail|Email|HOMEPAGE|https?://|〒|[0-9０-９]{3}[-ー－‐][0-9０-９]{4}|$))",
    )
    private val suffix = Regex(
        "([\\p{L}][\\p{L} ・･]{1,29})\\s+($rolePattern)(?=\\s*(?:TEL|Tel|電話|FAX|Fax|MAIL|E-mail|Email|HOMEPAGE|https?://|〒|[0-9０-９]{3}[-ー－‐][0-9０-９]{4}|$))",
    )
    private val trailingNoise = Regex("(?:TEL|Tel|tel|電話|FAX|Fax|fax|〒|メール|E-mail|Email).*$")

    fun parse(text: String): List<ChurchMinister> {
        val normalized = normalize(text)
        return (
            inline.findAll(normalized).flatMap { match ->
                fromRoleAndNames(match.groupValues[1], match.groupValues[2]).asSequence()
            } + suffix.findAll(normalized).flatMap { match ->
                fromRoleAndNames(match.groupValues[2], match.groupValues[1]).asSequence()
            }
        ).distinctBy { it.roleId to it.name }.toList()
    }

    fun fromRoleAndNames(roleText: String, namesText: String): List<ChurchMinister> {
        val normalizedRole = normalize(roleText)
        val role = aliases.firstOrNull { (alias) -> normalizedRole.contains(alias) }?.second ?: return emptyList()
        return normalize(namesText)
            .replace(trailingNoise, "")
            .replace(Regex("^(?:は|が)?[：:・\\s]*|[。;；\\s]+$"), "")
            .replace(Regex("[（(]\\s*$"), "")
            // Some legacy directories concatenate the next name immediately after a role annotation.
            // A plain reading annotation can be followed by the remainder of the same name, e.g. 韓（ハン）ビョンソブ.
            .replace(Regex("([（(][^）)]*(?:$rolePattern)[^）)]*[）)])(?=\\p{L})"), "$1、")
            .split(Regex("\\s*(?:、|,|，|／|/)\\s*"))
            .map { it.replace(Regex("^(?:氏名|名前)\\s*[：:]?\\s*"), "").trim() }
            .mapNotNull { rawName ->
                val annotatedRole = aliases.firstOrNull { (alias) ->
                    Regex("[（(][^）)]*${Regex.escape(alias)}[^）)]*[）)]").containsMatchIn(rawName)
                }?.second
                val name = rawName.replace(Regex("[（(][^）)]*(?:$rolePattern)[^）)]*[）)]"), "")
                    .replace(Regex("^(?:大尉|中尉|少尉|少佐|中佐|大佐|曹長)\\s*"), "")
                    .trim()
                name.takeIf(::isPersonName)?.let { it to (annotatedRole ?: role) }
            }
            .map { (name, effectiveRole) ->
                ChurchMinister(
                    name = name,
                    roleId = effectiveRole.id,
                    roleName = effectiveRole.japanese,
                    localizedRoleNames = listOf(
                        LocalizedName("ja", effectiveRole.japanese),
                        LocalizedName("en", effectiveRole.english),
                        LocalizedName("ko", effectiveRole.korean),
                        LocalizedName("pt", effectiveRole.portuguese),
                        LocalizedName("id", effectiveRole.indonesian),
                    ),
                )
            }
    }

    private fun normalize(value: String): String = value
        .replace('\u00a0', ' ')
        .replace('　', ' ')
        .replace(Regex("[ \\t]+"), " ")
        .trim()

    private fun isPersonName(value: String): Boolean = value.length in 2..30 &&
        value.none { it.isDigit() } &&
        !Regex("[{}\\\"：:。！？!?|]|facebook|instagram|youtube|Presbyterian|Church in|教会|伝道所|地区担当|電話|住所|不在|募集中|未定|礼拝|聖書|奉仕|皆様|毎週|月に|先生を|ています|ました|します|ください|おります|です|ます|して|され|こと|ため|によって|では", RegexOption.IGNORE_CASE).containsMatchIn(value)
}
