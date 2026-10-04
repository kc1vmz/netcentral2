export function showUncertainty(marker, map, data) {
  const radius = Number(data.uncertaintyMeters || 0);
  if (!marker.uncertaintyHook) {
    marker.on('remove', () => {if (marker.uncertaintyCircle) map.removeLayer(marker.uncertaintyCircle);});
    marker.uncertaintyHook = true;
  }
  if (radius > 0 && Number.isFinite(radius)) {
    if (!marker.uncertaintyCircle) marker.uncertaintyCircle = L.circle(marker.getLatLng(), {radius, color:'#7551a8', weight:1, fillOpacity:.08, interactive:false}).addTo(map);
    marker.uncertaintyCircle.setLatLng(marker.getLatLng()).setRadius(radius);
  } else if (marker.uncertaintyCircle) {
    map.removeLayer(marker.uncertaintyCircle);marker.uncertaintyCircle = null;
  }
}
