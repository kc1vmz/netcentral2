import {showUncertainty} from './position-uncertainty.mjs';
import {connectLive, preferences} from './live-client.mjs';

const infrastructureTypes = new Set(['IGATE', 'DIGIPEATER', 'BBS', 'REPEATER', 'WEATHER', 'DSTAR']);
const objectTypes = new Set(['EOC', 'SHELTER', 'MEDICAL', 'RESOURCE']);

export function netMapResources(resources, infrastructure, objects) {
  return resources.filter(item => {
    const data = item.data || {};
    if (!Number.isFinite(data.lat) || !Number.isFinite(data.lon)
        || Math.abs(data.lat) > 90 || Math.abs(data.lon) > 180) return false;
    if (item.kind === 'STATION')
      return infrastructure && !data.stale && (data.types || []).some(type => infrastructureTypes.has(type));
    return objects && ['OBJECT', 'OBSERVED_OBJECT'].includes(item.kind)
      && data.alive === true && objectTypes.has(data.type);
  });
}

export function addNetMapLayers(map, netId) {
  const prefs = preferences('netcentral.monitor.layers.' + netId);
  const settings = prefs.read({infrastructure: false, objects: false});
  let resources = [];
  const markers = new Map();
  map.createPane('net-context');
  map.getPane('net-context').style.zIndex = 350;
  const control = L.control({position: 'bottomright'});
  const buttons = {};
  function render() {
    const wanted = new Set();
    for (const item of netMapResources(resources, settings.infrastructure, settings.objects)) {
      const key = item.kind + ':' + item.id;
      wanted.add(key);
      let marker = markers.get(key);
      if (!marker) {
        marker = L.circleMarker([item.data.lat, item.data.lon], {
          pane: 'net-context', radius: 6, color: item.kind === 'STATION' ? '#7551a8' : '#b56518',
          fillOpacity: 0.8
        }).addTo(map);
        markers.set(key, marker);
      }
      marker.setLatLng([item.data.lat, item.data.lon]);showUncertainty(marker,map,item.data);
      const text = document.createElement('span');
      text.textContent = item.id + ' — ' + (item.data.type || (item.data.types || []).join(', '));
      if(item.data.approximate) text.textContent += ' (Approximate location)';marker.bindTooltip(text);
    }
    for (const [key, marker] of markers) if (!wanted.has(key)) {map.removeLayer(marker);markers.delete(key);}
    for (const [key, button] of Object.entries(buttons)) {
      button.setAttribute('aria-pressed', String(!!settings[key]));
      button.textContent = (key === 'infrastructure' ? 'Infrastructure' : 'Resource objects') + (settings[key] ? ': On' : ': Off');
    }
  }
  const status = document.createElement('span');
  status.className = 'net-map-layer-status';status.setAttribute('role', 'status');
  control.onAdd = () => {
    const container = L.DomUtil.create('div', 'net-map-layer-controls');
    container.setAttribute('role', 'group');container.setAttribute('aria-label', 'Map layers');
    for (const key of ['infrastructure', 'objects']) {
      const button = document.createElement('button');button.type = 'button';buttons[key] = button;
      button.addEventListener('click', () => {settings[key] = !settings[key];prefs.write(settings);render();});
      container.append(button);
    }
    container.append(status);
    L.DomEvent.disableClickPropagation(container);L.DomEvent.disableScrollPropagation(container);
    return container;
  };
  control.addTo(map);render();
  const feed = connectLive(snapshot => {
    resources = snapshot.resources || [];render();
    status.title = snapshot.limited ? 'The resource limit has been reached; some resources may not be shown.' : '';
  }, () => {resources = [];render();}, status);
  map.on('unload', () => feed.stop());
}
