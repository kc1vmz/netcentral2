import {test} from 'node:test';
import assert from 'node:assert/strict';
import {netMapResources} from '../src/main/resources/static/js/net-map-layers.mjs';
const resource = (id, kind, data) => ({id, kind, data:{lat:42, lon:-71, ...data}});
const stations = ['IGATE','DIGIPEATER','BBS','REPEATER','WEATHER','DSTAR'].map(type => resource(type,'STATION',{types:[type]}));
const objects = ['EOC','SHELTER','MEDICAL','RESOURCE'].map(type => resource(type,'OBSERVED_OBJECT',{type,alive:true}));
const resources = [...stations,...objects,
  resource('ordinary','STATION',{types:['STATION']}),resource('stale','STATION',{types:['IGATE'],stale:true}),
  resource('withdrawn','OBJECT',{type:'RESOURCE',alive:false}),resource('net','OBJECT',{type:'NET',alive:true}),
  resource('unknown-location','STATION',{types:['BBS'],lat:null})];
test('net map layers are independent and include every requested type', () => {
  assert.deepEqual(netMapResources(resources,false,false),[]);
  assert.deepEqual(netMapResources(resources,true,false),stations);
  assert.deepEqual(netMapResources(resources,false,true),objects);
  assert.deepEqual(netMapResources(resources,true,true),[...stations,...objects]);
});
test('updated locations and removal are reflected in each snapshot', () => {
  const moved = resource('EOC','OBJECT',{type:'EOC',alive:true,lat:43});
  assert.equal(netMapResources([moved],false,true)[0].data.lat,43);
  assert.deepEqual(netMapResources([{...moved,data:{...moved.data,alive:false}}],false,true),[]);
});
