// Keep valid JSON semantics (including primitives and revivers); normalize UTF BOMs and
// report wrong pages without leaking HTML, credentials or decrypted contents to the UI.
(function () {
    const originalParse = JSON.parse;
    const clean = value => typeof value === 'string' ? value.replace(/^\s*\uFEFF/, '') : value;
    function problem(value, expectJson) {
        const text = String(value == null ? '' : value).replace(/^[\s\uFEFF]+/, '');
        if (!text) return '源站返回空响应，请稍后重试';
        if (text[0] !== '<') return null;
        const sample = text.slice(0, 32768);
        if (/<title[^>]*>\s*(Just a moment|Attention Required|Access denied|Forbidden|Service Unavailable)/i.test(sample)
            || sample.toLowerCase().includes('/cdn-cgi/challenge-platform/'))
            return '源站返回验证或拦截页面，请在书源管理完成站点验证或稍后重试';
        return expectJson ? '源站返回网页而非接口数据，请稍后重试或切换该源线路' : null;
    }
    JSON.parse = function (value, reviver) {
        const normalized = clean(value);
        try { return originalParse(normalized, reviver); }
        catch (error) {
            if (!(error instanceof SyntaxError)) throw error;
            if (typeof reviver === 'function') {
                // A valid payload with an intentionally throwing reviver is not a parse failure.
                let valid = false;
                try { originalParse(normalized); valid = true; } catch (_) {}
                if (valid) throw error;
            }
            const reason = problem(normalized, true);
            const failure = new SyntaxError(reason || '接口数据不完整或 JSON 格式异常，请稍后重试');
            failure.code = 'CIALLO_RESPONSE_FORMAT';
            throw failure;
        }
    };
    globalThis.CialloSourceResponse = Object.freeze({
        clean, problem,
        object(value) {
            const result = JSON.parse(value);
            if (!result || typeof result !== 'object' || Array.isArray(result))
                throw new Error('接口数据结构异常，预期为 JSON 对象');
            return result;
        },
        chapters(value) {
            const entries = value instanceof Map ? Array.from(value.entries()) : Object.entries(value);
            const out = [];
            const add = (key, item, group) => {
                const row = item && typeof item === 'object' && !Array.isArray(item) && !(item instanceof Map)
                    ? {...item, id: item.id === undefined ? key : item.id}
                    : {id: key, title: item};
                if (group !== undefined) row.group = String(group);
                out.push(row);
            };
            for (const [key, item] of entries) {
                if (item instanceof Map) for (const [id, title] of item) add(String(id), title, key);
                else if (Array.isArray(item)) for (const chapter of item) add(null, chapter, key);
                else if (item && typeof item === 'object' && !('id' in item) && !('title' in item))
                    for (const [id, title] of Object.entries(item)) add(String(id), title, key);
                else add(String(key), item);
            }
            return out;
        },
        install(src, sourceKey) {
            if (sourceKey === 'wnacg' && src.comic) src.comic.loadInfo = id => cialloWnacgInfo.call(src, id);
            if (sourceKey !== 'jm' || typeof src.get !== 'function' || typeof src.convertData !== 'function') return;
            src.get = url => cialloJmGet.call(src, url);
            const type = Object.getPrototypeOf(src).constructor;
            Object.defineProperty(src, 'baseUrl', {get() {
                const setting = String(src.loadSetting('apiDomain'));
                if (src.__cialloApiOrigin && src.__cialloApiSelection === setting) return src.__cialloApiOrigin;
                const domains = type.apiDomains && type.apiDomains.length ? type.apiDomains : type.fallbackServers;
                if (!domains || !domains.length) throw new Error('源站未提供 API 线路，请更新书源');
                let index = parseInt(setting) - 1;
                if (!Number.isFinite(index) || index < 0 || index >= domains.length) index = 0;
                return 'https://' + domains[index];
            }});
        }
    });
})();
