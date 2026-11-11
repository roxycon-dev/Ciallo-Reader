/** Anonymous MCCMS public reader. Adapted from the site's own search/catalogue/reader HTML. */
class Nineteen extends ComicSource {
    name = "19漫画";
    key = "nineteen";
    version = "1.1.0";
    minAppVersion = "1.6.0";
    url = "https://m.sxchex.com";
    baseUrl = "https://m.sxchex.com";
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
        "Accept": "text/html,application/json;q=0.9,*/*;q=0.8"
    };
    catalogs = {};

    abs(raw) {
        const value = String(raw || "").trim();
        if (value.startsWith("//")) return "https:" + value;
        if (/^https?:\/\//.test(value)) return value;
        return this.baseUrl + (value.startsWith("/") ? value : "/" + value);
    }

    workId(value) {
        const match = String(value).match(/^(?:https:\/\/(?:m|www)\.sxchex\.com)?\/comic\/(\d+)$/);
        if (!match) throw new Error("19漫画作品链接无效");
        return match[1];
    }

    async page(path) {
        if (!path.startsWith("/") || path.startsWith("//")) throw new Error("19漫画请求地址无效");
        const response = await Network.get(this.baseUrl + path, this.headers);
        if (response.status !== 200 || !response.body || !String(response.body).trim())
            throw new Error("19漫画页面请求失败：HTTP " + response.status);
        const body = String(response.body);
        if (path.startsWith("/api/")) JSON.parse(body);
        else if (!/\bMcpath\s*=/.test(body)) throw new Error("19漫画返回了无效页面");
        return body;
    }

    parseCards(html, page) {
        const doc = new HtmlDocument(html);
        try {
            const comics = [], seen = new Set();
            for (const item of doc.querySelectorAll(".common-comic-item")) {
                const title = item.querySelector(".comic__title a"), image = item.querySelector("img");
                if (!title || !/^\/comic\/\d+$/.test(title.attributes.href || "")) continue;
                const id = this.abs(title.attributes.href);
                if (seen.has(id)) continue;
                seen.add(id);
                const latest = item.querySelector(".comic-update a");
                comics.push(new Comic({id, title: title.text.trim(),
                    cover: image ? this.abs(image.attributes["data-original"] || image.attributes.src) : "",
                    latestChapter: latest ? latest.text.trim() : null, tags: []}));
            }
            let maxPage = page;
            for (const link of doc.querySelectorAll("a[href]")) {
                const match = (link.attributes.href || "").match(/(?:[?&]page=|\/page\/)(\d+)/);
                if (match) maxPage = Math.max(maxPage, Number(match[1]));
            }
            return {comics, maxPage};
        } finally { doc.dispose(); }
    }

    search = {load: async (keyword, options, page) => {
        keyword = String(keyword).trim();
        // These are the source's published work titles, not synthetic chapter entries.
        if (keyword === "海贼王") keyword = "航海王";
        if (keyword === "葬送的芙莉莲" || keyword === "葬送者芙莉莲") keyword = "芙莉莲";
        if (keyword === "这个勇者是个金钱至上主义者") keyword = "这个勇者是金钱至上主义者";
        page = Math.max(1, Number(page) || 1);
        return this.parseCards(await this.page("/search?key=" + encodeURIComponent(keyword) + "&page=" + page), page);
    }};

    explore = [
        {title:"最新更新", type:"multiPageComicList", load:async page => {
            page = Math.max(1, Number(page) || 1);
            return this.parseCards(await this.page("/custom/update?page=" + page), page);
        }},
        {title:"热门", type:"multiPageComicList", load:page => this.browse("/category/order/hits", [], page)},
        {title:"完结", type:"multiPageComicList", load:page => this.browse("/category/finish/2/order/hits", [], page)}
    ];

    async nativeCategories(group) {
        if (!this.browseCategories || Date.now() - this.browseCategories.at >= 300000) {
            const doc = new HtmlDocument(await this.page("/category/"));
            try {
                const rows = [], seen = new Set();
                for (const section of doc.querySelectorAll(".cate-col")) {
                    const heading = section.querySelector(".cate-title");
                    const name = heading ? heading.text.trim().replace(/[：:]$/, "") : "";
                    for (const link of section.querySelectorAll("a[href]")) {
                        const route = link.attributes.href || "", label = link.text.trim();
                        if (!/^\/category\/(list|tags|theme|quality|city|copyright|pay|finish)\/\d+$/.test(route) || !label || seen.has(route)) continue;
                        seen.add(route); rows.push({group:name, label, route});
                    }
                }
                if (!rows.length) throw new Error("19漫画未返回源站分类，请稍后刷新");
                this.browseCategories = {at:Date.now(), rows};
            } finally { doc.dispose(); }
        }
        return this.browseCategories.rows.filter(row => row.group === group).map(row => ({label:row.label,
            target:{page:"category", attributes:{category:row.label, param:row.route}}}));
    }

    category = {title:"19漫画分类", parts:["分类", "标签", "类型", "品质", "地区", "版权", "资费", "进度"]
        .map(name => ({name, type:"dynamic", loader:() => this.nativeCategories(name)}))};

    async browse(route, options, page) {
        if (!/^\/category(?:\/(?:list|tags|theme|quality|city|copyright|pay|finish)\/\d+|\/order\/(?:hits|addtime))*\/?$/.test(String(route)))
            throw new Error("19漫画分类入口已变化，请重新选择");
        page = Math.max(1, Number(page) || 1);
        const fields = route.replace(/^\/category\/?/, "").split("/").filter(Boolean);
        const filters = {};
        for (let i = 0; i < fields.length; i += 2) filters[fields[i]] = fields[i + 1];
        if (options[0]) filters.finish = options[0];
        if (options[1]) filters.order = options[1];
        const path = "/category" + Object.entries(filters).map(([key,value]) => "/" + key + "/" + encodeURIComponent(value)).join("");
        return this.parseCards(await this.page(path + "/page/" + page), page);
    }

    categoryComics = {
        optionList:[
            {name:"状态", options:["-全部", "1-连载", "2-完结"]},
            {name:"排序", options:["addtime-更新", "hits-热门"]}
        ],
        load:(category, param, options, page) => this.browse(param, options, page)
    };

    async catalog(id) {
        const cached = this.catalogs[id];
        if (cached && Date.now() - cached.at < 5 * 60 * 1000) return cached.rows;
        const data = JSON.parse(await this.page("/api/comic/chapter?mid=" + id));
        if (data.code !== 1 || !Array.isArray(data.data) || !data.data.length)
            throw new Error("19漫画未返回完整章节目录");
        const seen = new Set();
        const rows = data.data.map(item => {
            const path = String(item.link || "");
            if (!/^\/chapter\/\d+$/.test(path) || !item.name || seen.has(path))
                throw new Error("19漫画章节目录数据不完整");
            seen.add(path);
            return {path, title: String(item.name),
                locked: Number(item.price) > 0 || Number(item.vip) > 0 || Number(item.cion) > 0};
        });
        this.catalogs[id] = {at: Date.now(), rows};
        return rows;
    }

    parseMetadata(html, book) {
        const doc = new HtmlDocument(html);
        try {
            const title = doc.querySelector(".de-info__box .j-comic-title"), cover = doc.querySelector(".de-info__cover img");
            if (!title) throw new Error("19漫画作品已下架或详情页面无效");
            const author = doc.querySelector(".comic-author .name");
            const description = doc.querySelector(".comic-intro .intro-total") || doc.querySelector(".comic-intro .intro");
            const tags = doc.querySelectorAll('.comic-status a[href*="/category/tags/"]').map(n => n.text.trim()).filter(Boolean);
            return {id: book, title: title.text.trim(),
                cover: cover ? this.abs(cover.attributes["data-original"] || cover.attributes.src) : "",
                author: author ? author.text.trim() : "", description: description ? description.text.trim() : "", tags};
        } finally { doc.dispose(); }
    }

    async discoverMetadata(book) { return this.parseMetadata(await this.page("/comic/" + this.workId(book)), book); }

    parseImages(html, bookId, chapterPath) {
        const permission = html.match(/\breadPic\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)/);
        if (!permission || permission[1] !== String(bookId) || "/chapter/" + permission[2] !== chapterPath)
            throw new Error("19漫画阅读页与所选章节不匹配");
        if (Number(permission[3]) > 0 || Number(permission[4]) > 0) throw new Error("该章节需要源站登录或购买");
        const doc = new HtmlDocument(html);
        try {
            const counter = doc.querySelector(".page-index__btn .count");
            const expected = counter ? Number(counter.text.trim()) : 0;
            const pages = doc.querySelectorAll(".rd-article-wr .rd-article__pic");
            if (!Number.isInteger(expected) || expected <= 0 || pages.length !== expected)
                throw new Error("19漫画章节图片不完整：" + pages.length + "/" + expected);
            const seen = new Set();
            const images = pages.map((node, index) => {
                const image = node.querySelector("img[data-original]");
                const url = image ? image.attributes["data-original"] : "";
                if (Number(node.attributes["data-index"]) !== index + 1 || !/^https:\/\//.test(url) || seen.has(url))
                    throw new Error("19漫画章节存在缺页或无效图片");
                seen.add(url);
                return url;
            });
            return {images};
        } finally { doc.dispose(); }
    }

    comic = {
        loadInfo: async book => {
            const id = this.workId(book);
            const metadata = this.parseMetadata(await this.page("/comic/" + id), book);
            const rows = await this.catalog(id), chapters = {};
            for (const row of rows) chapters[row.path] = row.title;
            return new ComicDetails({...metadata, tags: {"作者": metadata.author ? [metadata.author] : [], "题材": metadata.tags},
                chapters, url: this.baseUrl + "/comic/" + id});
        },
        loadEp: async (book, chapterPath) => {
            const id = this.workId(book);
            const row = (await this.catalog(id)).find(item => item.path === chapterPath);
            if (!row) throw new Error("该章节已不在源站目录中");
            if (row.locked) throw new Error("该章节需要源站登录或购买");
            return this.parseImages(await this.page(chapterPath), id, chapterPath);
        },
        onImageLoad: () => ({headers: {"User-Agent": this.headers["User-Agent"], "Referer": this.baseUrl + "/"}}),
        onThumbnailLoad: () => ({headers: {"User-Agent": this.headers["User-Agent"], "Referer": this.baseUrl + "/"}})
    };
}
