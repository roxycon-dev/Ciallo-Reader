// Assigned to src.comic.loadInfo after the upstream class is instantiated. This adapter
// supports both site layouts and does not depend on the exact text of an upstream method.
async function cialloWnacgInfo(id) {
    const base = this.baseUrl;
    const address = value => {
        value = String(value || '').trim();
        if (/^\/{2,}/.test(value)) return 'https://' + value.replace(/^\/+/, '');
        if (/^https?:\/\//i.test(value)) return value;
        if (value.startsWith('/')) return base + value;
        return value ? base + '/' + value : '';
    };
    async function page(number) {
        const response = await Network.get(base + '/photos-index-page-' + number + '-aid-' + encodeURIComponent(id) + '.html?order=asc&mode=list', {});
        if (response.status !== 200) throw new Error('源站返回 HTTP ' + response.status);
        const reason = CialloSourceResponse.problem(response.body, false);
        if (reason) throw new Error(reason);
        return new HtmlDocument(response.body);
    }
    let document = await page(1);
    try {
        const detailMarker = document.querySelector('div#Cover, div.userwrap .uwthumb, #sr_pub, a[data-chid]') ||
            document.querySelectorAll('a[href*="photos-slide-aid-"]').find(link => {
                const match = (link.attributes.href || '').match(/photos-slide-aid-(\d+)/);
                return match && match[1] === String(id);
            });
        const titleNode = document.querySelector('div#comicName, div.userwrap > h2') ||
            (detailMarker ? document.querySelector('h1') : null);
        const title = (titleNode ? titleNode.text : '').trim();
        if (!title) throw new Error('源站未返回作品详情，页面可能已变更或作品已下架，请稍后重试');
        const coverNode = document.querySelector('div#Cover > img, div.userwrap .uwthumb img');
        const attrs = coverNode ? coverNode.attributes : {};
        const cover = address(attrs['data-original'] || attrs['data-src'] || attrs.src);
        const labels = document.querySelectorAll('div.asTBcell.uwconn > label');
        const categoryNode = document.querySelector('p.txtItme > a.pd') || labels[0];
        const countNode = document.querySelector('p.txtItme > span.date') || labels[1];
        const category = categoryNode ? categoryNode.text.split(/[：:]/).pop().trim() : '';
        const count = countNode ? countNode.text.split(/[：:]/).pop().trim() : '';
        const tagNodes = document.querySelectorAll('a.tagshow');
        const tags = new Map();
        if (category) tags.set('分類', [category]);
        if (tagNodes.length) tags.set('標籤', tagNodes.map(node => node.text.trim()).filter(Boolean));
        const descriptionNode = document.querySelector('div.asTBcell.uwconn > p, div.Introduct_Sub');
        const uploaderNode = document.querySelector('a.introName, div.asTBcell.uwuinfo > a > p');
        const description = descriptionNode ? descriptionNode.text.trim() : '';
        const uploader = uploaderNode ? uploaderNode.text.trim() : '';
        const series = document.querySelector('#sr_pub, a[data-chid]') !== null || document.querySelectorAll('a[href*="photos-slide-aid-"]').some(link => {
            const match = (link.attributes.href || '').match(/photos-slide-aid-(\d+)/);
            return match && match[1] !== String(id);
        });
        if (count) tags.set(series ? '章節' : '頁數', [count]);
        let chapters;
        if (series) {
            chapters = new Map();
            let number = 1, maxPage = 1;
            while (true) {
                for (const link of document.querySelectorAll('a[data-chid], a[href*="photos-slide-aid-"]')) {
                    const a = link.attributes || {};
                    const match = (a.href || '').match(/photos-slide-aid-(\d+)/);
                    const chapterId = a['data-chid'] || (match && match[1]);
                    if (chapterId && !chapters.has(chapterId)) chapters.set(chapterId, link.text.trim() || '第' + (chapters.size + 1) + '章');
                }
                for (const link of document.querySelectorAll('.paginator a[href]')) {
                    const match = (link.attributes.href || '').match(/-page-(\d+)-/);
                    if (match) maxPage = Math.max(maxPage, Number(match[1]));
                }
                if (maxPage > 200) throw new Error('源站目录页数异常');
                if (number >= maxPage) break;
                document.dispose(); document = null;
                document = await page(++number);
                if (!document.querySelector('a[data-chid], a[href*="photos-slide-aid-"]'))
                    throw new Error('源站连载目录不完整，请稍后重试');
            }
            if (!chapters.size) throw new Error('源站未返回连载目录，请稍后重试');
        }
        return new ComicDetails({id, title, cover, chapters, tags, description, uploader});
    } finally { if (document) document.dispose(); }
}
