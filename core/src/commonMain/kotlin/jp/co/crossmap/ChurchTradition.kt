package jp.co.crossmap

enum class ChurchTradition(
    val aliases: Set<String>,
    val names: LocalizedText,
    val nameParts: LocalizedText = names,
) {
    LUTHERAN(
        setOf("ルーテル", "ルター派", "Lutheran"),
        LocalizedText.of("ルーテル教会", "Lutheran Church", "루터교", "Igreja Luterana", "Gereja Lutheran", vietnamese = "Hội Thánh Luther"),
        LocalizedText.of("ルーテル", "Lutheran", "루터", "Luterana", "Lutheran", vietnamese = "Luther"),
    ),
    BAPTIST(
        setOf("バプテスト", "Baptist"),
        LocalizedText.of("バプテスト教会", "Baptist Church", "침례교", "Igreja Batista", "Gereja Baptis", vietnamese = "Hội Thánh Báp-tít"),
        LocalizedText.of("バプテスト", "Baptist", "침례", "Batista", "Baptis", vietnamese = "Báp-tít"),
    ),
    PRESBYTERIAN(
        setOf("長老派", "長老教会", "Presbyterian"),
        LocalizedText.of("長老教会", "Presbyterian Church", "장로교", "Igreja Presbiteriana", "Gereja Presbiterian", vietnamese = "Hội Thánh Trưởng Lão"),
        LocalizedText.of("長老派", "Presbyterian", "장로", "Presbiteriana", "Presbiterian", vietnamese = "Trưởng Lão"),
    ),
    METHODIST(
        setOf("メソジスト", "Methodist"),
        LocalizedText.of("メソジスト教会", "Methodist Church", "감리교", "Igreja Metodista", "Gereja Methodis", vietnamese = "Hội Thánh Giám Lý"),
        LocalizedText.of("メソジスト", "Methodist", "감리", "Metodista", "Methodis", vietnamese = "Giám Lý"),
    ),
    ANGLICAN(
        setOf("聖公会", "アングリカン", "Anglican"),
        LocalizedText.of("聖公会", "Anglican", "성공회", "Anglicana", "Anglikan", vietnamese = "Anh giáo"),
    ),
    PENTECOSTAL(
        setOf("ペンテコステ", "ペンテコスタル", "Pentecostal"),
        LocalizedText.of("ペンテコステ", "Pentecostal", "오순절", "Pentecostal", "Pentakosta", vietnamese = "Ngũ Tuần"),
    ),
    REFORMED(
        setOf("改革派", "Reformed"),
        LocalizedText.of("改革派教会", "Reformed Church", "개혁교회", "Igreja Reformada", "Gereja Reformed", vietnamese = "Hội Thánh Cải cách"),
        LocalizedText.of("改革派", "Reformed", "개혁", "Reformada", "Reformed", vietnamese = "Cải cách"),
    ),
    CATHOLIC(
        setOf("カトリック", "Catholic"),
        LocalizedText.of("カトリック", "Catholic", "가톨릭", "Católica", "Katolik", vietnamese = "Công giáo"),
    ),
    ORTHODOX(
        setOf("正教会", "ハリストス正教会", "Orthodox"),
        LocalizedText.of("正教会", "Orthodox Church", "정교회", "Igreja Ortodoxa", "Gereja Ortodoks", vietnamese = "Giáo hội Chính Thống"),
        LocalizedText.of("正教", "Orthodox", "정교", "Ortodoxa", "Ortodoks", vietnamese = "Chính Thống"),
    ),
    HOLINESS(
        setOf("ホーリネス", "Holiness"),
        LocalizedText.of("ホーリネス教会", "Holiness Church", "성결교", "Igreja Holiness", "Gereja Holiness", vietnamese = "Hội Thánh Thánh Khiết"),
        LocalizedText.of("ホーリネス", "Holiness", "성결", "Holiness", "Holiness", vietnamese = "Thánh Khiết"),
    ),
    MENNONITE(
        setOf("メノナイト", "Mennonite"),
        LocalizedText.of("メノナイト", "Mennonite", "메노나이트", "Menonita", "Mennonite", vietnamese = "Mennonite"),
    ),
    ADVENTIST(
        setOf("アドベンチスト", "セブンスデー・アドベンチスト", "Adventist"),
        LocalizedText.of("アドベンチスト教会", "Adventist Church", "재림교", "Igreja Adventista", "Gereja Advent", vietnamese = "Hội Thánh Cơ Đốc Phục Lâm"),
        LocalizedText.of("アドベンチスト", "Adventist", "재림", "Adventista", "Advent", vietnamese = "Cơ Đốc Phục Lâm"),
    ),
    ASSEMBLIES_OF_GOD(
        setOf("アッセンブリーズ・オブ・ゴッド", "アッセンブリー・オブ・ゴッド", "Assemblies of God"),
        LocalizedText.of("アッセンブリーズ・オブ・ゴッド", "Assemblies of God", "하나님의성회", "Assembleias de Deus", "Gereja Sidang Jemaat Allah", vietnamese = "Hội đồng Đức Chúa Trời"),
    ),
    EVANGELICAL(
        setOf("福音派", "Evangelical"),
        LocalizedText.of("福音派", "Evangelical", "복음주의", "Evangélica", "Injili", vietnamese = "Tin Lành"),
    ),
    FULL_GOSPEL(
        setOf("純福音", "フルゴスペル", "Full Gospel"),
        LocalizedText.of("純福音", "Full Gospel", "순복음", "Evangelho Pleno", "Injil Sepenuh", vietnamese = "Phúc Âm Toàn Vẹn"),
    ),
    NAZARENE(
        setOf("ナザレン", "Nazarene"),
        LocalizedText.of("ナザレン", "Nazarene", "나사렛", "Nazareno", "Nazarene", vietnamese = "Nazarene"),
    ),
    SALVATION_ARMY(
        setOf("救世軍", "Salvation Army"),
        LocalizedText.of("救世軍", "Salvation Army", "구세군", "Exército de Salvação", "Bala Keselamatan", vietnamese = "Cứu Thế Quân"),
    ),
    ;

    fun name(language: Language): String = names[language]
    fun namePart(language: Language): String = nameParts[language]
}
