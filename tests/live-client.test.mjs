import test from 'node:test';
import assert from 'node:assert/strict';
import {connectLive} from '../src/main/resources/static/js/live-client.mjs';

test('menu pause closes delivery, ignores old events and resumes with current state',()=>{
  const listeners=new Map(),sources=[];globalThis.window={addEventListener:(name,fn)=>listeners.set(name,fn)};
  globalThis.sessionStorage={getItem:()=>null};
  globalThis.EventSource=class{constructor(){this.listeners=new Map();sources.push(this);}addEventListener(name,fn){this.listeners.set(name,fn);}close(){this.closed=true;}};
  const rendered=[],status={};const feed=connectLive(value=>rendered.push(value.revision),()=>{},status);
  const event=(source,revision)=>source.listeners.get('snapshot')({data:JSON.stringify({epoch:'one',revision})});
  event(sources[0],1);listeners.get('netcentral:updates')({detail:{paused:true}});event(sources[0],2);
  assert.equal(sources[0].closed,true);assert.equal(status.textContent,'Paused');assert.deepEqual(rendered,[1]);
  listeners.get('netcentral:updates')({detail:{paused:false}});event(sources[1],3);assert.deepEqual(rendered,[1,3]);feed.stop();
});

test('navigation while paused loads one snapshot without starting a live connection',async()=>{
  globalThis.window={addEventListener(){}};globalThis.sessionStorage={getItem:()=> 'true'};
  globalThis.EventSource=class{constructor(){assert.fail('Paused pages must not connect');}};
  globalThis.fetch=async()=>({ok:true,status:200,json:async()=>({epoch:'two',revision:1})});
  const rendered=[],status={};const feed=connectLive(value=>rendered.push(value),()=>{},status);
  await new Promise(resolve=>setImmediate(resolve));assert.equal(rendered.length,1);assert.equal(status.textContent,'Paused');feed.stop();
});
