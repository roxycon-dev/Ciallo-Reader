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
            branch("life", "日常成长", leaf("daily", "日常", "slice of life", "日常生活", "生活"), leaf("comedy", "搞笑", "comedy", "喜剧", "爆笑", "欢乐向", "歡樂向"), leaf("school", "校园", "school life", "校园生活", "学园"), leaf("career", "职场", "工作", "上班族", "office workers"), leaf("food", "美食", "cooking", "烹饪"), leaf("music", "音乐舞蹈", "music", "音乐", "舞蹈", "dance"), leaf("social", "社会现实", "社会", "社會"), leaf("medical", "医疗", "medical"), leaf("youth", "青春成长", "青春", "成长", "coming of age")),
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
        branch("publication", "形式与来源",
            branch("media", "内容形式", leaf("artbook", "画集 / CG", "CG", "CG畫集", "CG雜圖", "画师CG", "游戏CG", "图集", "artbook", "artist cg", "game cg"),
                leaf("threed", "3D作品", "3D", "3D漫畫", "3d comic"), leaf("cosplay", "写真 / Cosplay", "cosplay", "寫真Cosplay", "写真", "photography"),
                leaf("animation", "动画", "anime", "动画", "animation"), leaf("long", "长篇", "長篇")),
            branch("edition", "版本信息", leaf("translation", "汉化", "漢化", "嗶咔漢化", "禁漫漢化組", "translated"),
                leaf("uncensored", "无修正", "無修正", "uncensored"), leaf("language", "语言", "汉语", "中文", "英语", "日语", "Chinese", "English", "Japanese"), leaf("year", "出版年份"), leaf("franchise", "作品系列"), leaf("native_other", "其他原站分类"))),
        DiscoverCategory("adult", "成人与偏好", listOf(
            branch("mature", "内容分级", leaf("adultonly", "限制级", "adult", "18+", "hentai", "成人", "pornographic", "19r", "R-18", "R18+", "18禁", "19+"),
                leaf("explicit", "成人内容", "色情", "erotica", "smut", "情色", "肉欲", "性暴力"), leaf("ecchi", "轻度成人", "ecchi", "性感", "魅惑", "暧昧", "TL")),
            branch("preferences", "原站偏好", leaf("uniform", "制服装扮", "制服", "女學生", "女学生", "女大生", "女教師", "护士", "女僕", "maid", "milf", "巫女", "眼鏡控", "眼镜", "女仆", "泳裝", "泳装", "新婚", "人妻", "御姐", "大姐姐", "空姐", "上班族"),
                leaf("relationship", "成人关系", "NTR", "寝取", "偷情", "继母", "亂倫", "乱伦", "年上", "年下", "母系", "母子", "姐系", "巨乳", "貧乳", "贫乳", "长腿", "美腿", "多人", "巨根", "乳交", "全裸", "汗", "眼鏡", "皮裤", "兽娘", "精灵", "萝莉", "蘿莉", "正太", "loli", "shota", "胸毛", "中出"),
                leaf("play", "特殊偏好", "SM", "BDSM", "束缚", "束縛", "女性支配", "男性支配", "黑絲", "白襪", "黑丝", "白袜", "足交", "腳交", "口交", "颜射", "肛交", "触手", "觸手", "自卫", "自慰", "獵奇", "猎奇", "ryona", "獣姦", "兽奸", "野外露出", "露出", "催眠", "调教", "調教", "痴汉", "痴漢", "重口", "重口地帶", "血腥暴力", "人外", "妖精", "肉感", "骚", "騷", "耻辱", "羞耻", "淫语", "淫亂"))), adult = true)
    )
    val all: List<DiscoverCategory> by lazy { roots.flatMap { listOf(it) + it.children.flatMap { b -> listOf(b) + b.children } } }
    private fun tagKey(value: String) = DiscoverEngine.normalize(value.map(com.example.source.anilist.CjkFoldMap::fold).joinToString(""))
    private val extraAliases = listOf(
        "action" to listOf("热血", "冒险热血", "冒險熱血", "武侠格斗"), "fantasy" to listOf("科幻魔幻", "魔幻", "魔法少女"),
        "detective" to listOf("侦探推理", "偵探推理"), "love" to listOf("耽美爱情", "耽美愛情", "同志 BL", "浪漫"),
        "daily" to listOf("生活漫画", "生活漫畫"), "shonen" to listOf("少男"), "josei" to listOf("淑女", "女士", "女青年"),
        "scifi" to listOf("科学", "科學"), "war" to listOf("战斗", "戰爭", "戰爭史詩"), "hktw" to listOf("台湾", "港台漫画", "台灣", "港漫"),
        "western" to listOf("美漫", "欧美", "欧漫"), "short" to listOf("雜誌短篇", "單行本"), "harem" to listOf("逆后宫"),
        "travel" to listOf("时间旅行"), "cute" to listOf("萌系", "萌"), "game" to listOf("电子游戏", "傳統遊戲", "传统游戏"),
        "historical" to listOf("宗教", "历史文化"), "suspense" to listOf("黑暗", "悬疑惊悚", "悬疑灵异", "推理悬疑"), "gender" to listOf("性转变", "性别变"),
        "adventure" to listOf("奇幻冒险", "动作冒险", "奇幻冒險"), "fantasy" to listOf("奇幻冒险", "奇幻冒險", "奇异幻想", "西幻"),
        "battle" to listOf("战斗热血", "战斗 怪物"), "school" to listOf("校园青春"), "love" to listOf("爱情浪漫", "浪漫爱情", "情感", "同志 BL", "耽美爱情"),
        "career" to listOf("职场都市"), "modern" to listOf("职场都市"), "scifi" to listOf("科幻未来", "科学"),
        "healing" to listOf("治愈温馨"), "survival" to listOf("末日生存"), "martial" to listOf("武侠仙侠"),
        "palace" to listOf("古代宫廷", "欧式宫廷", "后宫·宮廷", "宅斗", "宫廷"), "japan" to listOf("日本漫画"),
        "china" to listOf("国产漫画", "中国"), "korea" to listOf("韩国漫画", "韩国", "咚漫"), "western" to listOf("欧美漫画", "美漫", "欧漫"),
        "music" to listOf("偶像", "歌舞"), "inspiring" to listOf("正能量"), "seinen" to listOf("成人向"),
        "femalelead" to listOf("女性视角", "女性視角"), "supernatural" to listOf("奇异幻想", "奇異幻想"),
        // Exact native novel labels. Never infer a genre from a title substring.
        "fantasy" to listOf("西方奇幻", "玄幻奇幻", "异世界幻想", "现代幻想", "幻想：高幻想", "幻想：低幻想"),
        "cultivation" to listOf("东方仙侠", "都市修真", "仙侠修真", "传统玄幻", "玄幻脑洞", "修真"),
        "scifi" to listOf("科幻末世", "科幻未来", "星际机甲", "科幻：VR游戏", "科幻：宇宙", "科幻：空想科学", "科幻：惊悚"),
        "mecha" to listOf("星际机甲"), "apocalypse" to listOf("科幻末世", "末世生存"), "survival" to listOf("末世生存"),
        "modern" to listOf("都市日常", "都市高武", "都市修真", "都市脑洞", "都市种田", "都市生活", "职场商战", "现实世界", "恋爱：现实世界", "现代幻想", "现代戏剧", "现言", "现言脑洞", "豪门总裁", "职场婚恋"),
        "daily" to listOf("都市日常", "都市生活", "乡村生活", "古代世情", "古风世情"),
        "battle" to listOf("都市高武", "文学：动作"), "war" to listOf("历史军事", "军事战争", "军事", "抗战谍战"),
        "historical" to listOf("历史古代", "历史脑洞", "古代世情", "古风世情", "历史军事", "文学：历史", "历史·时代·传奇", "民国年代", "民国言情"),
        "ancient" to listOf("历史古代", "古代世情", "古风世情", "古言", "古言脑洞", "古代言情", "宫廷宅斗", "宫斗宅斗"),
        "palace" to listOf("宫廷宅斗", "宫斗宅斗"), "martial" to listOf("武侠江湖", "武侠仙侠"),
        "suspense" to listOf("悬疑脑洞", "女频悬疑", "灵异悬疑", "悬疑惊悚", "悬疑灵异", "文学：推理", "科幻：惊悚"),
        "supernatural" to listOf("灵异志怪", "灵异悬疑", "悬疑灵异", "灵异言情"),
        "detective" to listOf("文学：推理", "推理"), "horror" to listOf("文学：恐怖", "恐怖"),
        "love" to listOf("言情", "现言", "古言", "现言脑洞", "古言脑洞", "玄幻言情", "古代言情", "校园言情", "穿越言情", "灵异言情", "民国言情", "豪门总裁", "职场婚恋", "恋爱：异世界", "恋爱：现实世界", "浪漫喜剧"),
        "fantasy" to listOf("玄幻言情"), "ceo" to listOf("豪门总裁"), "sweet" to listOf("青春甜宠"),
        "school" to listOf("校园言情", "校园青春"), "youth" to listOf("青春甜宠", "校园青春"),
        "comedy" to listOf("文学：喜剧", "浪漫喜剧"), "career" to listOf("职场商战", "官场", "职场婚恋"),
        "otherworld" to listOf("恋爱：异世界", "异世界幻想", "幻想：高幻想"), "travel" to listOf("快穿", "穿越言情"),
        "sports" to listOf("游戏体育", "游戏竞技"), "game" to listOf("游戏体育", "游戏竞技", "科幻：VR游戏"),
        "doujin" to listOf("同人衍生", "动漫衍生", "男频衍生", "女频衍生", "男频同人", "明星同人", "动漫同人", "影视同人", "游戏同人", "小说同人"),
        "martial" to listOf("武侠小说"), "cultivation" to listOf("修真仙侠"), "modern" to listOf("都市青春"),
        "youth" to listOf("都市青春"), "war" to listOf("军事历史"), "historical" to listOf("军事历史"),
        "game" to listOf("网游竞技"), "sports" to listOf("网游竞技"), "scifi" to listOf("科幻灵异"),
        "supernatural" to listOf("科幻灵异"), "love" to listOf("言情穿越", "台言古言"), "travel" to listOf("言情穿越"),
        "bl" to listOf("耽美同人"), "doujin" to listOf("耽美同人"),
        "lightnovel" to listOf("轻小说"), "drama" to listOf("文学：人性剧", "现代戏剧"))
        .groupBy({ it.first }, { it.second }).mapValues { it.value.flatten() }
    private val aliasIndex by lazy { all.flatMap { c -> (c.aliases + extraAliases[c.id].orEmpty()).map { tagKey(it) to c.id } }.groupBy({ it.first }, { it.second }) }
    private val ancestors by lazy { all.associate { c -> c.id to all.filter { c.id in it.leaves }.map { it.id }.toSet() } }
    private val metadataNamespaces = setOf("author", "authors", "artist", "artists", "group", "groups", "character", "characters", "parody",
        "作者", "作家", "画师", "画家", "绘师", "繪師", "藝術家", "艺术家", "角色", "社团", "社團", "language", "语言", "語言")
    fun isStoryTag(raw: String): Boolean = !('：' in raw || ':' in raw) ||
        raw.substringBefore('：').substringBefore(':').trim().lowercase() !in metadataNamespaces
    fun classify(tags: List<String>): Set<String> {
        val matched = tags.flatMap { raw ->
            if (!isStoryTag(raw)) emptyList()
            else aliasIndex[tagKey(raw)].orEmpty() + raw.substringAfter('：').substringAfter(':').split(Regex("[,，/、;；|]"))
                .flatMap { tag ->
                    val tokens = if (tag.any { it.code in 0x3400..0x9fff }) listOf(tag) + tag.split(Regex("\\s+")) else listOf(tag)
                    tokens.flatMap { aliasIndex[tagKey(it)].orEmpty() }
                }
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
    /** Every native route stays reachable, including IP, language, year and site-specific tags. */
    fun classifyNative(label: String, group: String): Set<String> {
        val direct = classify(listOf(label))
        if (direct.isNotEmpty()) return direct
        val id = when {
            group.contains("语言") || group.equals("Language", true) || label in setOf("Chinese", "English", "Japanese", "汉语", "英语", "日语", "英語 ENG") -> "language"
            group.contains("年份") || label.matches(Regex("\\d{4}|\\d{2}年代|\\d{2}後")) -> "year"
            group.contains("角色") || group.contains("作品系列") || label.matches(Regex("(?i).*fate.*|.*東方.*|.*东方.*|.*原神.*|.*Love ?Live.*|.*SAO.*|.*碧藍.*")) -> "franchise"
            else -> "native_other"
        }
        return ancestors[id].orEmpty()
    }
    fun labels(tags: List<String>): List<String> {
        val ids = classify(tags)
        return all.filter { it.id in ids && it !in roots && (it.children.isEmpty() || it.children.none { child -> child.id in ids }) }.map { it.label }
            .ifEmpty { tags.filter(::isStoryTag).map { it.substringAfter('：').substringAfter(':') } }.distinct()
    }
}
