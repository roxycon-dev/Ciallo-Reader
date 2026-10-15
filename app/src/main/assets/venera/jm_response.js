// Retry only read-only API GETs. A route is accepted only after envelope/decryption validation.
async function cialloJmGet(url) {
    const current = this.baseUrl;
    const local = url.startsWith(current + '/');
    const path = local ? url.slice(current.length) : '';
    const sourceType = Object.getPrototypeOf(this).constructor;
    const routes = local ? Array.from(new Set([current,
        ...(sourceType.apiDomains || []).map(domain => 'https://' + domain),
        ...(sourceType.fallbackServers || []).map(domain => 'https://' + domain)])).slice(0, 5) : [''];
    let lastError;
    const started = Date.now();
    for (const route of routes) {
        if (lastError && Date.now() - started > 18000) break;
        const time = Math.floor(Date.now() / 1000);
        try {
            const response = await Network.get(local ? route + path : url, this.getApiHeaders(time));
            if (response.status === 401 || response.status === 403) {
                let message = '';
                try { message = CialloSourceResponse.object(response.body).errorMsg || ''; } catch (_) {}
                if (response.status === 401 || message) {
                    const error = new Error(message === '請先登入會員' && this.isLogged ? 'Login expired' : message || '该源需要登录：请在书源管理中登录');
                    error.noRetry = true;
                    throw error;
                }
            }
            if (response.status !== 200) throw new Error('源站返回 HTTP ' + response.status);
            const envelope = CialloSourceResponse.object(response.body);
            if (envelope.code === 401) {
                const error = new Error('Login expired');
                error.noRetry = true;
                throw error;
            }
            if (typeof envelope.data !== 'string' || !envelope.data.trim())
                throw new Error('源站接口未返回有效数据，请稍后重试');
            const decoded = this.convertData(envelope.data, String(time) + '185Hcomic3PAPP7R');
            const parsed = JSON.parse(decoded);
            if (!parsed || typeof parsed !== 'object') throw new Error('源站接口数据结构异常，请稍后重试');
            const endpoint = path.split('?')[0];
            if (endpoint === '/album') {
                if (typeof parsed.name !== 'string' || !parsed.name.trim() ||
                    (parsed.series != null && (!Array.isArray(parsed.series) || parsed.series.some(item => !item || item.id == null))))
                    throw new Error('源站未返回完整作品详情，请稍后重试');
                // Recommendations are optional; absent/invalid recommendations must not block reading.
                parsed.related_list = Array.isArray(parsed.related_list)
                    ? parsed.related_list.filter(item => item && item.id != null && typeof item.name === 'string') : [];
            }
            if (endpoint === '/search' && (!Array.isArray(parsed.content) || parsed.content.some(item => !item || item.id == null)))
                throw new Error('源站未返回有效搜索列表，请稍后重试');
            if (endpoint === '/chapter' && (!Array.isArray(parsed.images) || !parsed.images.length || parsed.images.some(item => typeof item !== 'string' || !item.trim())))
                throw new Error('源站未返回完整章节图片，请稍后重试');
            if (local) {
                this.__cialloApiOrigin = route;
                this.__cialloApiSelection = String(this.loadSetting('apiDomain'));
                if (typeof this.saveData === 'function') this.saveData('jm_read_route', JSON.stringify({
                    domain: route.slice(8), selection: this.__cialloApiSelection
                }));
            }
            return endpoint === '/album' ? JSON.stringify(parsed) : decoded;
        } catch (error) {
            if (error && error.noRetry) throw error;
            if (/cancel|执行超过|请求已取消/i.test(String(error))) throw error;
            lastError = error;
        }
    }
    throw lastError || new Error('源站所有线路暂不可用，请稍后重试');
}
