const fs = require('node:fs');
const {host, closeTransport} = require('./discover-live-audit.cjs');
const h = host({fileName:'hitomi.js'});
(async () => {
  const report = {};
  try {
    if (process.env.HITOMI_COLD_ONLY === '1') {
      Object.assign(report, JSON.parse(fs.readFileSync('artifacts/discover-rework3-2026-10-14/hitomi-live.json','utf8')));
      report.auditHarnessNote = 'The earlier combined cold test exceeded the 128-request host budget; this probe uses a fresh host.';
      const first = await h.run('__cialloDiscover(src,{dimension:"updates",range:"最新",categoryId:"category:0:0",page:1,offset:0})');
      const cold = await h.run('src.categoryResultCache=undefined; src.__cialloHitomiCategoryKey=undefined; __cialloDiscover(src,{dimension:"updates",range:"最新",categoryId:"category:0:0",page:2,offset:25})');
      report.coldCategoryPage2 = {count:cold.entries.length,newIds:cold.entries.filter(e=>!first.entries.some(a=>a.id===e.id)).length};
      if (report.coldCategoryPage2.newIds !== 25) throw Error('Cold category pagination failed');
      delete report.error;
      return;
    }
    report.catalog = await h.run('__cialloDiscoverCatalog(src)');
    const page = await h.run('__cialloDiscover(src,{dimension:"updates",range:"最新",page:1,offset:0})');
    report.count = page.entries.length;
    report.entries = page.entries.slice(0,2);
    report.hasMore = page.hasMore;
    if (!page.entries.length || !page.hasMore || !page.entries[0].updatedAt) throw Error('Updates, dates and pagination must all be present');
    const second = await h.run('__cialloDiscover(src,{dimension:"updates",range:"最新",page:2,offset:25})');
    report.secondPage = {count:second.entries.length,newIds:second.entries.filter(e=>!page.entries.some(a=>a.id===e.id)).length};
    if (report.secondPage.newIds !== 25) throw Error('Page 2 is not a distinct complete page');
    const ranking = await h.run('__cialloDiscover(src,{dimension:"ranking",range:"热门",feedId:"ranking:week",page:1,offset:0})');
    report.weekRanking = {count:ranking.entries.length,origin:ranking.entries[0]?.origin,rank:ranking.entries[0]?.rank};
    const category = await h.run('__cialloDiscover(src,{dimension:"updates",range:"最新",categoryId:"category:0:0",page:1,offset:0})');
    report.chineseCategory = {count:category.entries.length,languages:[...new Set(category.entries.map(e=>e.language))]};
    if (!ranking.entries.length || !category.entries.length) throw Error('Ranking or native classification failed');
    const coldHost = host({fileName:'hitomi.js'});
    const cold = await coldHost.run('__cialloDiscover(src,{dimension:"updates",range:"最新",categoryId:"category:0:0",page:2,offset:25})');
    report.coldRequests = coldHost.evidence; coldHost.close();
    report.coldCategoryPage2 = {count:cold.entries.length,newIds:cold.entries.filter(e=>!category.entries.some(a=>a.id===e.id)).length};
    if (report.coldCategoryPage2.newIds !== 25) throw Error('Restored category context did not resume the requested page');
  } catch (e) { report.error = String(e.stack); process.exitCode = 1; }
  finally {
    report.requests = h.evidence;
    fs.writeFileSync('artifacts/discover-rework3-2026-10-14/hitomi-live.json', JSON.stringify(report,null,2));
    console.log(JSON.stringify({...report,entries:undefined,requests:report.requests.length})); h.close(); closeTransport();
  }
})();
