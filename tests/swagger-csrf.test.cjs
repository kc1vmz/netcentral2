const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const source=fs.readFileSync('src/main/java/com/kc1vmz/netcentral/config/SwaggerCsrfTransformer.java','utf8');
const functionText=source.slice(source.indexOf('async function(request)'),source.indexOf('\n            },')+14);
test('Swagger mutations use fresh session CSRF tokens only for same-origin API requests',async()=>{
 let calls=0;
 const interceptor=vm.runInNewContext('('+functionText+')',{URL,window:{location:{href:'http://localhost:8080/nc/swagger-ui/index.html',origin:'http://localhost:8080',pathname:'/nc/swagger-ui/index.html'}},fetch:async url=>{assert.equal(url,'/nc/api/v1/csrf');calls++;return {ok:true,json:async()=>({headerName:'X-CSRF-TOKEN',token:'token'+calls})}}});
 for(const method of ['POST','PUT','DELETE']){const result=await interceptor({url:'http://localhost:8080/nc/api/v1/nets',method});assert.equal(result.headers['X-CSRF-TOKEN'],'token'+calls);assert.equal(result.credentials,'same-origin');}
 for(const url of ['http://elsewhere.test/nc/api/v1/nets','http://localhost:8080/other']){const result=await interceptor({url,method:'POST'});assert.equal(result.headers,undefined);}
 await interceptor({url:'http://localhost:8080/nc/api/v1/nets',method:'GET'});assert.equal(calls,3);
});
