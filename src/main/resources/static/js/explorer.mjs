import {showUncertainty} from './position-uncertainty.mjs';
import {label,displayValue} from './display-values.mjs';
import {connectLive,preferences,textCell,mapTiles} from './live-client.mjs';
import {resourceKey,visibleResources,reconcileSelection,markerChanges,callsignResources,ignoredResources} from './live-state.mjs';
const byId=id=>document.getElementById(id),prefs=preferences('netcentral.explorer.v2');
let settings=prefs.read({type:'ALL',query:'',withdrawn:false,selected:null,width:50,center:[39,-98],zoom:4});
if(settings.type==='NETMGR'){settings.type='ALL';settings.selected=null;prefs.write(settings);}
const requestedType=new URLSearchParams(location.search).get('type');
if(requestedType && [...byId('resource-type').options].some(option=>option.value===requestedType)){settings.type=requestedType;settings.query='';settings.selected=null;}
let resourceMode;
let snapshot,tile,markerState=new Map(),detailVersion;
const markers=new Map(),map=L.map('resource-map').setView(settings.center,settings.zoom),save=()=>prefs.write(settings);
map.on('moveend',()=>{const c=map.getCenter();settings.center=[c.lat,c.lng];settings.zoom=map.getZoom();save();});
for(const [id,key] of [['resource-type','type'],['resource-filter','query']]){
  const input=byId(id);if(input.type==='checkbox')input.checked=settings[key];else input.value=settings[key];
  input.addEventListener('input',()=>{settings[key]=input.type==='checkbox'?input.checked:input.value;settings.selected=null;detailVersion=undefined;save();render();});
}
function choose(key,focusMap=false){settings.selected=key;detailVersion=undefined;save();render();const item=markerState.get(key);if(focusMap&&item)map.panTo([item.data.lat,item.data.lon]);}
function render(){
  if(!snapshot)return;
  const callsignMode=settings.type==='CALLSIGN',ignoredMode=settings.type==='IGNORED';
  byId('resource-map').hidden=callsignMode;byId('map-count').hidden=callsignMode;byId('callsign-map-unavailable').hidden=!callsignMode;if(!callsignMode)map.invalidateSize();
  const currentResources=ignoredMode?ignoredResources(snapshot.ignoredCallsigns,snapshot.resources):callsignMode?callsignResources(snapshot.callsigns):snapshot.resources.filter(item=>item.kind==='STATION'||item.data.alive===true);
  tile=mapTiles(map,snapshot.map,tile);settings.selected=reconcileSelection(settings.selected,currentResources);save();
  const items=visibleResources(currentResources,settings.type,settings.query,false),wanted=new Set(items.map(resourceKey)),tbody=byId('resource-rows');
  if(resourceMode!==callsignMode){
    tbody.replaceChildren();const header=tbody.closest('table').querySelector('thead tr');header.replaceChildren();
    for(const title of callsignMode?['Callsign','Name','Country','State','License','Last heard']:['Callsign/name','Type','Status','Last heard']){const th=document.createElement('th');th.textContent=title;header.append(th);}
    resourceMode=callsignMode;
  }
  for(const row of [...tbody.children])if(!wanted.has(row.dataset.key))row.remove();
  const existing=new Map([...tbody.children].map(row=>[row.dataset.key,row]));
  for(const item of items){
    const key=resourceKey(item);let row=existing.get(key);
    if(!row){row=document.createElement('tr');row.dataset.key=key;const cell=textCell(row,''),button=document.createElement('button');button.type='button';button.className='row-select';button.textContent=item.id;button.addEventListener('click',()=>choose(key,true));cell.append(button);for(let i=0;i<(callsignMode?5:3);i++)textCell(row,'');tbody.append(row);}
    if(callsignMode){['name','country','state','license'].forEach((field,index)=>row.children[index+1].textContent=displayValue(field,item.data[field]));}else{
    row.children[1].textContent=[...new Set([item.data.type,...(item.data.types||[item.kind==='STATION'?'STATION':'OBJECT'])].filter(Boolean))].join(', ');
    row.children[2].textContent=item.data.stale?'Not recently heard':item.data.alive===false?'Withdrawn':item.data.status||item.data.comment||'';
    }
    row.lastChild.textContent=displayValue('lastHeard',item.data.lastHeard);
    row.classList.toggle('selected',key===settings.selected);row.children[0].firstChild.setAttribute('aria-pressed',String(key===settings.selected));
  }
  const changes=markerChanges(markerState,items);markerState=changes.current;
  for(const key of changes.removed){map.removeLayer(markers.get(key));markers.delete(key);}
  for(const [key,item] of markerState){
    let marker=markers.get(key);if(!marker){marker=L.circleMarker([item.data.lat,item.data.lon],{radius:7,fillOpacity:.85}).addTo(map);marker.on('click',()=>choose(key));markers.set(key,marker);}
    marker.setLatLng([item.data.lat,item.data.lon]);showUncertainty(marker,map,item.data);marker.setStyle({color:key===settings.selected?'#c57017':item.kind==='STATION'?'#176f87':'#5b4a93'});
    const label=document.createElement('span');label.textContent=item.id+' Â· '+(item.data.status||item.data.comment||'');if(item.data.approximate) label.textContent += ' (Approximate location)';marker.bindTooltip(label);
  }
  byId('resource-count').textContent='('+items.length+')';byId('empty-resources').hidden=items.length>0;byId('empty-resources').textContent='No resources match the current filter.';
  byId('map-count').textContent=markers.size+' current locations. Updates preserve your map position.';
  byId('resource-limit').hidden=!(ignoredMode?false:callsignMode?snapshot.callsignsLimited:snapshot.limited);byId('resource-limit').textContent='Live view is limited to '+snapshot.resourceLimit+' resources. Full records remain available through the resource APIs.';
  const selected=currentResources.find(item=>resourceKey(item)===settings.selected);
  if(!selected){if(detailVersion!==null){const h=document.createElement('h2'),p=document.createElement('p');h.textContent='Resource details';p.textContent='Select a current resource. Removed observations are cleared automatically.';byId('resource-details').replaceChildren(h,p);detailVersion=null;}}
  else if(detailVersion!==selected.version+':'+resourceKey(selected)+':'+JSON.stringify(snapshot.ignoredCallsigns)+':'+JSON.stringify(snapshot.resources.find(item=>item.kind==='STATION'&&item.id===selected.id))){
    detailVersion=selected.version+':'+resourceKey(selected)+':'+JSON.stringify(snapshot.ignoredCallsigns)+':'+JSON.stringify(snapshot.resources.find(item=>item.kind==='STATION'&&item.id===selected.id));const heading=document.createElement('h2'),dl=document.createElement('dl'),link=document.createElement('a');heading.textContent=selected.id;dl.className='resource-facts';
    const station=snapshot.resources.find(item=>item.kind==='STATION'&&item.id===selected.id);
    const isStation=selected.kind==='STATION'||callsignMode||ignoredMode;
    const tracked=Boolean(station?.data.tracked??selected.data.tracked);
    const ignoreRules=(snapshot.ignoredCallsigns||[]).filter(call=>call===selected.id||call===selected.id.split('-')[0]);
    const fields={...selected.data,lastHeard:selected.data.lastHeard??null,tracked,ignored:ignoreRules.length>0};
    if('lon' in fields||'lat' in fields){
      fields.location=displayValue('lon',fields.lon)+', '+displayValue('lat',fields.lat);
      delete fields.lon;delete fields.lat;
    }
    if(!callsignMode){
      fields.symbol=fields.symbol?((fields.symbolDescription||'Unknown APRS symbol')+' ('+fields.symbol+')'):(fields.symbolDescription||'Not specified');
      delete fields.symbolDescription;
    }
    for(const [key,value] of Object.entries(fields)){if(key==='id'||key==='keyValues'||(value==null&&!callsignMode&&key!=='lastHeard'))continue;const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=key==='tracked'?'Tracked station?':label(key);dd.textContent=displayValue(key,value);if(key==='rawLatitude'||key==='rawLongitude')dd.style.whiteSpace='pre-wrap';dl.append(dt,dd);}
    link.href=(selected.kind==='STATION'?'/stations/':'/objects/')+encodeURIComponent(selected.id);link.textContent='View details';link.className='button-link';byId('resource-details').replaceChildren(...((callsignMode||ignoredMode)?[heading,dl]:[heading,dl,link]));
    const actions=document.createElement('div');actions.className='account-actions';
    if(!callsignMode&&!ignoredMode)actions.append(link);
    if(callsignMode){
      const identify=document.createElement('button');identify.type='button';identify.textContent='Identify';
      identify.addEventListener('click',()=>askResourceAction({kind:'identify',call:selected.id,title:'Identify',
        description:'Do you wish to identify this callsign?'}));actions.append(identify);
    }
    if(isStation&&snapshot.administrator){
      const track=document.createElement('button');track.type='button';track.textContent=tracked?'Stop tracking':'Start tracking';
      track.addEventListener('click',()=>askResourceAction({kind:'tracking',call:selected.id,value:!tracked,title:track.textContent,
        description:track.textContent+' '+selected.id+'?'}));actions.append(track);
      const ignore=document.createElement('button');ignore.type='button';ignore.textContent=ignoreRules.length?'Stop ignoring':'Ignore';
      ignore.addEventListener('click',()=>askResourceAction({kind:'ignore',call:selected.id,value:!ignoreRules.length,rules:ignoreRules,title:ignore.textContent,
        description:ignoreRules.length?'Stop ignoring '+selected.id+'?':'Ignore '+selected.id+'? Incoming packets from this callsign'+(selected.id.includes('-')?'':' and its SSIDs')+' will no longer be processed.'}));actions.append(ignore);
    }
    byId('resource-details').append(actions);
  }
}
const feed=connectLive(next=>{snapshot=next;render();},()=>{if(snapshot){snapshot={...snapshot,resources:[],callsigns:[],ignoredCallsigns:[],remoteNets:[]};render();}},byId('live-status'));
render();


let pendingAction=null,changingResource=false;
function askResourceAction(action){
  pendingAction=action;byId('resource-action-title').textContent=action.title;
  byId('stop-ignoring-description').textContent=action.description;byId('stop-ignoring-apply').textContent=action.title;
  byId('stop-ignoring-error').textContent='';byId('stop-ignoring-dialog').showModal();
}
async function resourceRequest(url,method='GET',body){
  const headers={'Content-Type':'application/json'};
  if(method!=='GET'){
    const token=await fetch('/api/v1/csrf',{cache:'no-store'});if(!token.ok||token.redirected)throw new Error('Sign in again.');
    const csrf=await token.json();headers[csrf.headerName]=csrf.token;
  }
  const response=await fetch(url,{method,headers,cache:'no-store',...(body===undefined?{}:{body:JSON.stringify(body)})});
  if(!response.ok||response.redirected){const error=new Error('Unable to update resource. Refresh and try again; administrator access is required.');error.status=response.status;throw error;}
  const text=await response.text();return text?JSON.parse(text):null;
}
byId('stop-ignoring-cancel').addEventListener('click',()=>byId('stop-ignoring-dialog').close());
byId('stop-ignoring-dialog').addEventListener('cancel',event=>{if(changingResource)event.preventDefault();});
byId('stop-ignoring-apply').addEventListener('click',async()=>{
  if(changingResource||!pendingAction)return;
  changingResource=true;const action=pendingAction;
  const apply=byId('stop-ignoring-apply'),cancel=byId('stop-ignoring-cancel');apply.disabled=cancel.disabled=true;
  try{
    if(action.kind==='identify'){
      const result=await resourceRequest('/api/v1/callsigns/'+encodeURIComponent(action.call)+'/identityRequests','POST');
      if(!result?.success)throw new Error(result?.status==='ACCOUNT_CALLSIGN_REQUIRED'
        ?'Set your callsign in My account before identifying a callsign.'
        :'No communication instance is available. Activate a connection and enable transmission before identifying a callsign.');
      byId('stop-ignoring-dialog').close();return;
    }else if(action.kind==='tracking'){
      const url='/api/v1/trackedStations/'+encodeURIComponent(action.call);
      let station;
      try{station=await resourceRequest(url);}catch(error){if(error.status!==404)throw error;}
      if(station)await resourceRequest(url+'?version='+station.version,'PUT',{tracked:action.value});
      else if(action.value)await resourceRequest('/api/v1/trackedStations','POST',{callsign:action.call});
    }else if(action.value){
      await resourceRequest('/api/v1/ignoreStations','POST',{callsign:action.call});
    }else{
      for(const call of action.rules)await resourceRequest('/api/v1/ignoreStations/'+encodeURIComponent(call),'DELETE');
    }
    snapshot=await resourceRequest('/api/v1/live/snapshot');detailVersion=undefined;render();byId('stop-ignoring-dialog').close();
  }catch(error){byId('stop-ignoring-error').textContent=error.message;}
  finally{changingResource=false;apply.disabled=cancel.disabled=false;}
});
