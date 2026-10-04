export class SnapshotState {
  constructor() { this.current=null; this.retired=new Set(); }
  accept(next) {
    if(!next || typeof next.epoch!=='string' || !Number.isSafeInteger(next.revision) || this.retired.has(next.epoch)) return false;
    if(this.current?.epoch===next.epoch && next.revision<=this.current.revision) return false;
    if(this.current && this.current.epoch!==next.epoch) { this.retired.add(this.current.epoch); if(this.retired.size>8) this.retired.delete(this.retired.values().next().value); }
    this.current=next; return true;
  }
}
export const resourceKey=item=>item.kind+':'+item.id;
export function visibleResources(resources,type='ALL',query='',withdrawn=false) {
  const search=query.trim().toUpperCase();
  return resources.filter(item=>{
    const d=item.data; if(!withdrawn && item.kind!=='STATION' && item.kind!=='IGNORED' && d.alive===false) return false;
    const types=new Set([item.kind==='CALLSIGN'?'CALLSIGN':item.kind==='STATION'?'STATION':'OBJECT',d.type,...(d.types||[])]);
    return (type==='ALL'||(type==='IGNORED'&&item.kind==='IGNORED')||types.has(type)||(type==='TRACKED'&&d.tracked)) && (!search||[item.id,d.name,d.comment,d.status,...(item.kind==='CALLSIGN'?[d.country,d.state,d.license]:[])].some(v=>String(v||'').toUpperCase().includes(search)));
  });
}
export const reconcileSelection=(selected,resources)=>resources.some(item=>resourceKey(item)===selected)?selected:null;
export function markerChanges(previous,resources) {
  const current=new Map(resources.filter(r=>Number.isFinite(r.data.lat)&&Number.isFinite(r.data.lon)&&!r.data.stale&&r.data.alive!==false).map(r=>[resourceKey(r),r]));
  return {current,removed:[...previous.keys()].filter(key=>!current.has(key))};
}

export const callsignResources=callsigns=>(callsigns||[]).map(data=>({kind:'CALLSIGN',id:data.callsign,version:JSON.stringify(data),data}));

export const ignoredResources=(callsigns,resources)=>(callsigns||[]).map(call=>{
  const observed=(resources||[]).find(item=>item.id===call);
  const data={...(observed?.data||{}),callsign:call,ignored:true};
  return {kind:'IGNORED',id:call,version:JSON.stringify(data),data};
});
