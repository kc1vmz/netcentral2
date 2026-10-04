import {SnapshotState} from './live-state.mjs';
export function connectLive(render,clear,status,endpoint='/api/v1/live') {
  const state=new SnapshotState(); let source,timer,generation=0,paused=false,stopped=false;
  try{paused=sessionStorage.getItem('netcentral.updatesPaused')==='true';}catch{}
  const say=text=>{status.textContent=text;};
  const close=()=>{generation++;source?.close();source=null;clearTimeout(timer);};
  function open() {
    if(paused||stopped) return; close();const own=generation; say('Connecting…');source=new EventSource(endpoint+'/events');
    source.addEventListener('snapshot',event=>{
      if(generation!==own) return;
      try {const next=JSON.parse(event.data);if(state.accept(next))render(next);say('Live');}
      catch {say('Update unavailable; reconnecting…');close();timer=setTimeout(open,2000);}
    });
    source.onerror=async()=>{
      if(generation!==own)return;close();const checking=generation;say('Disconnected · reconnecting…');
      try {const response=await fetch(endpoint+'/snapshot',{cache:'no-store'});if(generation!==checking)return;if(response.status===401||response.status===403){stopped=true;clear();say('Session ended. Sign in again.');return;}}
      catch {/* Offline: retain the view with an explicit disconnected status. */}
      if(generation===checking)timer=setTimeout(open,2000);
    };
  }
  window.addEventListener('netcentral:updates',event=>{paused=event.detail.paused;if(paused){close();say('Paused');}else open();});
  window.addEventListener('pagehide',close);window.addEventListener('pageshow',e=>{if(e.persisted)open();});
  if(paused){say('Paused');const own=generation;fetch(endpoint+'/snapshot',{cache:'no-store'}).then(async response=>{if(response.status===401||response.status===403){stopped=true;clear();say('Session ended. Sign in again.');return;}if(!response.ok)throw new Error();const next=await response.json();if(own===generation&&state.accept(next))render(next);}).catch(()=>say('Paused · initial view unavailable'));}else open();
  return {pause(){paused=true;close();say('Paused');},resume(){paused=false;open();},stop(){stopped=true;close();}};
}
export function preferences(key){return {read(fallback){try{return {...fallback,...JSON.parse(localStorage.getItem(key)||'{}')};}catch{return fallback;}},write(value){try{localStorage.setItem(key,JSON.stringify(value));}catch{}}};}
export function textCell(row,value){const cell=document.createElement('td');cell.textContent=value??'';row.append(cell);return cell;}
export function mapTiles(map,settings,previous){
  if(previous?.url===settings.tileUrl&&previous?.attribution===settings.attribution)return previous;
  if(previous)map.removeLayer(previous.layer);const safe=document.createElement('span');safe.textContent=settings.attribution;
  return {url:settings.tileUrl,attribution:settings.attribution,layer:L.tileLayer(settings.tileUrl,{maxZoom:19,attribution:safe.innerHTML}).addTo(map)};
}
