const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
class Element {
  constructor(tag){this.tagName=tag;this.children=[];this.dataset={};this.value='';this.events={};}
  append(...children){this.children.push(...children);}
  replaceChildren(...children){this.children=children;}
  add(option){this.children.push(option);}
  addEventListener(name,callback){this.events[name]=callback;}
}
function setup(){const nodes=new Map();const document={createElement:tag=>new Element(tag),getElementById:id=>{if(!nodes.has(id))nodes.set(id,new Element('div'));return nodes.get(id);}};
 const context=vm.createContext({document,Option:function(text,value){this.textContent=text;this.value=value;},Date});
 vm.runInContext(fs.readFileSync('src/main/resources/static/js/strip-ui.js','utf8'),context);
 return {ui:context.StripUi,document};}
const q={id:'id',strip:true,identifier:'S1',question:'BEDS/Facility(X)/Available(NN)//',fields:[{label:'Facility',mask:'X'},{label:'Available',mask:'NN'}]};
test('strip answer editor preserves blank fields and exact slash response',()=>{const {ui,document}=setup();const form={elements:{answer:{}}};ui.editor(form,q);
 const fields=document.getElementById('strip-answer-fields');assert.equal(form.elements.answer.value,' / //');
 fields.children[1].children[0].value='25';fields.children[1].children[0].events.input();assert.equal(form.elements.answer.value,' /25//');
 assert.equal(form.elements.answer.required,false);
 ui.editor(form,{id:'normal',strip:false});assert.equal(form.elements.answer.required,true);assert.equal(document.getElementById('answer-text-label').hidden,false);
});
test('strip results show masks as headers, operator and untouched response',()=>{const {ui}=setup();const container=new Element('div');ui.answers(container,q,[{callsign:'N1ABC',answer:'Lincoln/ //',answeredAt:'2026-11-27T15:00:00Z'}]);
 assert.equal(container.children[0].textContent,'S1 [BEDS]');
 const table=container.children.find(n=>n.tagName==='table');assert.deepEqual(table.children[0].children[0].children.map(n=>n.textContent),['Operator','Facility','Available','Answered','Response strip']);
 const row=table.children[1].children[0].children.map(n=>n.textContent);assert.equal(row[2],'(blank)');assert.equal(row[4],'Lincoln/ //');assert.match(ui.label(q),/^S1 \[BEDS\]/);
});
