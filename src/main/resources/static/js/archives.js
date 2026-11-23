(() => {
  const root = document.getElementById('archive-browser');
  const el = id => document.getElementById('archive-' + id);
  const time = value => value ? new Date(value).toLocaleString() : '—';
  const text = (tag, value) => Object.assign(document.createElement(tag), {textContent:value ?? '—'});
  const facts = (target, entries) => {
    const dl = document.createElement('dl'); dl.className = 'resource-facts';
    for (const [label,value] of entries) {
      const dd=text('dd',label==='Status color' && value==='WHITE'?'Unknown':value);
      if(label==='Status color' && ['RED','YELLOW','GREEN','WHITE'].includes(value))dd.className='participant-color participant-color-'+value.toLowerCase();
      dl.append(text('dt',label),dd);
    }
    target.replaceChildren(dl);
  };
  let selected = null, generation = 0, controller, listOffset = 0, listBusy = false, listDone = false;
  let messageOffset = 0, messageBusy = false, messageDone = false;
  async function get(url, signal) {
    const response = await fetch(url, {signal,cache:'no-store'});
    if (!response.ok) throw new Error(response.status === 403 || response.status === 401 ? 'Access expired or denied.' : 'Unable to load archive (' + response.status + ').');
    return response.json();
  }
  function error(e) { if (e.name !== 'AbortError') el('status').textContent = e.message; }
  function highlight() {
    for (const row of el('list').children) {
      row.classList.toggle('selected',row.dataset.id === selected);
      row.querySelector('button').setAttribute('aria-pressed',String(row.dataset.id === selected));
    }
  }
  async function loadList() {
    if (listBusy || listDone) return;
    listBusy = true; el('more').disabled = true;
    try {
      const rows = await get('/api/v1/archiveBrowser?offset=' + listOffset + '&limit=50');
      listOffset += rows.length; listDone = rows.length < 50;
      for (const n of rows) {
        const row = document.createElement('tr'); row.dataset.id = n.id;
        const td = document.createElement('td'), button = text('button',n.callsign); button.type='button'; button.className='row-select';
        button.addEventListener('click',() => select(n.id,true)); td.append(button);
        row.append(td,text('td',n.name),text('td',time(n.startTime)),text('td',time(n.endTime))); el('list').append(row);
      }
      highlight(); el('more').hidden = listDone;
      el('empty').hidden = listOffset !== 0; el('columns').hidden = listOffset === 0;
      if (!selected && !new URL(location.href).searchParams.get('net') && rows.length) await select(rows[0].id,false);
    } catch(e) { error(e); } finally { listBusy=false; el('more').disabled=false; }
  }
  async function messages(token) {
    if (messageBusy || messageDone || !selected) return;
    messageBusy=true; el('messages-more').disabled=true;
    try {
      const rows = await get('/api/v1/completedNets/' + encodeURIComponent(selected) + '/messagePage?offset=' + messageOffset + '&limit=100',controller.signal);
      if (token !== generation) return;
      messageOffset += rows.length; messageDone = rows.length < 100;
      for (const m of rows) {
        if (/^(ack|rej)[A-Za-z0-9]{1,5}$/.test(m.message || '')) continue;
        const row = document.createElement('tr');
        for (const value of [time(m.receivedTime),m.callsignFrom,m.recipient,m.message,m.delivery]) row.append(text('td',value));
        el('messages').append(row);
      }
      el('messages-more').hidden=messageDone;
    } catch(e) { if(token === generation) error(e); }
    finally { if(token === generation) {messageBusy=false; el('messages-more').disabled=false;} }
  }
  async function select(id, push) {
    controller?.abort(); controller=new AbortController(); const token=++generation;
    selected=id; messageOffset=0; messageBusy=false; messageDone=false; highlight();
    for (const part of root.querySelectorAll('[data-archive-selected]')) part.hidden=true;
    for (const name of ['details','reports','participants','messages','questions','answers']) el(name).replaceChildren();
    el('participant-details').textContent='Select a participant.'; el('answers').textContent='Select a question.';
    el('status').textContent='Loading archive…';
    const url=new URL(location.href); url.searchParams.set('net',id);
    if(push) history.pushState(null,'',url); else history.replaceState(null,'',url);
    try {
      // Validate archive membership before loading the reusable read-only snapshot.
      await get('/api/v1/completedNets/' + encodeURIComponent(id),controller.signal);
      const s=await get('/api/v1/nets/' + encodeURIComponent(id) + '/snapshot',controller.signal);
      if(token !== generation) return;
      const n=s.net;
      facts(el('details'),[['Callsign',n.callsign],['Name',n.name],['Description',n.description],['Check-in',n.open?'Open':'Closed'],['Status','Archived'],['Start time',time(n.startTime)],['End time',time(n.endTime)],['Voice frequency',n.voiceFrequency]]);
      for(const [path,label] of [['participationReports','Generate ICS 309 Report'],['messageReports','Generate Message Report'],['questionAnswerReports','Generate Q&A Report']]) {
        const a=text('a',label); a.className='button-link'; a.href='/api/v1/completedNets/'+encodeURIComponent(id)+'/'+path; el('reports').append(a);
      }
      const participants = netParticipantRows(s.participants, s.expectedParticipants);
      for(const p of participants) {
        const row=document.createElement('tr'),td=document.createElement('td'),button=text('button',p.callsign); button.type='button';button.className='row-select';
        button.addEventListener('click',()=>{
          for(const r of el('participants').children) {r.classList.toggle('selected',r===row);r.querySelector('button').setAttribute('aria-pressed',String(r===row));}
          facts(el('participant-details'),p.expectedOnly ? [['Callsign',p.callsign],['Status','Expected']] : [['Callsign',p.callsign],['Tactical callsign',p.tacticalCallsign],['Status',p.status],['Status color',p.statusColor],['Frequency',p.voiceFrequency],['Checked in',time(p.startTime)],['Checked out',time(p.endTime)],['Last heard',time(p.lastHeardTime)],['Location',p.lon == null ? 'Unknown' : p.lon+', '+p.lat],['Transmit watts',p.transmitPower],['Power',p.electricalPowerType],['Backup power',p.backupElectricalPowerType],['Radio',p.radioStyle]]);
        });
        button.setAttribute('aria-pressed','false');td.append(button);row.append(td,text('td',p.status));
        const color=text('td',p.statusColor==='WHITE'?'Unknown':p.statusColor); if(['RED','YELLOW','GREEN','WHITE'].includes(p.statusColor))color.className='participant-color participant-color-'+p.statusColor.toLowerCase();
        row.append(color,text('td',time(p.lastHeardTime)));el('participants').append(row);
      }
      if(!participants.length) {const row=document.createElement('tr'),td=text('td','No participants.');td.colSpan=4;row.append(td);el('participants').append(row);}
      for(const q of s.questions || []) {
        const button=text('button','Q '+q.number+': '+q.question);button.className='row-select';button.type='button';button.setAttribute('aria-pressed','false');
        button.addEventListener('click',()=>{
          for(const b of el('questions').children)b.setAttribute('aria-pressed',String(b===button));
          el('answers').replaceChildren();
          for(const a of s.answers || [])if(a.questionId===q.id)el('answers').append(text('p',a.callsign+': '+a.answer));
          if(!el('answers').children.length)el('answers').textContent='No answers recorded.';
        });el('questions').append(button);
      }
      if(!s.questions?.length) el('questions').textContent='No questions recorded.';
      for(const part of root.querySelectorAll('[data-archive-selected]'))part.hidden=false;
      el('status').textContent='';await messages(token);
    } catch(e) {if(token===generation)error(e);}
  }
  el('more').addEventListener('click',loadList);
  el('list-scroll').addEventListener('scroll',event=>{const box=event.currentTarget;if(box.scrollTop+box.clientHeight>=box.scrollHeight-80)loadList();});
  el('messages-more').addEventListener('click',()=>messages(generation));
  window.addEventListener('popstate',()=>{const id=new URL(location.href).searchParams.get('net') || el('list').firstElementChild?.dataset.id;if(id)select(id,false);});
  const initial=new URL(location.href).searchParams.get('net') || root.dataset.selected;
  if(initial)select(initial,false);
  loadList();
})();
