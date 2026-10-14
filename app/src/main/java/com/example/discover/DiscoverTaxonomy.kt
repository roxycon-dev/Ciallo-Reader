package com.example.discover

fun isAdultDiscoverSource(source: com.example.source.BookSource): Boolean =
    source.id.removePrefix("js_") in com.example.source.js.JsSourceRepo.ADULT_KEYS

data class DiscoverCategory(val id: String, val label: String, val children: List<DiscoverCategory> = emptyList(),
    val aliases: Set<String> = emptySet(), val adult: Boolean = false) {
    val leaves: Set<String> by lazy { setOf(id) + children.flatMap { it.leaves } }
}
object DiscoverTaxonomy {
    private fun leaf(id: String, label: String, vararg aliases: String) = DiscoverCategory(id, label, aliases = aliases.toSet() + label)
    private fun branch(id: String, label: String, vararg nodes: DiscoverCategory) = DiscoverCategory(id, label, nodes.toList(), aliases = setOf(label) + when (id) {
        "action" -> setOf("action", "热血", "动作", "動作")
        "fantasy" -> setOf("fantasy", "奇幻", "魔幻")
        "mystery" -> setOf("mystery")
        else -> emptySet()
    })
    val roots = listOf(
        branch("genre", "题材",
            branch("action", "热血冒险", leaf("battle", "战斗", "格斗", "fighting"), leaf("adventure", "冒险", "adventure"), leaf("sports", "运动", "sports", "竞技", "体育", "體育"), leaf("martial", "武侠", "武术"), leaf("survival", "生存", "survival")),
            branch("fantasy", "奇幻科幻", leaf("magic", "魔法", "magic"), leaf("scifi", "科幻", "sci-fi", "science fiction"), leaf("mecha", "机战", "机甲", "mecha", "机战"), leaf("cultivation", "修仙", "仙侠", "玄幻")),
            branch("life", "日常成长", leaf("daily", "日常", "slice of life", "日常生活", "生活"), leaf("comedy", "搞笑", "comedy", "喜剧", "欢乐向", "歡樂向"), leaf("school", "校园", "school life", "校园生活", "学园"), leaf("career", "职场", "工作", "上班族", "office workers"), leaf("food", "美食", "cooking", "烹饪"), leaf("music", "音乐舞蹈", "music", "音乐", "舞蹈", "dance"), leaf("social", "社会现实", "社会", "社會"), leaf("medical", "医疗", "medical"), leaf("youth", "青春成长", "青春", "成长", "coming of age")),
            branch("mystery", "悬疑惊险", leaf("detective", "推理", "detective", "侦探"), leaf("suspense", "悬疑", "thriller", "惊悚"), leaf("horror", "恐怖", "horror"), leaf("crime", "犯罪", "crime", "黑道", "黑帮", "gangs")),
            branch("history", "历史战争", leaf("historical", "历史", "historical"), leaf("war", "战争", "military"), leaf("palace", "宫廷", "宫斗"))),
        branch("emotion", "情感",
            branch("romance", "恋爱关系", leaf("love", "恋爱", "romance", "爱情", "浪漫"), leaf("pure", "纯爱", "初恋"), leaf("bl", "男生之间", "bl", "boys love", "耽美", "yaoi", "shounen ai", "shonen ai", "少年爱"), leaf("gl", "女生之间", "gl", "girls love", "百合", "yuri", "shoujo ai", "少女爱")),
            branch("bond", "陪伴羁绊", leaf("friendship", "友情", "伙伴"), leaf("family", "亲情", "家庭"), leaf("healing", "治愈", "iyashikei")),
            branch("tone", "情绪口味", leaf("sweet", "甜宠", "甜蜜"), leaf("angst", "虐心", "悲剧", "tragedy", "致郁"), leaf("drama", "剧情纠葛", "drama", "剧情", "情感纠葛"), leaf("psychological", "心理", "psychological"), leaf("inspiring", "励志", "勵志", "inspirational"), leaf("revenge", "复仇", "revenge"))),
        branch("setting", "设定",
            branch("world", "故事世界", leaf("modern", "现代", "都市"), leaf("ancient", "古风", "古代"), leaf("otherworld", "异世界", "isekai"), leaf("apocalypse", "末世", "丧尸", "zombies", "末日", "后末日", "post-apocalyptic", "僵尸"), leaf("supernatural", "灵异", "supernatural", "神鬼", "超自然", "幽灵"), leaf("imaginary", "架空", "架空世界")),
            branch("premise", "主角与机制", leaf("rebirth", "重生", "转生"), leaf("travel", "穿越", "time travel"), leaf("system", "系统", "升级"), leaf("superpower", "异能", "superhero", "超能力"), leaf("game", "游戏", "video games", "游戏竞技"), leaf("villain", "反派", "恶役", "恶役千金"), leaf("rise", "逆袭", "逆袭")),
            branch("cast", "角色类型", leaf("femalelead", "女主视角", "大女主"), leaf("ensemble", "群像", "群像剧"), leaf("monster", "妖怪", "demons", "魔物", "怪物", "恶魔", "魔物娘"), leaf("animal", "动物", "animals", "萌宠"), leaf("harem", "后宫", "harem", "逆后宫"), leaf("gender", "性转", "gender bender", "性别转换", "性转换", "性轉換"), leaf("crossdress", "反串", "伪娘", "偽娘", "女装", "crossdressing"), leaf("ceo", "霸总", "总裁"))),
        branch("audience", "受众",
            branch("demographic", "阅读群体", leaf("shonen", "少年", "shounen", "shonen"), leaf("shojo", "少女", "shoujo", "shojo"), leaf("seinen", "青年", "seinen"), leaf("josei", "女性", "josei"), leaf("children", "儿童", "kids"), leaf("allages", "全年龄", "all ages")),
            branch("format", "阅读形式", leaf("webtoon", "条漫", "webtoon", "长条"), leaf("page", "页漫"), leaf("color", "全彩", "full color", "彩色", "官方上色", "粉丝上色"), leaf("short", "短篇", "one shot", "单篇"), leaf("anthology", "合集", "anthology", "选集"), leaf("doujin", "同人", "doujinshi", "同人志"), leaf("adaptation", "改编", "adaptation", "light novel adaptation"), leaf("fourpanel", "四格", "4-koma"), leaf("magazine", "杂志", "雜誌"), leaf("lightnovel", "轻小说相关", "轻小说", "輕小說", "light novel"), leaf("webcomic", "网络连载", "webcomic", "网络漫画")),
            branch("region", "地区风格", leaf("japan", "日漫", "日本", "manga", "japanese"), leaf("china", "国漫", "内地", "大陆", "manhua"), leaf("korea", "韩漫", "韩国", "manhwa"), leaf("hktw", "港台", "港台漫画"), leaf("western", "欧美", "western")),
            branch("taste", "阅读偏好", leaf("cute", "萌系"), leaf("otaku", "宅系", "宅男", "宅向"), leaf("award", "获奖", "award winning"))),
        DiscoverCategory("adult", "成人向", listOf(branch("mature", "成人内容", leaf("adultonly", "限制级", "adult", "18+", "hentai", "成人", "pornographic", "19r", "R-18", "R18+", "18禁", "19+"), leaf("explicit", "露骨内容", "色情", "erotica", "smut", "情色", "肉欲", "性暴力"))), adult = true)
    )
    val all: List<DiscoverCategory> by lazy { roots.flatMap { listOf(it) + it.children.flatMap { b -> listOf(b) + b.children } } }
    private fun tagKey(value: String) = DiscoverEngine.normalize(value.map(com.example.source.anilist.CjkFoldMap::fold).joinToString(""))
    private val aliasIndex by lazy { all.flatMap { c -> c.aliases.map { tagKey(it) to c.id } }.groupBy({ it.first }, { it.second }) }
    private val ancestors by lazy { all.associate { c -> c.id to all.filter { c.id in it.leaves }.map { it.id }.toSet() } }
    private val metadataNamespaces = setOf("author", "authors", "artist", "artists", "group", "groups", "character", "characters", "parody",
        "作者", "作家", "画师", "画家", "绘师", "繪師", "藝術家", "艺术家", "角色", "社团", "社團", "language", "语言", "語言")
    fun isStoryTag(raw: String): Boolean = !('：' in raw || ':' in raw) ||
        raw.substringBefore('：').substringBefore(':').trim().lowercase() !in metadataNamespaces
    fun classify(tags: List<String>): Set<String> {
        val matched = tags.flatMap { raw ->
            if (!isStoryTag(raw)) emptyList()
            else raw.substringAfter('：').substringAfter(':').split(Regex("[,，/、;；|]"))
                .flatMap { aliasIndex[tagKey(it)].orEmpty() }
        }.toSet()
        return matched.flatMap { ancestors[it].orEmpty() }.toSet()
    }
    /** OR within one facet; AND between different facets. */
    fun matches(tags: List<String>, selected: Set<String>): Boolean {
        val classified = classify(tags)
        return roots.all { root -> val picks = selected.intersect(root.leaves)
            picks.isEmpty() || picks.any(classified::contains) }
    }
    fun isAdult(tags: List<String>) = classify(tags).any { it in roots.last().leaves }
}
