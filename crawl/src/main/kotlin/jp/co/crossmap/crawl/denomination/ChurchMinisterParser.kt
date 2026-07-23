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
        Role("supervising_pastor", "主管者", "Supervising Pastor", "주관 목사", "Pastor supervisor", "Pendeta pengawas", listOf("主管者")),
        Role("pastor_emeritus", "名誉牧師", "Pastor Emeritus", "원로목사", "Pastor emérito", "Pendeta emeritus", listOf("名誉牧師", "隠退牧師")),
        Role("concurrent_pastor", "兼任牧師", "Concurrent Pastor", "겸임 목사", "Pastor em acumulação", "Pendeta rangkap", listOf("兼任牧師", "牧師（兼任）", "牧師(兼任)", "兼牧")),
        Role("pastor", "牧師", "Pastor", "목사", "Pastor", "Pendeta", listOf("牧師", "正教師")),
        Role("evangelist", "伝道師", "Evangelist", "전도사", "Evangelista", "Penginjil", listOf("伝道師", "伝道者")),
        Role("missionary", "宣教師", "Missionary", "선교사", "Missionário", "Misionaris", listOf("宣教師")),
        Role("priest", "司祭", "Priest", "사제", "Sacerdote", "Imam", listOf("司祭", "司牧")),
        Role("minister", "教職", "Minister", "교역자", "Ministro", "Pelayan gereja", listOf("教職", "教師")),
    )
    private val aliases = roles.flatMap { role -> role.aliases.map { it to role } }.sortedByDescending { it.first.length }
    private val rolePattern = aliases.joinToString("|") { Regex.escape(it.first) }
    private val inline = Regex(
        "($rolePattern)(?:\\s*[：:]\\s*|\\s+)(.+?)(?=\\s*(?:(?:$rolePattern)(?:\\s*[：:]|\\s+)|TEL|Tel|電話|FAX|Fax|MAIL|E-mail|Email|HOMEPAGE|https?://|〒|$))",
    )
    private val trailingNoise = Regex("(?:TEL|Tel|tel|電話|FAX|Fax|fax|〒|メール|E-mail|Email).*$")

    fun parse(text: String): List<ChurchMinister> = inline.findAll(normalize(text)).flatMap { match ->
        fromRoleAndNames(match.groupValues[1], match.groupValues[2]).asSequence()
    }.distinctBy { it.roleId to it.name }.toList()

    fun fromRoleAndNames(roleText: String, namesText: String): List<ChurchMinister> {
        val normalizedRole = normalize(roleText)
        val role = aliases.firstOrNull { (alias) -> normalizedRole.contains(alias) }?.second ?: return emptyList()
        return normalize(namesText)
            .replace(trailingNoise, "")
            .replace(Regex("^(?:は|が)?[：:・\\s]*|[。;；\\s]+$"), "")
            .replace(Regex("[（(]\\s*$"), "")
            // Some legacy directories concatenate the next name immediately after a role/reading annotation.
            .replace(Regex("([）)])(?=\\p{L})"), "$1、")
            .split(Regex("\\s*(?:、|,|，|／|/)\\s*"))
            .map { it.replace(Regex("^(?:氏名|名前)\\s*[：:]?\\s*"), "").trim() }
            .mapNotNull { rawName ->
                val annotatedRole = aliases.firstOrNull { (alias) ->
                    Regex("[（(][^）)]*${Regex.escape(alias)}[^）)]*[）)]").containsMatchIn(rawName)
                }?.second
                val name = rawName.replace(Regex("[（(][^）)]*(?:$rolePattern)[^）)]*[）)]"), "").trim()
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
        !Regex("[。！？!?|]|教会|伝道所|電話|住所|不在|募集中|未定|礼拝|聖書|奉仕|皆様|毎週|月に|先生を|ています|ました|します|ください|おります|です|ます|して|され|こと|ため|によって|では").containsMatchIn(value)
}
