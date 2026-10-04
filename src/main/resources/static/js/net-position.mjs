import {mapTiles} from './live-client.mjs';
const panel=document.getElementById('net-position-map'),lat=document.getElementById('net-lat'),lon=document.getElementById('net-lon');
const form=lat.form,selection=document.getElementById('net-selected-instances');
function routingVisibility(){if(selection)selection.hidden=form.elements.communicationMode.value!=='SELECTED';}
form.elements.communicationMode?.addEventListener('change',routingVisibility);routingVisibility();
const cancel=document.getElementById('net-cancel-dialog');
document.getElementById('net-cancel')?.addEventListener('click',()=>{cancel.showModal();document.getElementById('net-cancel-back').focus();});
document.getElementById('net-cancel-back')?.addEventListener('click',()=>cancel.close());
document.getElementById('net-cancel-confirm')?.addEventListener('click',event=>location.assign(event.currentTarget.dataset.returnUrl));
const manualButton=document.getElementById('location-manual'),mapButton=document.getElementById('location-map');
let map;
function locationMode(useMap){
 document.getElementById('location-coordinates').hidden=useMap;
 document.getElementById('location-map-panel').hidden=!useMap;
 manualButton.setAttribute('aria-pressed',String(!useMap));mapButton.setAttribute('aria-pressed',String(useMap));
 if(useMap){map?.invalidateSize();move(true);}else lat.focus();
}
manualButton.addEventListener('click',()=>locationMode(false));mapButton.addEventListener('click',()=>locationMode(true));
for(const input of [lat,lon])input.addEventListener('invalid',()=>locationMode(false));
map=L.map(panel).setView([39,-98],4);
mapTiles(map,{tileUrl:panel.dataset.tileUrl,attribution:panel.dataset.attribution});
let marker;
function valid(){return lat.value!==''&&lon.value!==''&&lat.validity.valid&&lon.validity.valid;}
function move(center=false){document.getElementById('location-summary').textContent=valid()?'Latitude: '+lat.value+' · Longitude: '+lon.value:'Select a location on the map.';if(!valid()){if(marker){map.removeLayer(marker);marker=null;}return;}
 const point=[Number(lat.value),Number(lon.value)];
 if(!marker){marker=L.marker(point,{draggable:true}).addTo(map);marker.on('dragend',()=>select(marker.getLatLng()));}else marker.setLatLng(point);
 if(center)map.setView(point,Math.max(map.getZoom(),10));
}
function select(point){lat.value=point.lat.toFixed(6);lon.value=(((point.lng+180)%360+360)%360-180).toFixed(6);move();}
map.on('click',event=>select(event.latlng));
for(const input of [lat,lon])input.addEventListener('input',()=>move(true));
move(true);
