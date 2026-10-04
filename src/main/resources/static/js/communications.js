/* Connection workflow adapted from APRS Welcome Center, Copyright 2026 John Rokicki KC1VMZ, GPL-3.0-or-later. */
(() => {
  const form=document.getElementById('communication-form'), dialog=document.getElementById('communication-dialog');
  const confirm=document.getElementById('communication-confirm'), list=document.getElementById('communication-list');
  const message=document.getElementById('communication-list-message');
  let entries=[], editing=null, pending=null, busy=false, sequence=0, updatesPaused=false;
  try{updatesPaused=sessionStorage.getItem('netcentral.updatesPaused')==='true';}catch{}
  window.addEventListener('netcentral:updates',event=>{updatesPaused=event.detail.paused;});
  async function request(url,method='GET',body){
    const headers={'Content-Type':'application/json'};
    if(method!=='GET'){
      const response=await fetch('/api/v1/csrf',{cache:'no-store'});
      if(!response.ok)throw new Error('Sign in again.');
      const csrf=await response.json(); headers[csrf.headerName]=csrf.token;
    }
    const response=await fetch(url,{method,headers,cache:'no-store',...(body===undefined?{}:{body:JSON.stringify(body)})});
    if(!response.ok||response.redirected){const detail=await response.json().catch(()=>({}));const error=new Error(detail.message||'Request failed. Sign in again or refresh the list.');error.status=response.status;throw error;}
    const text=await response.text(); return text?JSON.parse(text):null;
  }
  function fieldStates(){
    const type=form.elements.type.value;
    form.querySelectorAll('[data-types]').forEach(label=>{label.hidden=!label.dataset.types.split(' ').includes(type);label.querySelectorAll('input').forEach(input=>input.disabled=label.hidden);});
    form.elements.passcode.required=type==='APRS_IS'&&(!editing||editing.type!=='APRS_IS');
  }
  function open(config=null){
    editing=config;form.reset();document.getElementById('communication-title').textContent=config?'Edit connection':'Add connection';
    const values=config||{type:'APRS_IS',enabled:true,transmitEnabled:true,port:14580,baudRate:9600,kissPort:0};
    for(const input of form.elements)if(input.name)input.value=values[input.name]??'';
    form.elements.passcode.value='';fieldStates();document.getElementById('communication-message').textContent='';dialog.showModal();
  }
  function render(){
    list.replaceChildren();
    if(!entries.length)list.textContent='No communication connections configured.';
    for(const entry of entries){
      const c=entry.configuration,h=entry.health,row=document.createElement('section');row.className='panel';
      const title=document.createElement('h2');title.textContent=c.name;
      const status=document.createElement('p');status.textContent=`${c.type} · ${c.enabled?'ACTIVE':'PAUSED'} · ${h.state} · ${c.transmitEnabled?'Transmission allowed':'Receive only'}`;
      const health=document.createElement('p');health.textContent=`Received: ${h.received} · Sent: ${h.sent} · Failed: ${h.failed} · Discarded: ${h.discarded} · Queued: ${h.queued}`+(h.lastPacket?` · Last packet: ${new Date(h.lastPacket).toLocaleString()}`:'');
      row.append(title,status,health);
      const warnings=[h.lastError];
      if(c.host&&entries.some(other=>other.configuration.id!==c.id&&other.configuration.host===c.host&&other.configuration.port===c.port))warnings.push('Another instance uses the same TCP endpoint.');
      for(const warning of warnings.filter(Boolean)){const p=document.createElement('p');p.textContent=warning;row.append(p);}
      const actions=document.createElement('div');actions.className='account-actions';
      for(const [label,action] of [['Edit',()=>open(c)],[c.enabled?'Pause':'Resume',()=>ask(c,false)],['Delete',()=>ask(c,true)]]){const button=document.createElement('button');button.type='button';button.textContent=label;button.addEventListener('click',action);actions.append(button);}
      row.append(actions);list.append(row);
    }
    // Keep routing choices current without discarding unsaved selections.
    document.querySelectorAll('form[action$="/communications/routes"] fieldset').forEach(fieldset=>{
      fieldset.querySelectorAll('label').forEach(label=>{if(!entries.some(e=>e.configuration.id===label.querySelector('input').value))label.remove();});
      for(const {configuration:c} of entries){let input=Array.from(fieldset.querySelectorAll('input')).find(i=>i.value===c.id);if(!input){const label=document.createElement('label');input=document.createElement('input');input.type='checkbox';input.name='instanceIds';input.value=c.id;label.className='check';label.append(input,document.createElement('span'));fieldset.append(label);}input.parentElement.querySelector('span').textContent=c.name;}
    });
  }
  async function load(){const current=++sequence;const result=await request('/api/v1/communicationInstances');if(current===sequence){entries=result;render();}}
  async function reloadRoutes(){
    const routes=await request('/api/v1/communicationRoutes');
    document.querySelectorAll('form[action$="/communications/routes"]').forEach(routeForm=>{
      const route=routes.find(r=>r.owner===routeForm.elements.owner.value);if(!route)return;
      routeForm.elements.version.value=route.version;routeForm.elements.mode.value=route.mode;
      if(route.owner==='DEFAULT')routeForm.querySelector('fieldset').hidden=route.mode!=='SELECTED';
      routeForm.querySelectorAll('[name="instanceIds"]').forEach(input=>input.checked=route.instanceIds.includes(input.value));
    });
    message.textContent='Connection deleted. Routing controls show the saved selections.';
  }
  async function failure(error,target){target.textContent=error.message;if([404,409].includes(error.status)){target.textContent+=' Refresh and reopen the connection before retrying.';await load().catch(()=>{});}}
  function ask(config,remove){pending={config,remove};const action=remove?'Delete':config.enabled?'Pause':'Resume';document.getElementById('communication-confirm-title').textContent=action+' connection';document.getElementById('communication-confirm-description').textContent=`${action} ${config.name}?`+(remove?' It will be removed from selected routes. Routing controls will reload their saved selections; an empty selection remains empty.':'');document.getElementById('communication-confirm-error').textContent='';confirm.showModal();}
  document.getElementById('confirm-communication-change').addEventListener('click',async()=>{
    if(busy||!pending)return;busy=true;confirm.querySelectorAll('button').forEach(b=>b.disabled=true);
    try{const {config:c,remove}=pending;await request('/api/v1/communicationInstances/'+encodeURIComponent(c.id)+(remove?'?version='+c.version:''),remove?'DELETE':'PUT',remove?undefined:{...c,enabled:!c.enabled});confirm.close();pending=null;await load();if(remove)await reloadRoutes();}
    catch(error){await failure(error,document.getElementById('communication-confirm-error'));if([404,409].includes(error.status))pending=null;}
    finally{busy=false;confirm.querySelectorAll('button').forEach(b=>b.disabled=false);}
  });
  form.addEventListener('submit',async event=>{
    event.preventDefault();if(busy)return;busy=true;const save=form.querySelector('[type="submit"]');save.disabled=true;
    const value={version:editing?.version??0,kissPort:0};
    for(const [key,text] of new FormData(form))value[key]=['port','baudRate','kissPort'].includes(key)?Number(text):['enabled','transmitEnabled'].includes(key)?text==='true':text.trim();
    try{await request('/api/v1/communicationInstances'+(editing?'/'+encodeURIComponent(editing.id):''),editing?'PUT':'POST',value);dialog.close();await load();}
    catch(error){await failure(error,document.getElementById('communication-message'));}
    finally{busy=false;save.disabled=false;}
  });
  form.elements.type.addEventListener('change',()=>{const port=form.elements.port;if(!port.value||['14580','8001'].includes(port.value))port.value=form.elements.type.value==='APRS_IS'?14580:8001;fieldStates();});
  const routeDialog=document.getElementById('default-route-dialog'), routeConfirm=document.getElementById('default-route-confirm');
  const routeForm=routeDialog.querySelector('form');
  routeForm.elements.mode.addEventListener('change',()=>{routeForm.querySelector('fieldset').hidden=routeForm.elements.mode.value!=='SELECTED';});
  document.getElementById('default-route-open').addEventListener('click',async()=>{
    if(busy)return;busy=true;
    try{await load();await reloadRoutes();message.textContent='';routeForm.dataset.confirmed='';routeDialog.showModal();}
    catch(error){await failure(error,message);}finally{busy=false;}
  });
  document.getElementById('default-route-cancel').addEventListener('click',()=>routeDialog.close());
  routeForm.addEventListener('submit',event=>{
    if(routeForm.dataset.confirmed==='true')return;
    event.preventDefault();
    const selected=[...routeForm.querySelectorAll('[name="instanceIds"]:checked')].map(input=>input.parentElement.querySelector('span').textContent);
    document.getElementById('default-route-summary').textContent=routeForm.elements.mode.value==='ALL'
      ?'Use all eligible communication instances, including future instances?'
      :'Use only these communication instances: '+(selected.join(', ')||'None (no default transmissions)')+'?';
    routeConfirm.showModal();
  });
  document.getElementById('default-route-back').addEventListener('click',()=>routeConfirm.close());
  document.getElementById('default-route-apply').addEventListener('click',()=>{
    routeForm.dataset.confirmed='true';document.getElementById('default-route-apply').disabled=true;routeForm.requestSubmit();
  });
  document.getElementById('add-communication').addEventListener('click',()=>open());
  document.getElementById('refresh-communications').addEventListener('click',()=>load().catch(e=>failure(e,message)));
  document.getElementById('cancel-communication').addEventListener('click',()=>{if(!busy)dialog.close();});
  document.getElementById('cancel-communication-change').addEventListener('click',()=>{if(!busy)confirm.close();});
  for(const d of [dialog,confirm])d.addEventListener('cancel',e=>{if(busy)e.preventDefault();});
  load().catch(e=>failure(e,message));
  setInterval(()=>{if(!updatesPaused&&!document.hidden&&!dialog.open&&!confirm.open&&!busy&&!routeDialog.open&&!routeConfirm.open)load().catch(e=>failure(e,message));},5000);
})();
