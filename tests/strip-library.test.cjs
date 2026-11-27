const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
test('create errors stay in editor and retry creates without a second dialog',async()=>{
 const nodes=new Map();
 function element(){return {textContent:'',children:[],open:false,append(...v){this.children.push(...v)},replaceChildren(){this.children=[]},addEventListener(){},showModal(){this.open=true},close(){this.open=false}};}
 const get=id=>{if(!nodes.has(id))nodes.set(id,element());return nodes.get(id)};
 const form=get('strip-template-form');form.elements={name:{value:''},definition:{value:''}};form.reset=()=>{};
 let posts=0;
 const context={document:{getElementById:get,createElement:element,querySelector:()=>({content:'csrf'}),querySelectorAll:()=>[]},fetch:async(url,options)=>{
  if(options.method==='GET')return {ok:true,json:async()=>({templates:[],canManage:true})};
  posts++;return posts===1?{ok:false,json:async()=>({message:'Use Label(MASK) for each strip field'})}:{ok:true,json:async()=>({templates:[{id:'1',name:'test1',definition:'foo/bar(X)/test(X)//',version:0}],canManage:true})};
 }};
 vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/strip-library.js','utf8'),context);
 await new Promise(resolve=>setImmediate(resolve));
 get('strip-template-new').onclick();form.elements.name.value='test1';form.elements.definition.value='foo/bar/test//';
 await form.onsubmit({preventDefault(){}});
 assert.equal(get('strip-template-editor').open,true);assert.equal(get('strip-confirm').open,false);
 assert.match(get('strip-editor-error').textContent,/Label/);
 form.elements.definition.value='foo/bar(X)/test(X)//';await form.onsubmit({preventDefault(){}});
 assert.equal(get('strip-template-editor').open,false);assert.equal(posts,2);
 assert.equal(get('strip-library-list').children[0].children[0].textContent,'test1');
 assert.equal(get('strip-library-list').children[0].children[1].textContent,'foo/bar(X)/test(X)//');
});
