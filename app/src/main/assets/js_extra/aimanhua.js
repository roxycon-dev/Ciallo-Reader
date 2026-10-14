/**
 * Public MCCMS reader adapter. Protocol reference (not a Venera source):
 * https://github.com/keiyoushi/extensions-source/blob/main/lib-multisrc/mccms/src/eu/kanade/tachiyomi/multisrc/mccms/MCCMSWeb.kt
 * Uses the site's chapter API so the collapsed HTML catalogue cannot truncate downloads.
 */
class AiManhua extends ComicSource {
    name = "爱漫画";
    key = "aimanhua";
    version = "1.0.4";
    minAppVersion = "1.6.0";
    url = "https://www.alipaypay.com";
    baseUrl = "https://www.alipaypay.com";
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
        "Accept": "text/html,application/json;q=0.9,*/*;q=0.8"
    };
    catalogs = {};
    // The publisher exposes this origin in Mcpath.url on the canonical website.
    // Keep canonical work/chapter IDs so existing shelves and progress still match.
    readOrigin = "https://mh.iqtao.cn";

    abs(value) {
        value = String(value || "").trim();
        if (value.startsWith("//")) return "https:" + value;
        if (/^https?:\/\//.test(value)) return value;
        return this.baseUrl + (value.startsWith("/") ? value : "/" + value);
    }

    bookId(value) {
        const match = String(value).match(/^(?:https:\/\/www\.alipaypay\.com)?\/(\d+)\.html$/);
        if (!match) throw new Error("爱漫画作品链接无效");
        return match[1];
    }

    async page(url) {
        if (!url.startsWith(this.baseUrl + "/")) throw new Error("爱漫画请求地址无效");
        const path = url.substring(this.baseUrl.length);
        const allOrigins = [...new Set([this.readOrigin, "https://mh.iqtao.cn", this.baseUrl])];
        if (!this.failedOrigins) this.failedOrigins = {};
        const origins = allOrigins.filter(origin => !this.failedOrigins[origin] || Date.now() - this.failedOrigins[origin] > 60000);
        if (!origins.length) origins.push(this.readOrigin);
        let lastError;
        for (const origin of origins) {
            try {
                const r = await Network.get(origin + path, this.headers);
                if (r.status === 401 || r.status === 403) throw new Error("爱漫画站点访问受限：" + r.status);
                if ((r.url || "").includes("/err/")) throw new Error("爱漫画作品已下架");
                if (r.status !== 200 || !r.body || !String(r.body).trim()) throw new Error("爱漫画页面请求失败：" + r.status);
                const body = String(r.body);
                if (path.startsWith("/index.php/api/")) JSON.parse(body);
                else if (!/\bMcpath\s*=/.test(body)) throw new Error("爱漫画返回了无效页面");
                this.readOrigin = origin;
                delete this.failedOrigins[origin];
                return body;
            } catch (error) {
                if (/作品已下架|访问受限/.test(String(error))) throw error;
                this.failedOrigins[origin] = Date.now();
                lastError = error;
            }
        }
        throw new Error("爱漫画线路暂时不可用：" + String(lastError));
    }

    async catalog(id) {
        const cached = this.catalogs[id];
        if (cached && Date.now() - cached.at < 5 * 60 * 1000) return cached.rows;
        const json = JSON.parse(await this.page(this.baseUrl + "/index.php/api/comic/chapter?mid=" + id));
        if (json.code !== 1 || !Array.isArray(json.data) || !json.data.length) throw new Error("爱漫画未返回完整章节目录");
        const rows = json.data.map(item => {
            const path = String(item.link || "");
            if (!new RegExp("^/" + id + "/[0-9]+\\.html$").test(path) || !item.name) throw new Error("爱漫画章节数据不完整");
            return {path, title: String(item.name), pages: Number(item.pnum), locked: Number(item.price) > 0 || Number(item.vip) > 0 || Number(item.cion) > 0};
        });
        this.catalogs[id] = {at: Date.now(), rows};
        return rows;
    }

    parseSearch(html) {
        const doc = new HtmlDocument(html);
        try {
            const comics = [];
            for (const item of doc.querySelectorAll(".bookList_3 .item")) {
                const title = item.querySelector("p.title a");
                const img = item.querySelector("img.cover");
                const author = item.querySelector("p.tip");
                if (!title) continue;
                const id = this.abs(title.attributes.href);
                this.bookId(id);
                comics.push(new Comic({id, title: title.text.trim(), cover: img ? this.imageUrl(img.attributes.src) : "", subTitle: author ? author.text.trim() : "", tags: []}));
            }
            if (!comics.length && !doc.querySelector(".bookList_3")) throw new Error("爱漫画未返回搜索页面，请重试");
            return {comics, maxPage: 1};
        } finally { doc.dispose(); }
    }

    // MCCMSWeb's catalogue routes; discover uses the site's real paging links.
    explore = [{title: "最新更新", type: "multiPageComicList",
        load: async page => this.browse("/category/order/updatetime", page)},
        {title: "新作上架", type: "multiPageComicList", load: async page => this.browse("/category/order/addtime", page)},
        {title: "完结榜", type: "multiPageComicList", load: async page => this.browse("/category/finish/2", page)}];

    category = {title: "爱漫画分类", parts: [{name: "题材", type: "fixed",
        categories: ["全部", "冒险热血", "武侠格斗", "科幻魔幻", "侦探推理", "耽美爱情", "生活漫画"],
        categoryParams: ["/category/order/addtime", "/category/tags/6", "/category/tags/9", "/category/tags/10", "/category/tags/11", "/category/tags/12", "/category/tags/13"],
        itemType: "category"}, {name: "状态", type: "fixed", categories: ["连载", "完结"],
        categoryParams: ["/category/finish/1", "/category/finish/2"], itemType: "category"}], enableRankingPage: false};

    categoryComics = {load: async (category, param, options, page) => {
        if (!/^\/category\/(?:order\/(?:addtime|updatetime)|tags\/\d+|finish\/[12])$/.test(param || "")) throw new Error("爱漫画分类已变化，请重新选择");
        return this.browse(param, page);
    }};

    async browse(path, page) {
        page = Math.max(1, Number(page) || 1);
        const html = await this.page(this.baseUrl + path + "/page/" + page);
        const doc = new HtmlDocument(html);
        try {
            const comics = [], seen = new Set();
            for (const item of doc.querySelectorAll(".bookList_3 .item")) {
                const title = item.querySelector("p.title a"), image = item.querySelector("img.cover");
                const raw = title && (title.attributes.href || "");
                if (!/^\/\d+\.html$/.test(raw || "") || !title.text.trim() || seen.has(raw)) continue;
                seen.add(raw);
                const author = item.querySelector("p.tip"), chapter = item.querySelector(".msg");
                comics.push({id: this.abs(raw), title: title.text.trim(),
                    cover: image ? this.imageUrl(image.attributes["data-src"] || image.attributes.src) : "",
                    author: author ? author.text.trim() : "", latestChapter: chapter ? chapter.text.trim() : null,
                    status: /\/finish\/1$/.test(path) ? "连载" : /\/finish\/2$/.test(path) ? "完结" : null, tags: []});
            }
            if (!comics.length && !doc.querySelector(".bookList_3")) throw new Error("爱漫画未返回目录，请重试");
            let maxPage = page;
            for (const a of doc.querySelectorAll('a[href*="/page/"]')) {
                const match = (a.attributes.href || "").match(/\/page\/(\d+)(?:[/?#]|$)/);
                if (match) maxPage = Math.max(maxPage, Number(match[1]));
            }
            return {comics, maxPage};
        } finally { doc.dispose(); }
    }

    imageUrl(raw) {
        const url = this.abs(raw);
        // WordPress serves valid JPEG bytes for these AVIF-named origin URLs.
        // Its explicit width rendition also repairs stale HTTP 400 cache entries.
        if (/^https:\/\/i[0-3]\.wp\.com\//.test(url) && !/[?&]w=/.test(url)) return url + (url.includes("?") ? "&" : "?") + "w=1080";
        return url;
    }

    parseMetadata(html, book) {
        const doc = new HtmlDocument(html);
        try {
            const title = doc.querySelector("h1.name_mh"), cover = doc.querySelector("img[title]");
            if (!title) throw new Error("爱漫画详情页面无效");
            const spans = doc.querySelectorAll(".info > p > span").map(node => node.text.trim());
            const field = pattern => { const text = spans.find(value => pattern.test(value)); return text ? text.replace(pattern, "").trim() : null; };
            const author = field(/^作\s*者\s*[:：]\s*/);
            const status = field(/^状\s*态\s*[:：]\s*/);
            const updateTime = field(/^更新时间\s*[:：]\s*/);
            const tags = doc.querySelectorAll('.info a[href*="/category/tags/"]').map(a => a.text.trim()).filter(Boolean);
            return {id:book,title:title.text.trim(),cover:cover ? this.imageUrl(cover.attributes.src) : "",author:author || "",status,updateTime,tags};
        } finally { doc.dispose(); }
    }

    // One detail HTML request, without loading the chapter API, for progressive metadata.
    async discoverMetadata(book) { return this.parseMetadata(await this.page(book), book); }

    parseImages(html, expected) {
        const doc = new HtmlDocument(html);
        try {
            const images = doc.querySelectorAll("#comicContain img[data-src]").map(img => this.imageUrl(img.attributes["data-src"]));
            if (!images.length) throw new Error("爱漫画未提供正文图片");
            if (!Number.isInteger(expected) || expected <= 0 || images.length !== expected) throw new Error("爱漫画章节图片不完整：" + images.length + "/" + expected);
            if (images.some(url => !/^https?:\/\//.test(url))) throw new Error("爱漫画图片地址无效");
            return {images};
        } finally { doc.dispose(); }
    }

    search = {load: async (keyword, options, page) => {
        if (page > 1) return {comics: [], maxPage: 1};
        return this.parseSearch(await this.page(this.baseUrl + "/search/" + encodeURIComponent(keyword)));
    }};

    comic = {
        loadInfo: async (book) => {
            const id = this.bookId(book);
            const html = await this.page(this.baseUrl + "/" + id + ".html");
            const rows = await this.catalog(id);
            const doc = new HtmlDocument(html);
            try {
                const title = doc.querySelector("h1.name_mh");
                const cover = doc.querySelector("img[title]");
                if (!title) throw new Error("爱漫画详情页面无效");
                const chapters = {};
                // API order preserves announcements and half chapters; IDs are not episode numbers.
                for (const row of rows) chapters[this.abs(row.path)] = row.title;
                const description = doc.querySelector(".info p.content");
                const authorEl = doc.querySelector(".info > p > span.l");
                const author = authorEl ? authorEl.text.replace(/^作\s*者\s*[:：]\s*/, "").trim() : "";
                const metadata = this.parseMetadata(html, book);
                return new ComicDetails({...metadata, title: title.text.trim(), cover: cover ? this.imageUrl(cover.attributes.src) : "", description: description ? description.text.split("\\n")[0].trim() : "", tags: {"作者": author ? [author] : [], "状态": metadata.status ? [metadata.status] : [], "更新": metadata.updateTime ? [metadata.updateTime] : [], "题材": metadata.tags}, chapters, url: this.baseUrl + "/" + id + ".html"});
            } finally { doc.dispose(); }
        },
        loadEp: async (comicId, epId) => {
            const url = this.abs(epId);
            const match = url.match(/^https:\/\/www\.alipaypay\.com\/(\d+)\/(\d+)\.html$/);
            if (!match) throw new Error("爱漫画章节链接无效");
            const row = (await this.catalog(match[1])).find(item => this.abs(item.path) === url);
            if (!row) throw new Error("该章节已不在源站目录中");
            if (row.locked) throw new Error("该章节需要源站授权");
            return this.parseImages(await this.page(url), row.pages);
        },
        // Keep online and download requests on the verified JPEG rendition; the
        // downloader's default WebP Accept negotiates a separate stale CDN cache.
        onImageLoad: () => ({headers: {"User-Agent": this.headers["User-Agent"], "Accept": "image/jpeg"}}),
        onThumbnailLoad: () => ({headers: {"User-Agent": this.headers["User-Agent"], "Accept": "image/jpeg"}})
    };
}
