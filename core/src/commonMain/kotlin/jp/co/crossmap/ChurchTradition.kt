package jp.co.crossmap

enum class ChurchTradition(
    val aliases: Set<String>,
    val names: LocalizedText,
    val nameParts: LocalizedText = names,
) {
    LUTHERAN(
        setOf("ルーテル", "ルター派", "Lutheran"),
        LocalizedText.of("ルーテル教会", "Lutheran Church", "루터교", "Igreja Luterana", "Gereja Lutheran"),
        LocalizedText.of("ルーテル", "Lutheran", "루터", "Luterana", "Lutheran"),
    ),
    BAPTIST(
        setOf("バプテスト", "Baptist"),
        LocalizedText.of("バプテスト教会", "Baptist Church", "침례교", "Igreja Batista", "Gereja Baptis"),
        LocalizedText.of("バプテスト", "Baptist", "침례", "Batista", "Baptis"),
    ),
    PRESBYTERIAN(
        setOf("長老派", "長老教会", "Presbyterian"),
        LocalizedText.of("長老教会", "Presbyterian Church", "장로교", "Igreja Presbiteriana", "Gereja Presbiterian"),
        LocalizedText.of("長老派", "Presbyterian", "장로", "Presbiteriana", "Presbiterian"),
    ),
    METHODIST(
        setOf("メソジスト", "Methodist"),
        LocalizedText.of("メソジスト教会", "Methodist Church", "감리교", "Igreja Metodista", "Gereja Methodis"),
        LocalizedText.of("メソジスト", "Methodist", "감리", "Metodista", "Methodis"),
    ),
    ANGLICAN(
        setOf("聖公会", "アングリカン", "Anglican"),
        LocalizedText.of("聖公会", "Anglican", "성공회", "Anglicana", "Anglikan"),
    ),
    PENTECOSTAL(
        setOf("ペンテコステ", "ペンテコスタル", "Pentecostal"),
        LocalizedText.of("ペンテコステ", "Pentecostal", "오순절", "Pentecostal", "Pentakosta"),
    ),
    REFORMED(
        setOf("改革派", "Reformed"),
        LocalizedText.of("改革派教会", "Reformed Church", "개혁교회", "Igreja Reformada", "Gereja Reformed"),
        LocalizedText.of("改革派", "Reformed", "개혁", "Reformada", "Reformed"),
    ),
    CATHOLIC(
        setOf("カトリック", "Catholic"),
        LocalizedText.of("カトリック", "Catholic", "가톨릭", "Católica", "Katolik"),
    ),
    ORTHODOX(
        setOf("正教会", "ハリストス正教会", "Orthodox"),
        LocalizedText.of("正教会", "Orthodox Church", "정교회", "Igreja Ortodoxa", "Gereja Ortodoks"),
        LocalizedText.of("正教", "Orthodox", "정교", "Ortodoxa", "Ortodoks"),
    ),
    HOLINESS(
        setOf("ホーリネス", "Holiness"),
        LocalizedText.of("ホーリネス教会", "Holiness Church", "성결교", "Igreja Holiness", "Gereja Holiness"),
        LocalizedText.of("ホーリネス", "Holiness", "성결", "Holiness", "Holiness"),
    ),
    MENNONITE(
        setOf("メノナイト", "Mennonite"),
        LocalizedText.of("メノナイト", "Mennonite", "메노나이트", "Menonita", "Mennonite"),
    ),
    ADVENTIST(
        setOf("アドベンチスト", "セブンスデー・アドベンチスト", "Adventist"),
        LocalizedText.of("アドベンチスト教会", "Adventist Church", "재림교", "Igreja Adventista", "Gereja Advent"),
        LocalizedText.of("アドベンチスト", "Adventist", "재림", "Adventista", "Advent"),
    ),
    ASSEMBLIES_OF_GOD(
        setOf("アッセンブリーズ・オブ・ゴッド", "アッセンブリー・オブ・ゴッド", "Assemblies of God"),
        LocalizedText.of("アッセンブリーズ・オブ・ゴッド", "Assemblies of God", "하나님의성회", "Assembleias de Deus", "Gereja Sidang Jemaat Allah"),
    ),
    EVANGELICAL(
        setOf("福音派", "Evangelical"),
        LocalizedText.of("福音派", "Evangelical", "복음주의", "Evangélica", "Injili"),
    ),
    FULL_GOSPEL(
        setOf("純福音", "フルゴスペル", "Full Gospel"),
        LocalizedText.of("純福音", "Full Gospel", "순복음", "Evangelho Pleno", "Injil Sepenuh"),
    ),
    NAZARENE(
        setOf("ナザレン", "Nazarene"),
        LocalizedText.of("ナザレン", "Nazarene", "나사렛", "Nazareno", "Nazarene"),
    ),
    SALVATION_ARMY(
        setOf("救世軍", "Salvation Army"),
        LocalizedText.of("救世軍", "Salvation Army", "구세군", "Exército de Salvação", "Bala Keselamatan"),
    ),
    ;

    fun name(language: Language): String = names[language]
    fun namePart(language: Language): String = nameParts[language]
}
