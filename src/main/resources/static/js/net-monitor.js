/* Same-origin, coalesced snapshots keep all rendered state consistent across reconnects. */
(async () => {
  const root = document.getElementById('net-monitor');
  if (!root) return;
  const endpoint = '/api/v1/nets/' + encodeURIComponent(root.dataset.netId);
  const status = document.getElementById('action-status');
  const live = document.getElementById('live-status');
  const csrf = document.querySelector('meta[name="csrf-token"]').content;
  const csrfHeader = document.querySelector('meta[name="csrf-header"]').content;
  let current = null, tile = null, tileUrl = null, attribution = null, fitted = false;
  let actionPending = false;
  let paused=false,source=null;
  try{paused=sessionStorage.getItem('netcentral.updatesPaused')==='true';}catch{}
  const map = L.map('net-map').setView([39, -98], 4);
  let selectedCall=null,selectedQuestion=null;
  const storageKey='netcentral.monitor.'+root.dataset.netId;
  try { const saved=JSON.parse(localStorage.getItem(storageKey)||'{}'); if(saved.center){map.setView(saved.center,saved.zoom);fitted=true;} selectedCall=saved.call;selectedQuestion=saved.question; } catch {}
  function saveView(){try{const c=map.getCenter();localStorage.setItem(storageKey,JSON.stringify({center:[c.lat,c.lng],zoom:map.getZoom(),call:selectedCall,question:selectedQuestion}));}catch{}}
  map.on('moveend',saveView);map.on('dragstart',()=>{fitted=true;});
  document.getElementById('net-selection')?.addEventListener('change',event=>{location.href='/nets/'+encodeURIComponent(event.target.value);});
  const {showUncertainty} = await import('./position-uncertainty.mjs');
  const markers = new Map();
  import('./net-map-layers.mjs').then(({addNetMapLayers}) => addNetMapLayers(map, root.dataset.netId))
    .catch(() => {live.textContent = 'Map layer controls unavailable; reload to retry.';});
  const escape = value => { const node = document.createElement('span'); node.textContent = value; return node.innerHTML; };
  const cell = (row, value) => { const node = document.createElement('td'); node.textContent = value ?? ''; row.append(node); };
  const time = value => value ? new Date(value).toLocaleString() : '';
  const plannedEnd=document.getElementById('net-planned-end');
  if(plannedEnd?.dataset.time)plannedEnd.textContent=time(plannedEnd.dataset.time);
  const url = value => encodeURIComponent(value);
  function updateHeardAge(){
    const now=Date.now();
    document.querySelectorAll('#participants [data-last-heard]').forEach(node=>{
      const heard=Date.parse(node.dataset.lastHeard),age=now-heard;
      node.classList.toggle('heard-warning',Number.isFinite(heard)&&age>10*60*1000&&age<=20*60*1000);
      node.classList.toggle('heard-critical',Number.isFinite(heard)&&age>20*60*1000);
    });
  }
  const heardAgeTimer=setInterval(updateHeardAge,15000);

  const confirmation=document.createElement('dialog');
  confirmation.setAttribute('aria-labelledby','participant-confirm-title');
  confirmation.innerHTML='<h2 id="participant-confirm-title"></h2><p data-confirm-description></p><p data-confirm-error role="alert" hidden></p><div class="dialog-actions"><button type="button" data-confirm-apply>Update</button><button type="button" data-dialog-cancel>Cancel</button></div>';
  root.append(confirmation);
  const apply=confirmation.querySelector('[data-confirm-apply]'),confirmError=confirmation.querySelector('[data-confirm-error]');
  let pendingConfirmation=null;
  function confirmAction(title,description,operation,success,editor=null,label='Update'){
    if(actionPending || confirmation.open)return;
    pendingConfirmation={operation,success,editor};
    confirmation.querySelector('h2').textContent=title;
    confirmation.querySelector('[data-confirm-description]').textContent=description;
    confirmError.hidden=true;confirmError.textContent='';apply.textContent=label;apply.hidden=false;confirmation.querySelector('[data-dialog-cancel]').textContent='Cancel';
    confirmation.showModal();confirmation.querySelector('[data-dialog-cancel]').focus();
  }
  confirmation.querySelector('[data-dialog-cancel]').addEventListener('click',()=>confirmation.close());
  confirmation.addEventListener('close',()=>{pendingConfirmation=null;});
  confirmation.addEventListener('cancel',event=>{if(actionPending)event.preventDefault();});
  apply.addEventListener('click',async()=>{
    if(actionPending || !pendingConfirmation)return;
    const pending=pendingConfirmation;actionPending=true;
    const buttons=confirmation.querySelectorAll('button');buttons.forEach(button=>button.disabled=true);
    try{
      await pending.operation();status.textContent=pending.success;
      confirmation.close();pending.editor?.close();
    }catch(error){
      if(error.status===422){
        confirmation.querySelector('h2').textContent='Cannot invite stations';
        confirmation.querySelector('[data-confirm-description]').textContent=error.message;
        apply.hidden=true;confirmation.querySelector('[data-dialog-cancel]').textContent='Back';
      }else{confirmError.textContent=error.message;confirmError.hidden=false;}
    }
    finally{actionPending=false;buttons.forEach(button=>button.disabled=false);}
  });

  function render(snapshot) {
    if (current && (snapshot.net.version < current.net.version ||
        (snapshot.net.version === current.net.version && snapshot.routing.version < current.routing.version))) return;
    current = snapshot;
    document.getElementById('net-callsign').textContent = snapshot.net.callsign;
    document.getElementById('net-name').textContent = snapshot.net.name;
    document.getElementById('net-description').textContent = snapshot.net.description;
    document.getElementById('net-open').textContent = snapshot.net.open ? 'Open' : 'Closed';
    const end=snapshot.net.endTime?null:snapshot.plannedEndTime;
    document.getElementById('net-planned-end-label').hidden=!end;
    plannedEnd.hidden=!end;plannedEnd.textContent=time(end);
    document.getElementById('net-state').textContent = snapshot.net.endTime ? 'Archived' : 'Active';
    document.querySelectorAll('[data-management]').forEach(node => node.hidden = !snapshot.canManage || !!snapshot.net.endTime);
    if(!snapshot.canManage || snapshot.net.endTime)document.querySelectorAll('dialog[open]').forEach(dialog=>dialog.close());
    document.getElementById('archive-downloads').hidden = !snapshot.net.endTime;
    if (tileUrl !== snapshot.map.tileUrl || attribution !== snapshot.map.attribution) {
      if (tile) map.removeLayer(tile);
      tileUrl = snapshot.map.tileUrl; attribution = snapshot.map.attribution;
      tile = L.tileLayer(tileUrl, {maxZoom: 19, attribution: escape(attribution)}).addTo(map);
    }
    const visible = new Set();
    const focused=document.activeElement?.dataset?.focusKey;
    const participantRows=netParticipantRows(snapshot.participants,snapshot.expectedParticipants);
    if(!participantRows.some(p=>p.callsign===selectedCall))selectedCall=null;
    const participantActions=document.getElementById('participant-actions');
    participantActions.replaceChildren();
    const rows = document.createDocumentFragment(), recipients = document.createDocumentFragment();
    for (const participant of participantRows) {
      const active = !participant.expectedOnly && !participant.endTime;
      const row = document.createElement('tr');
      row.classList.toggle('selected',participant.callsign===selectedCall);row.dataset.callsign=participant.callsign;
      const callCell=document.createElement('td'),callButton=document.createElement('button');callButton.type='button';callButton.className='row-select';callButton.dataset.focusKey='select-'+participant.callsign;
      callButton.textContent=participant.callsign+(participant.tacticalCallsign?' ('+participant.tacticalCallsign+')':'');callButton.setAttribute('aria-pressed',String(participant.callsign===selectedCall));
      callButton.addEventListener('click',()=>{selectedCall=participant.callsign;saveView();if(markers.has(selectedCall))map.panTo(markers.get(selectedCall).getLatLng());render(current);});callCell.append(callButton);row.append(callCell);
      cell(row, participant.status);
      const statusColor=participant.expectedOnly?null:participant.statusColor;
      cell(row, statusColor ? statusColor.charAt(0)+statusColor.slice(1).toLowerCase() : '');
      if(['RED','YELLOW','GREEN'].includes(statusColor))row.lastChild.className='participant-color participant-color-'+statusColor.toLowerCase();
      cell(row, participant.expectedOnly ? '' : participant.lastHeardTime ? time(participant.lastHeardTime) : 'Unknown');
      row.lastChild.dataset.lastHeard=participant.lastHeardTime||'';
      cell(row, participant.expectedOnly ? 'Expected' : active ? 'Checked in' : 'Checked out');
      if (participant.callsign===selectedCall && active && snapshot.canManage && !snapshot.net.endTime) {
        const edit = document.createElement('button'); edit.type = 'button'; edit.textContent = 'Edit Status';
        edit.dataset.focusKey='edit-'+participant.callsign;
        edit.addEventListener('click', () => {
          selectedCall=participant.callsign;saveView();
          const form = document.getElementById('participant-edit');
          for (const key of ['callsign','status','voiceFrequency','tacticalCallsign','version']) form.elements[key].value = participant[key] ?? '';
          document.getElementById('participant-edit-dialog')?.showModal();form.elements.status.focus();
        });
        const checkout = document.createElement('button'); checkout.type = 'button'; checkout.textContent = 'Check out';
        checkout.dataset.focusKey='checkout-'+participant.callsign;
        checkout.addEventListener('click', () => confirmAction('Check out participant', 'Check out ' + participant.callsign + ' from this net?', () => request('DELETE', '/participants/' + url(participant.callsign)), 'Checked out', null, 'Check out'));
        const equipment=document.createElement('button');equipment.type='button';equipment.textContent='Edit Equipment';equipment.dataset.focusKey='equipment-'+participant.callsign;
        equipment.addEventListener('click',()=>action(async()=>{
          const response=await fetch(endpoint+'/participants/'+url(participant.callsign)+'/equipment',{cache:'no-store'});
          if(!response.ok)throw new Error('Unable to load equipment');const values=await response.json(),form=document.getElementById('participant-equipment');
          form.elements.callsign.value=participant.callsign;
          for(const [key,column] of [['X','TRANSMIT_POWER'],['E','ELECTRICAL_POWER'],['B','BACKUP_POWER'],['Y','RADIO_STYLE']])form.elements[key].value=values[column]||(key==='X'?'':'UNKNOWN');
          document.getElementById('participant-equipment-dialog').showModal();
        },'Equipment loaded'));
        const message=document.createElement('button');message.type='button';message.textContent='Send Message';message.dataset.focusKey='message-'+participant.callsign;
        message.addEventListener('click',()=>{
          const form=document.getElementById('participant-message');form.reset();
          form.elements.sender.value=snapshot.net.callsign;form.elements.recipient.value=participant.callsign;
          document.getElementById('participant-message-dialog').showModal();form.elements.message.focus();
        });
        participantActions.append(edit,equipment,message,checkout);
      }
      rows.append(row);
      if (active) { const option = document.createElement('option'); option.value = participant.callsign; recipients.append(option); }
      if ((active || snapshot.net.endTime) && participant.lat != null && participant.lon != null) {
        visible.add(participant.callsign);
        let marker = markers.get(participant.callsign);
        if (!marker) {
          marker = L.circleMarker([participant.lat,participant.lon], {radius:7,color:'#0c6679',fillOpacity:0.85}).addTo(map);
          markers.set(participant.callsign,marker);
          marker.on('click',()=>{selectedCall=participant.callsign;saveView();if(current)render(current);});
        }
        marker.setLatLng([participant.lat,participant.lon]);showUncertainty(marker,map,participant);
        marker.setStyle({color:participant.callsign===selectedCall?'#c57017':'#0c6679'});
        const label = document.createElement('span'); label.textContent = participant.callsign + ': ' + participant.status;
        if(participant.uncertaintyMeters>0) label.textContent += ' (Approximate location)';marker.bindTooltip(label);
      }
    }
    for (const [call, marker] of markers) if (!visible.has(call)) { map.removeLayer(marker); markers.delete(call); }
    if (!fitted && markers.size) {
      map.fitBounds(L.featureGroup([...markers.values()]).getBounds().pad(0.15), {maxZoom:12}); fitted = true;
    }
    document.getElementById('participants').replaceChildren(rows);
    updateHeardAge();
    const details=document.getElementById('selected-participant'),selected=participantRows.find(p=>p.callsign===selectedCall);
    if(details){details.replaceChildren();if(selected){const facts=document.createElement('dl');facts.className='resource-facts';
      for(const [label,value] of (selected.expectedOnly ? [['Callsign',selected.callsign],['Status','Expected']] : [['Callsign',selected.callsign],['Tactical callsign',selected.tacticalCallsign],['Status',selected.status],['Status color',selected.statusColor],['Frequency',selected.voiceFrequency],['Checked in',time(selected.startTime)],['Last heard',time(selected.lastHeardTime)],['Position precision',selected.uncertaintyMeters>0?'Approximate':'Reported position'],['Latitude',selected.lat],['Longitude',selected.lon],['Transmit watts',selected.transmitPower],['Power',selected.electricalPowerType],['Backup power',selected.backupElectricalPowerType],['Radio',selected.radioStyle]])){const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=label;dd.textContent=value??'Unknown';if(label==='Status color'&&['RED','YELLOW','GREEN'].includes(value)){dd.textContent=value.charAt(0)+value.slice(1).toLowerCase();dd.className='participant-color participant-color-'+value.toLowerCase();}facts.append(dt,dd);}details.append(facts);
    }else details.textContent='Select a participant or map marker.';}
    if(focused)[...document.querySelectorAll('[data-focus-key]')].find(el=>el.dataset.focusKey===focused)?.focus({preventScroll:true});
    document.getElementById('recipient-list')?.replaceChildren(recipients);
    const messages = document.createDocumentFragment();
    for (const message of snapshot.messages) {
      if (/^(ack|rej)[A-Za-z0-9]{1,5}$/.test(message.message || '')) continue;
      const row = document.createElement('tr');
      cell(row,time(message.receivedTime)); cell(row,message.callsignFrom); cell(row,message.recipient);
      cell(row,message.message); cell(row,message.delivery); messages.append(row);
    }
    document.getElementById('messages').replaceChildren(messages);
    const questions=document.getElementById('questions'),allQuestions=snapshot.questions||[];
    if(!allQuestions.some(q=>q.id===selectedQuestion))selectedQuestion=allQuestions[0]?.id??null;
    const keys=new Set(allQuestions.map(q=>q.id));for(const child of [...questions.children])if(!keys.has(child.dataset.id))child.remove();
    for(const question of allQuestions){let button=[...questions.children].find(b=>b.dataset.id===question.id);if(!button){button=document.createElement('button');button.type='button';button.dataset.id=question.id;button.className='row-select';button.addEventListener('click',()=>{selectedQuestion=question.id;saveView();render(current);});questions.append(button);}button.textContent='Q '+question.number+': '+question.question;button.setAttribute('aria-pressed',String(question.id===selectedQuestion));}
    const answers=document.getElementById('question-answers');if(answers){answers.replaceChildren();for(const answer of snapshot.answers||[])if(answer.questionId===selectedQuestion){const p=document.createElement('p');p.textContent=answer.callsign+': '+answer.answer;answers.append(p);}if(!answers.children.length)answers.textContent=selectedQuestion?'No answers yet.':'Select a question.';}
    live.textContent = paused?'Paused':'Live';
  }
  async function snapshot() {
    const response = await fetch(endpoint + '/snapshot', {cache:'no-store'});
    if (response.status === 401 || response.status === 403) {
      source?.close();
      root.replaceChildren(Object.assign(document.createElement('p'), {textContent:'Access expired or changed. Sign in again or return to the net list.'}));
      throw new Error('Access expired or changed');
    }
    if (!response.ok) throw new Error('Unable to refresh net');
    render(await response.json());
  }
  async function request(method, path, body) {
    const response = await fetch(endpoint + path, {
      method, headers:{[csrfHeader]:csrf,'Content-Type':'application/json'},
      body:body === undefined ? undefined : JSON.stringify(body)
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      const failure=new Error(error.message || 'Request failed (' + response.status + ')');failure.status=response.status;throw failure;
    }
    const result = response.headers.get('content-type')?.includes('application/json') ? await response.json() : null;
    await snapshot();
    return result;
  }
  async function action(operation, success = 'Saved') {
    if (actionPending) return;
    actionPending = true;
    try { await operation(); status.textContent = success; }
    catch (error) { status.textContent = error.message; }
    finally { document.querySelectorAll('dialog[open] [data-dialog-status]').forEach(node=>node.textContent=status.textContent); actionPending = false; }
  }
  document.getElementById('check-in')?.addEventListener('submit', event => {
    event.preventDefault(); const form = event.currentTarget;
    const callsign=form.elements.callsign.value;
    confirmAction('Check in Station', 'Check in ' + callsign + ' to this net?', async () => {
      await request('POST','/participants/' + url(callsign));form.reset();
    }, 'Checked in', document.getElementById('check-in-dialog'), 'Check in');
  });
  document.getElementById('ask-question')?.addEventListener('submit', event => {
    event.preventDefault(); const form = event.currentTarget;
    const question=form.elements.question.value;
    confirmAction('Ask a question', 'Confirm sending this question to participants: ' + question, async () => {
      await request('POST','/questions',{question});form.reset();
    }, 'Question sent', document.getElementById('ask-question-dialog'), 'Ask participants');
  });
  document.getElementById('answer-question')?.addEventListener('submit', event => {
    event.preventDefault(); const form = event.currentTarget;
    action(async () => { await request('POST','/questions/' + url(form.elements.number.value) + '/answers',{callsign:form.elements.callsign.value,answer:form.elements.answer.value}); form.reset(); });
  });
  document.getElementById('invite-stations')?.addEventListener('submit', event => {
    event.preventDefault(); const form = event.currentTarget;
    const callsigns=form.elements.callsigns.value.split(/[,\s]+/).filter(Boolean);
    confirmAction('Invite stations', 'Send net invitations to: ' + callsigns.join(', ') + '?', async () => {
      await request('POST','/invitations',callsigns);form.reset();
    }, 'Invitations sent', document.getElementById('invite-stations-dialog'), 'Invite');
  });
  document.getElementById('participant-edit')?.addEventListener('submit', event => {
    event.preventDefault(); const form = event.currentTarget;
    if (!form.elements.callsign.value) { status.textContent = 'Choose a participant to edit first.'; return; }
    const body = {version:Number(form.elements.version.value),status:form.elements.status.value,
      voiceFrequency:form.elements.voiceFrequency.value,tacticalCallsign:form.elements.tacticalCallsign.value};
    const callsign=form.elements.callsign.value;
    confirmAction('Update participant details', 'Update details for ' + callsign + '?', async () => {
      const saved = await request('PUT','/participants/' + url(callsign),body);
      form.elements.version.value = saved.version;
    }, 'Participant updated', document.getElementById('participant-edit-dialog'));
  });
  document.getElementById('participant-message-cancel')?.addEventListener('click',()=>document.getElementById('participant-message-dialog').close());
  document.getElementById('participant-message')?.addEventListener('submit',event=>{
    event.preventDefault();const form=event.currentTarget;
    const body={recipient:form.elements.recipient.value,message:form.elements.message.value,
      acknowledgementRequested:form.elements.acknowledgementRequested.checked};
    confirmAction('Send Message', 'Send from ' + form.elements.sender.value + ' to ' + body.recipient + ' only: ' + body.message, async()=>{
      await request('POST','/messages',body);form.reset();
    }, 'Message recorded; see delivery status', document.getElementById('participant-message-dialog'), 'Send Message');
  });
  document.getElementById('send-message')?.addEventListener('submit', event => {
    event.preventDefault(); const form = event.currentTarget;
    action(async () => { await request('POST','/messages',{recipient:form.elements.recipient.value,message:form.elements.message.value,
      acknowledgementRequested:form.elements.acknowledgementRequested.checked}); form.elements.message.value = ''; },'Message recorded; see delivery status below');
  });
  document.getElementById('participant-equipment')?.addEventListener('submit',event=>{
    event.preventDefault();const form=event.currentTarget,values={};
    if(!form.elements.callsign.value){status.textContent='Choose Equipment beside a participant first.';return;}
    for(const key of ['X','E','B','Y'])if(form.elements[key].value)values[key]=form.elements[key].value;
    const callsign=form.elements.callsign.value;
    confirmAction('Update participant equipment', 'Update equipment for ' + callsign + '?',
      ()=>request('PUT','/participants/'+url(callsign)+'/equipment',values),'Equipment updated',document.getElementById('participant-equipment-dialog'));
  });
  document.getElementById('close-net')?.addEventListener('click', () => {
    if (!current || !current.canManage || current.net.endTime) return;
    const net=current.net;
    confirmAction('Secure net', 'Close and archive ' + net.callsign + ' — ' + net.name + '? Participants and messages will be archived. This net cannot be reopened.', async () => {
      await request('DELETE','?version=' + net.version);location.assign('/nets');
    }, 'Net archived', null, 'Secure');
  });
  function connect(){source?.close();if(paused)return;const own=new EventSource(endpoint+'/events');source=own;
    own.addEventListener('snapshot',event=>{if(paused||source!==own)return;try{render(JSON.parse(event.data));}catch{live.textContent='Unable to render update';}});
    own.onerror=()=>{if(paused||source!==own)return;live.textContent='Reconnecting…';snapshot().catch(error=>{live.textContent=error.message;});};
  }
  window.addEventListener('netcentral:updates',event=>{paused=event.detail.paused;if(paused){source?.close();source=null;live.textContent='Paused';}else connect();});
  connect();
  snapshot().catch(error => { live.textContent = error.message; });
  window.addEventListener('pagehide', () => {source?.close();clearInterval(heardAgeTimer);});
  window.addEventListener('pageshow',event=>{if(event.persisted)location.reload();});
})();

