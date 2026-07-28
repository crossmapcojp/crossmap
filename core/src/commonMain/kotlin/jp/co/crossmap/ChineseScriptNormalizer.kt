package jp.co.crossmap

/**
 * Deterministic canonicalization used by the shared KMP search path.
 *
 * Display text is never rewritten with this table. It only produces the Simplified Chinese
 * canonical search form; stored official/reviewed script variants remain untouched.
 */
object ChineseScriptNormalizer {
    fun toSimplified(value: String): String = buildString(value.length) {
        value.forEach { append(sourceToSimplified[it] ?: it) }
    }

    /** Display-side fallback only; official or reviewed zh-Hant text must always take precedence. */
    fun toTraditional(value: String): String = buildString(value.length) {
        value.forEach { append(simplifiedOrShinjitaiToTraditional[it] ?: it) }
    }

    private val traditionalToSimplified = mapOf(
        '會' to '会', '東' to '东', '門' to '门', '國' to '国', '廣' to '广', '體' to '体',
        '聖' to '圣', '靈' to '灵', '經' to '经', '復' to '复', '愛' to '爱', '禮' to '礼',
        '節' to '节', '團' to '团', '華' to '华', '聯' to '联', '協' to '协', '總' to '总',
        '區' to '区', '縣' to '县', '鄉' to '乡', '鎮' to '镇', '島' to '岛', '澤' to '泽',
        '濱' to '滨', '邊' to '边', '龍' to '龙', '長' to '长', '萬' to '万', '豐' to '丰',
        '榮' to '荣', '樂' to '乐', '義' to '义', '氣' to '气', '學' to '学', '園' to '园',
        '館' to '馆', '營' to '营', '業' to '业', '開' to '开', '關' to '关', '橋' to '桥',
        '戶' to '户', '歷' to '历', '圖' to '图', '書' to '书', '師' to '师', '兒' to '儿',
        '婦' to '妇', '親' to '亲', '禱' to '祷', '講' to '讲', '傳' to '传', '導' to '导',
        '舊' to '旧', '新' to '新', '進' to '进', '選' to '选', '達' to '达', '遠' to '远',
        '無' to '无', '歸' to '归', '續' to '续', '實' to '实', '應' to '应', '願' to '愿',
        '憐' to '怜', '獨' to '独', '啟' to '启', '創' to '创', '權' to '权', '榮' to '荣',
        '顯' to '显', '處' to '处', '務' to '务', '備' to '备', '據' to '据', '號' to '号',
        '聲' to '声', '臺' to '台', '灣' to '湾', '與' to '与', '為' to '为', '來' to '来',
        '這' to '这', '裡' to '里', '們' to '们', '並' to '并', '從' to '从', '於' to '于',
        '後' to '后', '發' to '发', '現' to '现', '幫' to '帮', '帶' to '带', '領' to '领',
        '際' to '际', '組' to '组', '織' to '织', '網' to '网', '頁' to '页', '資' to '资',
        '訊' to '讯', '錄' to '录', '號' to '号', '點' to '点', '線' to '线', '雲' to '云',
    )

    private val simplifiedOrShinjitaiToTraditional = traditionalToSimplified.entries
        .associate { (traditional, simplified) -> simplified to traditional } + mapOf(
            '恵' to '惠', '沢' to '澤', '浜' to '濱', '辺' to '邊', '広' to '廣', '竜' to '龍',
        )

    private val sourceToSimplified = traditionalToSimplified + mapOf(
        '恵' to '惠', '沢' to '泽', '浜' to '滨', '辺' to '边', '広' to '广', '竜' to '龙',
    )
}
