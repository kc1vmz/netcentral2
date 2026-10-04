const test=require('node:test');
const assert=require('node:assert/strict');
const rows=require('../src/main/resources/static/js/net-participants.js');
test('expected rows exclude every previously participating base callsign',()=>{
 const actual=[{callsign:'N1ABC-7',endTime:null},{callsign:'N2ABC-8',endTime:'2026-10-01T00:00:00Z'}];
 const result=rows(actual,['N1ABC','N2ABC','N3ABC','N3ABC-2']);
 assert.deepEqual(result,[...actual,{callsign:'N3ABC',status:'Expected',expectedOnly:true}]);
 assert.deepEqual(rows([...actual,{callsign:'N3ABC-1'}],['N3ABC']),[...actual,{callsign:'N3ABC-1'}]);
});
