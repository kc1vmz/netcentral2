import test from 'node:test';
import assert from 'node:assert/strict';
import {showUncertainty} from '../src/main/resources/static/js/position-uncertainty.mjs';
test('uncertainty follows movement, exact updates, and marker removal',()=>{
 const layers=new Set();
 const map={removeLayer:layer=>layers.delete(layer)};
 globalThis.L={circle:(position,options)=>({position,...options,addTo(){layers.add(this);return this;},setLatLng(p){this.position=p;return this;},setRadius(r){this.radius=r;return this;}})};
 const marker={position:[42,-71],getLatLng(){return this.position;},on(event,callback){this.remove=callback;}};
 showUncertainty(marker,map,{uncertaintyMeters:1300});assert.equal(layers.size,1);
 marker.position=[43,-72];showUncertainty(marker,map,{uncertaintyMeters:130});assert.deepEqual(marker.uncertaintyCircle.position,[43,-72]);assert.equal(marker.uncertaintyCircle.radius,130);
 showUncertainty(marker,map,{uncertaintyMeters:0});assert.equal(layers.size,0);
 showUncertainty(marker,map,{uncertaintyMeters:1300});marker.remove();assert.equal(layers.size,0);
 delete globalThis.L;
});
