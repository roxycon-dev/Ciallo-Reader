/*
 * SPDX-License-Identifier: Apache-2.0
 * Public Xipi reader protocol, ported from Keiyoushi XipiManhua.kt / Dto.kt.
 * Upstream: https://github.com/keiyoushi/extensions-source/tree/cfc019928453a4650669d0514723360629c4af56/src/zh/xipimanhua
 * Changes: JS host integration, bounded catalogue paging, response/identity validation.
 * License and attribution: assets/licenses/keiyoushi-xipi-LICENSE.txt and NOTICE.txt.
 */
class Xipi extends ComicSource {
    name = "嬉皮漫画";
    key = "xipi";
    version = "1.1.0";
    minAppVersion = "1.6.0";
    url = "https://m.hipmh.com";
    baseUrl = "https://m.hipmh.com";
    apiUrl = "https://hipapi1.s3file.top";
    readerUrl = "https://reader.hipmh.top";
    coverUrl = "https://cover.s3imgs.top";
    headers = {"User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36", "Accept": "application/json"};
    catalogs = {};

    decode64(value) {
        value = String(value).replace(/-/g, "+").replace(/_/g, "/");
        value += "=".repeat((4 - value.length % 4) % 4);
        return Convert.decodeUtf8(Convert.decodeBase64(value));
    }

    encode64(value) {
        return Convert.encodeBase64(Convert.encodeUtf8(value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    }

    mid(value) {
        const id = String(value).replace(/^https:\/\/m\.hipmh\.com\/works\//, "").split("-")[0];
        if (!/^[A-Za-z0-9_]+$/.test(id) || !/^m:\d+$/.test(this.decode64(id))) throw new Error("嬉皮漫画作品编号无效");
        return id;
    }

    chapterIdentity(hid) {
        if (!/^[A-Za-z0-9_]+-[A-Za-z0-9_-]+$/.test(hid)) throw new Error("嬉皮漫画章节编号无效");
        const split = hid.indexOf("-");
        const decoded = this.decode64(hid.substring(0, split));
        const ids = decoded.match(/^m:(\d+)-c:(\d+)$/);
        if (!ids) throw new Error("嬉皮漫画章节编号无效");
        return {manga: Number(ids[1]), chapter: Number(ids[2]),
            apiHid: this.encode64("c:" + ids[2]) + hid.substring(split)};
    }

    async api(path) {
        if (!/^\/v[12]\//.test(path)) throw new Error("嬉皮漫画接口地址无效");
        const response = await Network.get(this.apiUrl + path, this.headers);
        if (response.status !== 200 || !response.body) throw new Error("嬉皮漫画请求失败：HTTP " + response.status);
        const json = JSON.parse(response.body);
        if (json.code !== 200 || !json.data) throw new Error("嬉皮漫画接口错误：" + String(json.message || json.code));
        if (json.data.is_locked === true || json.data.requires_login === true || json.data.is_paid === true)
            throw new Error("该章节需要源站登录或购买");
        return json.data;
    }

    cover(path) {
        if (!path) return "";
        if (!/^\/(?!\/)/.test(path)) throw new Error("嬉皮漫画封面地址无效");
        return this.coverUrl + path;
    }

    card(item) {
        const id = this.mid(item.mid || item.id);
        if (!item.title) throw new Error("嬉皮漫画作品数据不完整");
        return new Comic({id, title: String(item.title).trim(), cover: this.cover(item.vertical_image_url),
            author: item.author_names ? item.author_names.join("、") : (item.authors || []).map(a => a.name).filter(Boolean).join("、"),
            tags: (item.genres || []).map(g => typeof g === "string" ? g : g.name).filter(Boolean),
            status: {ongoing:"连载",completed:"完结",hiatus:"休刊"}[item.status] || null,
            updatedAt: item.updated_at || null});
    }

    search = {load: async (keyword, options, page) => {
        keyword = String(keyword).trim().replace("这个勇者是个金钱至上主义者", "这个勇者是金钱至上主义者");
        const data = await this.api("/v1/search?q=" + encodeURIComponent(keyword) + "&page=" + page + "&page_size=20");
        if (!Array.isArray(data.data) || !Number.isInteger(data.total_pages)) throw new Error("嬉皮漫画搜索数据不完整");
        const seen = new Set();
        const comics = data.data.map(item => this.card(item)).filter(item => {
            if (seen.has(item.id)) return false;
            seen.add(item.id); return true;
        });
        return {comics, maxPage: Math.max(1, data.total_pages)};
    }};

    async browse(page, filters = {}) {
        const query = Object.entries({sort:"updated", ...filters, page:Math.max(1, Number(page) || 1), per_page:18})
            .filter(([, value]) => value !== "" && value != null)
            .map(([key, value]) => encodeURIComponent(key) + "=" + encodeURIComponent(value)).join("&");
        const data = await this.api("/v1/mangas?" + query);
        if (!Array.isArray(data.items) || !Number.isInteger(data.total_pages)) throw new Error("嬉皮漫画更新列表不完整");
        return {comics: data.items.map(item => this.card(item)), maxPage: Math.max(1, data.total_pages)};
    }

    explore = [
        {title:"最新更新", type:"multiPageComicList", load:page => this.browse(page)},
        {title:"热门", type:"multiPageComicList", load:page => this.browse(page, {sort:"popular"})},
        {title:"新作", type:"multiPageComicList", load:page => this.browse(page, {sort:"latest"})},
        {title:"完结", type:"multiPageComicList", load:page => this.browse(page, {sort:"popular", status:"completed"})}
    ];

    async browseHtml(path) {
        if (!/^\/(?:$|(?:category|genre)\/[a-z0-9-]+$)/.test(path)) throw new Error("嬉皮漫画分类入口无效");
        const response = await Network.get(this.baseUrl + path, {...this.headers, Accept:"text/html,application/xhtml+xml"});
        if (response.status !== 200 || !response.body) throw new Error("嬉皮漫画分类请求失败：HTTP " + response.status);
        return new HtmlDocument(response.body);
    }

    async nativeCategories(group) {
        if (!this.browseCategories || Date.now() - this.browseCategories.at >= 300000) {
            const doc = await this.browseHtml("/");
            try {
                const seen = new Set(), rows = [];
                for (const link of doc.querySelectorAll("a[href]")) {
                    const route = link.attributes.href || "", label = link.text.trim();
                    const match = route.match(/^\/(category|genre)\/[a-z0-9-]+$/);
                    if (!match || !label || seen.has(route)) continue;
                    seen.add(route);
                    rows.push({group:match[1] === "category" ? "地区" : "题材", label, route});
                }
                if (!rows.length) throw new Error("嬉皮漫画未返回源站分类，请稍后刷新");
                this.browseCategories = {at:Date.now(), rows};
            } finally { doc.dispose(); }
        }
        return this.browseCategories.rows.filter(row => row.group === group).map(row => ({label:row.label,
            target:{page:"category", attributes:{category:row.label, param:row.route}}}));
    }

    category = {title:"嬉皮漫画分类", parts:["地区", "题材"].map(name => ({name, type:"dynamic",
        loader:() => this.nativeCategories(name)}))};

    async nativeFilter(route) {
        if (!/^\/(category|genre)\/[a-z0-9-]+$/.test(String(route))) throw new Error("嬉皮漫画分类入口已变化，请重新选择");
        const cached = this.browseFilters && this.browseFilters[route];
        if (cached && Date.now() - cached.at < 300000) return cached.value;
        const doc = await this.browseHtml(route);
        try {
            const node = doc.querySelector("[data-category-id][data-genre-id]");
            const field = route.startsWith("/category/") ? "category" : "genre";
            const id = node && node.attributes["data-" + field + "-id"];
            if (!/^\d+$/.test(id || "")) throw new Error("嬉皮漫画分类编号缺失，请刷新分类");
            const value = {[field]:id};
            (this.browseFilters ||= {})[route] = {at:Date.now(), value};
            return value;
        } finally { doc.dispose(); }
    }

    categoryComics = {
        optionList:[
            {name:"状态", options:["-全部", "ongoing-连载", "completed-完结"]},
            {name:"排序", options:["updated-更新", "popular-热门", "latest-新作"]}
        ],
        load:async (category, param, options, page) => this.browse(page, {
            ...await this.nativeFilter(param), status:options[0] || "", sort:options[1] || "updated"
        })
    };

    async catalog(mid) {
        mid = this.mid(mid);
        const cached = this.catalogs[mid];
        if (cached && Date.now() - cached.at < 5 * 60 * 1000) return cached.rows;
        const load = page => this.api("/v1/manga/chapters?mid=" + mid + "&page=" + page + "&per_page=50&order=desc");
        const first = await load(1);
        const count = first.total_pages;
        if (!Number.isInteger(count) || count < 1 || count > 512 || !Array.isArray(first.items))
            throw new Error("嬉皮漫画目录分页数据无效");
        const rows = [...first.items];
        for (let start = 2; start <= count; start += 3) {
            const requests = [];
            for (let page = start; page <= Math.min(start + 2, count); page++) requests.push(load(page));
            for (const batch of await Promise.all(requests)) {
                if (!Array.isArray(batch.items) || batch.total_pages !== count) throw new Error("嬉皮漫画目录已变化，请重试");
                rows.push(...batch.items);
            }
        }
        if (!rows.length || (Number.isInteger(first.total) && rows.length !== first.total)) throw new Error("嬉皮漫画章节目录不完整");
        const manga = Number(this.decode64(mid).substring(2)), seen = new Set();
        for (const row of rows) {
            if (!row.hid || !row.title || seen.has(row.hid) || this.chapterIdentity(row.hid).manga !== manga)
                throw new Error("嬉皮漫画章节目录存在重复或错误归属");
            seen.add(row.hid);
        }
        // chapter_number is the site's entry ordinal, including extras; never display it as an episode number.
        rows.reverse();
        this.catalogs[mid] = {at: Date.now(), rows};
        return rows;
    }

    metadata(mid, data) {
        const author = (data.authors || []).map(item => String(item.name || "").trim()).filter(Boolean).join("、");
        const tags = [...(data.categories || []), ...(data.genres || [])].map(item => item.name).filter(Boolean);
        if (!data.title) throw new Error("嬉皮漫画详情数据不完整");
        return {id:mid, title:String(data.title).trim(), description:data.description || "", author,
            cover:this.cover(data.vertical_image_url || data.cover_image_url), tags,
            status:{ongoing:"连载",completed:"完结",hiatus:"休刊"}[data.status] || null,
            updatedAt:data.updated_at || null, latestChapter:data.last_chapter ? data.last_chapter.title : null};
    }

    async discoverMetadata(book) {
        const mid = this.mid(book);
        return this.metadata(mid, await this.api("/v1/manga?mid=" + mid));
    }

    decodeImages(data) {
        const encoded = data.images;
        if (typeof encoded !== "string" || !encoded.startsWith("qM9") || !encoded.endsWith("Z7"))
            throw new Error("嬉皮漫画图片列表格式已变化");
        const body = encoded.substring(3, encoded.length - 2);
        const tailLength = Math.floor((body.length - 5) / 3);
        const headLength = Math.floor((body.length - 5 - tailLength) / 2);
        if (headLength < 0 || tailLength <= 0 || body.substring(headLength, headLength + 2) !== "Vx" ||
            body.substring(body.length - tailLength - 3, body.length - tailLength) !== "pL0")
            throw new Error("嬉皮漫画图片列表分段无效");
        const shuffled = body.substring(body.length - tailLength) + body.substring(0, headLength) +
            body.substring(headLength + 2, body.length - tailLength - 3);
        const blocks = [];
        for (let i = 0; i < shuffled.length; i += 7) {
            const block = shuffled.substring(i, i + 7);
            blocks.push((i / 7) % 2 ? block.split("").reverse().join("") : block);
        }
        const custom = "_-9876543210abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        const standard = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        const b64 = blocks.join("").split("").map(c => {
            const index = custom.indexOf(c);
            if (index < 0) throw new Error("嬉皮漫画图片列表字符无效");
            return standard[index];
        }).join("");
        const paths = JSON.parse(this.decode64(b64));
        if (!Array.isArray(paths) || !paths.length || paths.length > 2000 ||
            !Number.isSafeInteger(data.order_id) || !Number.isSafeInteger(data.sid))
            throw new Error("嬉皮漫画图片列表数据无效");
        const size = BigInt(paths.length);
        const key = ((BigInt(data.sid) * BigInt("2654435761")) ^ (size * BigInt("2246822507"))) % size;
        const decoy = BigInt(data.order_id) ^ key;
        if (decoy >= BigInt(0) && decoy < size) paths.splice(Number(decoy), 1);
        if (!paths.length || new Set(paths).size !== paths.length || paths.some(path => typeof path !== "string" || !/^\/i\//.test(path)))
            throw new Error("嬉皮漫画正文图片缺失或重复");
        const host = data.line === 9 ? "https://hip-tx-s1.s3imgs.top" : "https://hip-tx-1.s3imgs.top";
        return paths.map(path => host + path);
    }

    comic = {
        loadInfo: async book => {
            const mid = this.mid(book), metadata = await this.discoverMetadata(mid);
            const rows = await this.catalog(mid), chapters = {};
            for (const row of rows) chapters[row.hid] = row.title;
            return new ComicDetails({...metadata, tags:{"作者": metadata.author ? [metadata.author] : [], "题材":metadata.tags},
                chapters, url:this.baseUrl + "/works/" + mid});
        },
        loadEp: async (book, hid) => {
            const mid = this.mid(book), identity = this.chapterIdentity(hid);
            if (Number(this.decode64(mid).substring(2)) !== identity.manga) throw new Error("嬉皮漫画章节与作品不匹配");
            const row = (await this.catalog(mid)).find(item => item.hid === hid);
            if (!row) throw new Error("该章节已不在源站目录中");
            const data = await this.api("/v2/chapter?hid=" + encodeURIComponent(identity.apiHid));
            if (data.manga_id !== identity.manga || data.chapter_id !== identity.chapter || data.chapter_title !== row.title)
                throw new Error("嬉皮漫画返回了其他章节，请重试");
            return {images:this.decodeImages(data)};
        },
        onImageLoad: () => ({headers:{"User-Agent":this.headers["User-Agent"], "Accept":"image/webp,image/*;q=0.9,*/*;q=0.8"}}),
        onThumbnailLoad: () => ({headers:{"User-Agent":this.headers["User-Agent"], "Accept":"image/webp,image/*;q=0.9,*/*;q=0.8"}})
    };
}
