// Venera-compatible discovery adapter. Native browse is explicit and never masquerades as a metric.
// Protocol reference: https://github.com/venera-app/venera/blob/master/doc/comic_source.md
function __cialloDiscoverOption(raw) {
    if (raw && typeof raw === 'object') return { value: String(raw.value ?? raw.key ?? ''), label: String(raw.label ?? raw.text ?? raw.value ?? '') };
    const text = String(raw ?? '');
    // MYCOMIC sort keys are negative ("-week-週排行"); keep the leading sign.
    const dash = /^-[a-z_0-9]+-/i.test(text) ? text.indexOf('-', 1) : text.indexOf('-');
    return { value: dash < 0 ? text : text.slice(0, dash), label: dash < 0 ? text : text.slice(dash + 1) };
}
function __cialloDiscoverKind(label) {
    const text = String(label || '');
    if (/更新|最新|最近上架|newest|latest|recent|new releases|新着|新刊/i.test(text)) return 'updates';
    if (/热议|熱議|讨论|討論|discussion|comments/i.test(text)) return 'discussion';
    if (/点击|點擊|阅读量|閱讀量|views|clicks/i.test(text)) return 'clicks';
    if (/榜|排行|热门|熱門|人气|人氣|飙升|飆升|popular|ranking|trending|hot/i.test(text)) return 'ranking';
    return null;
}
function __cialloCategoryKey(part, target) {
    const a=target.attributes||{};
    const text=JSON.stringify([target.page,a.param??null,a.category??a.keyword??'',a.options??[]]);
    let hash=2166136261;
    for(let n=0;n<text.length;n++)hash=Math.imul(hash^text.charCodeAt(n),16777619);
    return 'dynamic:'+part+':'+(hash>>>0).toString(16);
}
function __cialloDiscoverCapabilities(feeds) {
    const result = {};
    const add = (kind, range) => { if (kind && range) (result[kind] ||= []).includes(range) || result[kind].push(range); };
    for (const f of feeds) {
        const text = String(f.label || '');
        if (f.kind === 'updates') add('updates', '最新');
        if (f.kind === 'ranking') {
            add('ranking', '热门');
            if (/飙升|飆升|rising|trending/i.test(text)) add('ranking', '飙升');
            if (/新作|新书|新書|new/i.test(text)) add('ranking', '新作');
            if (/完结|完結|completed/i.test(text)) add('ranking', '完结');
        }
        if (['discussion', 'clicks'].includes(f.kind)) {
            for (const [range, pattern] of Object.entries(f.kind === 'discussion' ?
                {'24h':/24h|24小时|24小時|日|day/i,'7d':/7d|7天|周|週|week/i,'30d':/30d|30天|月|month/i} :
                {'日':/日|day/i,'周':/周|週|week/i,'月':/月|month/i,'总':/总|總|历史|歷史|all|views/i}))
                if (pattern.test(text)) add(f.kind, range);
        }
    }
    return result;
}
function __cialloDiscoverCatalog(src) {
    const feeds = [];
    const sections = Array.isArray(src.explore) ? src.explore : [];
    sections.forEach((s, index) => {
        if (s && !/账号|帳號|登录|登入|account|login/i.test(s.title || '') && (typeof s.load === 'function' || typeof s.loadNext === 'function'))
            feeds.push({ id: 'explore:' + index, label: s.title || '原站推荐', kind: __cialloDiscoverKind(s.title), type: s.type || 'singlePageWithMultiPart' });
    });
    const ranking = src.categoryComics && src.categoryComics.ranking;
    if (ranking && !ranking.__cialloUnavailable && (typeof ranking.load === 'function' || typeof ranking.loadNext === 'function' || typeof ranking.loadWithNext === 'function')) {
        const options = Array.isArray(ranking.options) ? ranking.options : Object.entries(ranking.options || {}).map(([value, label]) => ({ value, label }));
        (options.length ? options : ['-原站排行']).forEach(raw => {
            const o = __cialloDiscoverOption(raw);
            feeds.push({ id: 'ranking:' + o.value, label: o.label,
                kind: __cialloDiscoverKind(o.label) === 'clicks' || /^-?(views|clicks|reads)$/i.test(o.value) ? 'clicks' : 'ranking', type: 'ranking', value: o.value });
        });
    }
    const categories = [];
    const parts = src.category && Array.isArray(src.category.parts) ? src.category.parts : [];
    parts.forEach((part, index) => {
        if (!Array.isArray(part.categories)) return;
        part.categories.forEach((name, n) => {
            if (name && typeof name === 'object') {
                const target = name.target, a = target && target.attributes || {};
                if (!target || !['category', 'search'].includes(target.page)) return;
                categories.push({ id: 'category:' + index + ':' + n, label: String(name.label || a.category || a.keyword || ''),
                    group: String(part.name || ''), category: String(a.category || a.keyword || name.label || ''), param: a.param ?? null,
                    itemType: target.page, dynamic: false, options: a.options || [] });
                return;
            }
            categories.push({ id: 'category:' + index + ':' + n, label: String(name), group: String(part.name || ''),
                category: String(name), param: Array.isArray(part.categoryParams) ? part.categoryParams[n] : part.groupParam ?? null,
                itemType: part.itemType || 'category', dynamic: false });
        });
    });
    const dynamic = parts.some(part => part.type === 'dynamic' && typeof part.loader === 'function');
    // EH exposes these native categories through search option 0 rather than category.parts.
    if (src.key === 'ehentai' && !categories.length && src.search?.loadNext) {
        ['其他','同人志','漫画','画师CG','游戏CG','图集','Cosplay','写真','全年龄','欧美'].forEach((label,index)=>
            categories.push({id:'eh:category:'+index,label,group:'内容形式',category:'',itemType:'search',options:[JSON.stringify([String(index)]),null,null]}));
    }
    return { feeds, categories, capabilities: __cialloDiscoverCapabilities(feeds), dynamicCategories: dynamic, hasCategoryLoader: !!(src.categoryComics &&
        (typeof src.categoryComics.load === 'function' || typeof src.categoryComics.loadNext === 'function')),
        explicit: !!(src.discover && typeof src.discover.load === 'function') };
}
async function __cialloDiscoverDynamicCategories(src, catalog) {
    const cached = src.__cialloDynamicCategories;
    if (cached && Date.now() - cached.time < 300000 && cached.logged === !!src.isLogged) {
        catalog.categories.push(...cached.rows.filter(c => !catalog.categories.some(old => old.id === c.id)));
        return catalog;
    }
    const parts = src.category && src.category.parts || [];
    for (let i = 0; i < parts.length; i++) {
        const part = parts[i];
        if (part.type !== 'dynamic' || typeof part.loader !== 'function') continue;
        const data = await part.loader.call(src);
        if (!Array.isArray(data)) continue;
        data.forEach((item, n) => {
            const target = item && item.target;
            if (!target || !['category', 'search'].includes(target.page)) return;
            const a = target.attributes || {};
            catalog.categories.push({ id: __cialloCategoryKey(i,target), legacyId:'dynamic:'+i+':'+n, label: String(item.label || a.category || a.keyword || ''),
                group: String(part.name || ''), category: String(a.category || a.keyword || item.label || ''), param: a.param ?? null,
                itemType: target.page, dynamic: true, options: a.options || [] });
        });
    }
    src.__cialloDynamicCategories = {time:Date.now(),logged:!!src.isLogged,rows:catalog.categories.filter(c=>c.dynamic)};
    return catalog;
}
function __cialloDiscoverParts(value, fallback) {
    if (value == null) return [];
    if (Array.isArray(value)) {
        if (value.every(v => v && typeof v === 'object' && 'id' in v && !('comics' in v))) return [{ title: fallback, comics: value }];
        return value.flatMap(v => Array.isArray(v) ? [{ title: fallback, comics: v }] : v && Array.isArray(v.comics) ? [v] : []);
    }
    if (Array.isArray(value.comics)) return [{ title: fallback, comics: value.comics }];
    if (Array.isArray(value.data)) return __cialloDiscoverParts(value.data, fallback);
    return Object.entries(value).filter(([, rows]) => Array.isArray(rows)).map(([title, comics]) => ({ title, comics }));
}
function __cialloDiscoverPagination(value, q, paged) {
    const next = value && value.next != null ? String(value.next) : null;
    if (paged === 'cursor') return { nextCursor: next, hasMore: next != null && next !== String(q.cursor ?? '') };
    if (!paged) return { nextCursor: null, hasMore: false };
    const max = value && value.maxPage;
    // Venera: null maxPage means unknown / keep requesting until an empty page.
    const populated = __cialloDiscoverParts(value, '').some(p => p.comics.length);
    return { nextCursor: null, hasMore: populated && (max == null || Number(max) > q.page) };
}
async function __cialloDiscover(src, q) {
    if (Array.isArray(q.categoryIds) && q.categoryIds.length > 1) {
        const saved = q.cursor ? JSON.parse(q.cursor) : {};
        const next = {}, entries = []; let catalog;
        for (const id of q.categoryIds) {
            if (q.page > 1 && saved[id] === false) { next[id] = false; continue; }
            const part = await __cialloDiscover(src,{...q,feedId:null,categoryIds:[],categoryId:id,
                cursor:saved[id] ?? null,offset:Number(q.offset||0)+entries.length});
            entries.push(...part.entries); catalog=part.catalog;
            next[id] = part.hasMore ? part.nextCursor ?? null : false;
        }
        return {entries:Array.from(new Map(entries.map(e=>[String(e.id),e])).values()),catalog,
            supported:true,native:true,hasMore:Object.values(next).some(v=>v!==false),nextCursor:JSON.stringify(next)};
    }
    if (q.categoryIds && q.categoryIds.length === 1) q = {...q,feedId:null,categoryId:q.categoryIds[0]};
    const catalog = __cialloDiscoverCatalog(src);
    if (q.catalogOnly) return q.resolveDynamic ? await __cialloDiscoverDynamicCategories(src, catalog) : catalog;
    if (src.discover && typeof src.discover.load === 'function' && !q.feedId && !q.categoryId) {
        const result = await src.discover.load.call(src, q);
        return { ...result, catalog };
    }
    let result, label, paged, kind, categoryTags = [];
    if (q.categoryId) {
        if (String(q.categoryId).startsWith('dynamic:')) await __cialloDiscoverDynamicCategories(src, catalog);
        const category = catalog.categories.find(c => c.id === q.categoryId || c.legacyId === q.categoryId);
        if (!category) return { supported: false, entries: [], catalog, notice: '书源分类已变化，请重新选择' };
        const api = category.itemType === 'search' ? src.search : src.categoryComics;
        if (!api || (typeof api.load !== 'function' && typeof api.loadNext !== 'function'))
            return { supported: false, entries: [], catalog, notice: '该书源未实现分类加载接口' };
        const optionDefinitions = typeof api.optionLoader === 'function' ? await api.optionLoader.call(src, category.category, category.param)
            : typeof api.optionList === 'function' ? await api.optionList.call(src, category.category, category.param) : api.optionList;
        const options = category.options && category.options.length ? category.options : (Array.isArray(optionDefinitions) ? optionDefinitions : [])
            .filter(o => !(o.notShowWhen || []).includes(category.category) && (!(o.showWhen || []).length || o.showWhen.includes(category.category)))
            .map(o => {const value=o.default ?? o.defaultValue ?? __cialloDiscoverOption((o.options || [])[0]).value;
                return Array.isArray(value)?JSON.stringify(value):value == null?null:String(value)});
        if (category.itemType === 'search') {
            paged = typeof api.load === 'function' && !api.__cialloCursorOnly ? true : 'cursor';
            result = paged === 'cursor' ? await api.loadNext.call(src, category.category, options, q.cursor ?? null)
                : await api.load.call(src, category.category, options, q.page);
        } else {
            paged = typeof api.load === 'function' ? true : 'cursor';
            result = paged === 'cursor' ? await api.loadNext.call(src, category.category, category.param, options, q.cursor ?? null)
                : await api.load.call(src, category.category, category.param, options, q.page);
        }
        label = category.label; kind = 'native'; categoryTags = [category.label];
    } else {
        let feed = q.feedId && q.feedId !== 'native' ? catalog.feeds.find(f => f.id === q.feedId) : null;
        const desired = String(q.dimension || '').toLowerCase();
        if (!feed && q.feedId === 'native') {
            feed = catalog.feeds[0];
            if (!feed && catalog.dynamicCategories) await __cialloDiscoverDynamicCategories(src, catalog);
            if (!feed && catalog.categories.length) return __cialloDiscover(src, { ...q, feedId: null, categoryId: catalog.categories[0].id });
        }
        if (!feed && !q.feedId && desired === 'ranking') {
            const rangePattern = { '热门': /热门|熱門|人气|人氣|popular|hot|总|總|all/i, '飙升': /飙升|飆升|rising|trending/i,
                '新作': /新作|新书|新書|new/i, '完结': /完结|完結|completed/i }[q.range];
            feed = catalog.feeds.find(f => f.kind === 'ranking' && rangePattern && rangePattern.test(f.label));
            if (!feed && q.range === '热门') feed = catalog.feeds.find(f => f.type === 'ranking');
        }
        if (!feed && !q.feedId) feed = catalog.feeds.find(f => f.kind === desired &&
            (desired !== 'clicks' || q.range === '总' || ({'日':/日|day/i,'周':/周|週|week/i,'月':/月|month/i}[q.range]?.test(f.label)))) ||
            catalog.feeds.find(f => /multiPart|singlePageWithMultiPart|mixed/i.test(f.type));
        if (!feed) return { supported: false, entries: [], catalog };
        label = feed.label; kind = feed.kind;
        if (feed.type === 'ranking') {
            const api = src.categoryComics.ranking;
            paged = typeof api.load === 'function' ? true : 'cursor';
            const nextLoader = api.loadWithNext || api.loadNext;
            result = paged === 'cursor' ? await nextLoader.call(src, feed.value, q.cursor ?? null) : await api.load.call(src, feed.value, q.page);
        } else {
            const section = src.explore[Number(feed.id.split(':')[1])];
            const isPaged = ['multiPageComicList', 'multiPage'].includes(section.type);
            const isMixed = section.type === 'mixed';
            paged = isMixed ? 'mixed' : isPaged ? (typeof section.load === 'function' ? true : 'cursor') : false;
            if (!paged && q.page > 1) return { entries: [], hasMore: false, supported: true, catalog, origin: label };
            // Venera's actual MultiPageLoadingState starts mixed pages at 1 (despite its 0-based docs).
            // Explicit pageBase:0 remains available for sources that follow the documented contract.
            result = paged === 'cursor' ? await section.loadNext.call(src, q.cursor ?? null)
                : await section.load.call(src, isMixed ? q.page - (section.pageBase === 0 ? 1 : 0) : paged ? q.page : null);
        }
    }
    let parts = __cialloDiscoverParts(result, label);
    const observed = __cialloDiscoverCapabilities(parts.map(p=>({label:p.title,kind:__cialloDiscoverKind(p.title)})));
    for(const [key,ranges] of Object.entries(observed)) catalog.capabilities[key]=Array.from(new Set([...(catalog.capabilities[key]||[]),...ranges]));
    const native = !!q.feedId || !!q.categoryId;
    const desired = String(q.dimension || '').toLowerCase();
    if (!native) {
        parts = parts.filter(p => (kind === desired || __cialloDiscoverKind(p.title) === desired));
        if (desired === 'ranking') {
            const specific = { '飙升': /飙升|飆升|rising|trending/i, '新作': /新作|新书|新書|new/i, '完结': /完结|完結|completed/i }[q.range];
            if (specific) parts = parts.filter(p => specific.test(p.title || '') || specific.test(label || ''));
        }
        // Native time-window names must match the requested window; counts alone cannot certify it.
        if (desired === 'discussion' || (desired === 'clicks' && q.range !== '总')) {
            const window = { '24h': /24h|24小时|24小時|日|day/i, '7d': /7d|7天|周|週|week/i,
                '30d': /30d|30天|月|month/i, '日': /日|day/i, '周': /周|週|week/i, '月': /月|month/i }[q.range];
            parts = parts.filter(p => window && window.test(p.title + ' ' + label));
        }
    }
    const page = __cialloDiscoverPagination(result, q, paged);
    let offset = Number(q.offset || 0);
    const entries = parts.flatMap(part => part.comics.filter(c => c && c.id != null && !String(c.id).startsWith('__') && c.title).map(c => {
        offset++;
        const tags = Array.isArray(c.tags) ? [...c.tags, ...categoryTags]
            : c.tags && typeof c.tags === 'object' ? { ...c.tags, '分类': [...(Array.isArray(c.tags['分类']) ? c.tags['分类'] : []), ...categoryTags] }
            : [...(typeof c.tags === 'string' ? [c.tags] : []), ...categoryTags];
        return { ...c, tags, nativeOrder: offset, origin: String(part.title || label),
            rank: (!native && desired === 'ranking') || kind === 'ranking' ? offset : c.rank ?? null,
            subtitle: c.subtitle ?? c.subTitle ?? null };
    }));
    return { entries, catalog, ...page, supported: native || parts.length > 0, native,
        origin: label };
}
