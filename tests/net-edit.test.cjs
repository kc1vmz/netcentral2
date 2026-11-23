const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync(require('node:path').join(__dirname,'../src/main/resources/static/js/net-edit.js'),'utf8');

function editor(mode='CREATE', type='NOW') {
  const control = value => ({value, disabled:false, listeners:{}, addEventListener(event, fn){this.listeners[event]=fn;}});
  const choice=control(type), time=control('19:30'), date=control('2035-01-01T12:00'), zone=control('UTC');
  const ordinal=control('2'), weekday=control('7'), duration=control('2'), enabled=control('true');
  const controls=[time,date,zone,ordinal,weekday,duration,enabled];
  const group=(types,children)=>({dataset:{recurrence:types},hidden:false,querySelectorAll:()=>children});
  const groups=[group('WEEKLY MONTHLY_RELATIVE',[ordinal,weekday]),group('MONTHLY_RELATIVE',[ordinal]),
    group('WEEKLY MONTHLY_RELATIVE',[weekday]),group('ONE_TIME_ONLY',[date]),group('DAILY WEEKLY MONTHLY_DAY MONTHLY_RELATIVE',[time])];
  const fields={hidden:false,querySelectorAll:()=>controls};
  const form={dataset:{editorMode:mode,netAction:'/nets/save',scheduleAction:'/schedules/save'},elements:{type:choice},
    listeners:{},querySelector:()=>fields,querySelectorAll:()=>groups,addEventListener(event,fn){this.listeners[event]=fn;},
    requestSubmit(){this.submitted=true;}};
  const ids={'net-editor-form':form};
  for(const id of ['net-save-button','net-save-title','net-save-description','schedule-save-confirm','schedule-save-back'])ids[id]=control('');
  ids['schedule-save-dialog']={showModal(){this.open=true;},close(){this.open=false;}};
  vm.runInNewContext(script,{document:{getElementById:id=>ids[id]}});
  return {form,choice,fields,controls,time,date,zone,ordinal,weekday,duration,ids};
}
test('Now and recurrence switch actions and validation without discarding values',()=>{
  const e=editor();
  assert.equal(e.form.action,'/nets/save');
  assert.equal(e.fields.hidden,true);
  assert.ok(e.controls.every(c=>c.disabled));
  e.choice.value='MONTHLY_RELATIVE';e.choice.listeners.change();
  assert.equal(e.form.action,'/schedules/save');
  assert.equal(e.fields.hidden,false);
  assert.equal(e.date.disabled,true);
  assert.equal(e.time.disabled,false);
  assert.equal(e.ordinal.disabled,false);
  e.form.listeners.submit({preventDefault(){}});
  assert.equal(e.ids['net-save-title'].textContent,'Create scheduled net?');
  e.choice.value='NOW';e.choice.listeners.change();
  assert.equal(e.zone.value,'UTC');assert.equal(e.time.value,'19:30');assert.equal(e.duration.value,'2');
  assert.ok(e.controls.every(c=>c.disabled));
  e.form.listeners.submit({preventDefault(){}});
  assert.equal(e.ids['net-save-title'].textContent,'Create net now?');
  e.ids['schedule-save-confirm'].listeners.click();
  assert.equal(e.form.submitted,true);
});
test('edit confirmations distinguish running net from future schedule occurrences',()=>{
  for(const [mode,type,action,text] of [['NET','NOW','/nets/save','this running net'],['SCHEDULE','WEEKLY','/schedules/save','future occurrences']]){
    const e=editor(mode,type);assert.equal(e.form.action,action);
    e.form.listeners.submit({preventDefault(){}});
    assert.ok(e.ids['net-save-description'].textContent.includes(text));
    assert.equal(e.ids['net-save-button'].textContent,'Update');
  }
});
