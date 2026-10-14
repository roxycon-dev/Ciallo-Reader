class NativeMangaDexAudit extends ComicSource {
 key='mangadex';name='MangaDex 原生源';url='https://mangadex.org';baseUrl='https://api.mangadex.org';
 async json(route) { const r=await Network.get(this.baseUrl+route);if(r.status!==200)throw Error('MangaDex HTTP '+r.status);return JSON.parse(r.body); }
 books(data) {return (data||[]).map(m=>({id:'mdapi:'+m.id,title:m.attributes.title['zh-hans']||m.attributes.title.en||Object.values(m.attributes.title)[0],tags:(m.attributes.tags||[]).map(t=>t.attributes.name.en)}));}
 explore=[{title:'最新更新',type:'multiPageComicList',load:async page=>{
  const r=await this.json('/chapter?limit=5&offset='+((page-1)*5)+'&order[publishAt]=desc&translatedLanguage[]=zh&translatedLanguage[]=zh-hk&translatedLanguage[]=en&contentRating[]=safe&contentRating[]=suggestive');
  const ids=[...new Set(r.data.flatMap(c=>c.relationships.filter(m=>m.type==='manga').map(m=>m.id)))];
  const data=ids.length?await this.json('/manga?limit=5&'+ids.map(id=>'ids[]='+id).join('&')+'&includes[]=author&includes[]=cover_art'): {data:[]};
  return {comics:this.books(data.data),maxPage:Math.ceil(r.total/5)};
 }},{title:'热门排行',type:'multiPageComicList',load:async page=>{const r=await this.json('/manga?limit=5&offset='+((page-1)*5)+'&order[followedCount]=desc&includes[]=author&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive');return {comics:this.books(r.data),maxPage:Math.ceil(r.total/5)};}}];
}