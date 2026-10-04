import test from 'node:test';
import assert from 'node:assert/strict';
import {SnapshotState,visibleResources,reconcileSelection,markerChanges,resourceKey,callsignResources,ignoredResources} from '../src/main/resources/static/js/live-state.mjs';
const station=(id,lat=42)=>({id,kind:'STATION',data:{lat,lon:-71,types:['STATION'],status:'Ready'}});
test('delayed snapshots cannot undo newer updates or restore a retired server epoch',()=>{const s=new SnapshotState();assert(s.accept({epoch:'a',revision:2}));assert(!s.accept({epoch:'a',revision:1}));assert(s.accept({epoch:'b',revision:1}));assert(!s.accept({epoch:'a',revision:999}));});
test('a full reconnect snapshot removes deleted and withdrawn markers',()=>{const a=station('N1ABC');const b={id:'OBJ001',kind:'OBJECT',data:{lat:43,lon:-71,alive:true}};const old=markerChanges(new Map(),[a,b]).current;const next=markerChanges(old,[{...b,data:{...b.data,alive:false}}]);assert.equal(next.current.size,0);assert.deepEqual(next.removed,[resourceKey(a),resourceKey(b)]);});
test('filters preserve selection until the selected record is actually deleted',()=>{const a=station('N1ABC'),b=station('N2ABC');assert.equal(visibleResources([a,b],'ALL','N2').length,1);assert.equal(reconcileSelection(resourceKey(a),[a,b]),resourceKey(a));assert.equal(reconcileSelection(resourceKey(a),[b]),null);});
test('moves retain marker identity and stale or invalid coordinates are excluded',()=>{const a=station('N1ABC'),old=markerChanges(new Map(),[a]).current;const next=markerChanges(old,[station('N1ABC',44)]);assert.deepEqual(next.removed,[]);assert.equal(next.current.get(resourceKey(a)).data.lat,44);assert.equal(markerChanges(next.current,[station('N1ABC',null)]).current.size,0);});
test('resource categories and withdrawn visibility are independent of arbitrary text',()=>{const object={id:'OBJ001',kind:'OBJECT',data:{alive:false,type:'SHELTER',comment:'<script>'}};assert.equal(visibleResources([object],'SHELTER','',false).length,0);assert.equal(visibleResources([object],'SHELTER','<script>',true).length,1);});

test('callsign resources search attributes, update details versions and never add map markers',()=>{
 const data={callsign:'N1ABC',name:'Operator',country:'US',state:'MA',license:'Extra'};
 const entries=callsignResources([data]);
 assert.equal(visibleResources(entries,'CALLSIGN','extra').length,1);
 assert.equal(visibleResources(entries,'STATION').length,0);
 assert.equal(markerChanges(new Map(),entries).current.size,0);
 assert.equal(reconcileSelection('CALLSIGN:N1ABC',entries),'CALLSIGN:N1ABC');
 assert.notEqual(callsignResources([{...data,name:'Changed'}])[0].version,entries[0].version);
 assert.equal(reconcileSelection('CALLSIGN:N1ABC',[]),null);
 assert.equal('id' in entries[0].data,false);
});

test('ignored resources include unheard callsigns, retain known details and disappear when unignored',()=>{
  const items=ignoredResources(['N1ABC','N2ABC'],[station('N1ABC')]);
  assert.equal(items.length,2);assert.equal(items[0].data.lat,42);assert.equal(items[1].data.ignored,true);
  assert.equal(visibleResources(items,'IGNORED','N2').length,1);
  assert.equal(reconcileSelection('IGNORED:N1ABC',ignoredResources(['N2ABC'],[])),null);
});
