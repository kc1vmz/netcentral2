import {connectLive,preferences} from './live-client.mjs';
const root=document.querySelector('[data-live-index]');
if(root){
  const recurrenceLabels={ONE_TIME_ONLY:'Just once',DAILY:'Every day',WEEKLY:'Every week',MONTHLY_DAY:'Monthly on a specific day',MONTHLY_RELATIVE:'Monthly on a relative day'};
  for(const cell of root.querySelectorAll('[data-schedule-time]'))if(cell.dataset.scheduleTime)cell.textContent=new Date(cell.dataset.scheduleTime).toLocaleString();
  const limitNotice=document.createElement('p');limitNotice.hidden=true;limitNotice.setAttribute('role','status');limitNotice.textContent='Live lists show up to 2,000 records each. Counts include all accessible records; use the full record APIs for larger installations.';root.prepend(limitNotice);
  let snapshot;const filter=document.getElementById('net-filter'),prefs=preferences('netcentral.net-filter');if(filter)filter.value=prefs.read({value:''}).value;
  function updateList(id,items){const list=document.getElementById(id);if(!list)return;const wanted=new Set(items.map(n=>n.id));for(const node of [...list.children])if(!wanted.has(node.dataset.id))node.remove();const existing=new Map([...list.children].map(n=>[n.dataset.id,n]));
    for(const net of items){let li=existing.get(net.id);if(!li){li=document.createElement('li');li.dataset.id=net.id;li.append(document.createElement('a'),document.createElement('span'));list.append(li);}li.firstChild.href='/nets/'+encodeURIComponent(net.id);li.firstChild.textContent=net.endTime?new Date(net.endTime).toLocaleDateString()+' - '+net.name:net.callsign+' — '+net.name;li.lastChild.textContent=net.endTime?'':' · '+net.participantCount+' checked in'+(net.privateNet?' · Private':'');li.lastChild.hidden=Boolean(net.endTime);}
    if(!items.length){const li=document.createElement('li');li.textContent='No active nets.';list.append(li);}
  }
  function render(){if(!snapshot)return;for(const el of document.querySelectorAll('[data-count]'))el.textContent=snapshot.counts[el.dataset.count]??'—';const q=(filter?.value||'').toUpperCase(),nets=snapshot.nets.filter(n=>(n.callsign+' '+n.name).toUpperCase().includes(q));updateList('active-net-list',nets.filter(n=>!n.endTime));updateList('archive-net-list',nets.filter(n=>n.endTime));
    const rows=document.getElementById('live-schedule-rows');if(rows){const wanted=new Set(snapshot.schedules.map(s=>s.ID));for(const row of [...rows.children])if(!wanted.has(row.dataset.id))row.remove();
      for(const s of snapshot.schedules){let row=[...rows.children].find(r=>r.dataset.id===s.ID);if(!row){row=document.createElement('tr');row.dataset.id=s.ID;for(let i=0;i<4;i++)row.append(document.createElement('td'));row.children[0].append(document.createElement('a'));rows.append(row);}const link=row.children[0].firstChild;link.href='/schedules/'+encodeURIComponent(s.ID);link.textContent=s.CALLSIGN+' — '+s.NAME;[recurrenceLabels[s.TYPE]||s.TYPE,s.NEXT_START?new Date(s.NEXT_START).toLocaleString():'None',s.ENABLED?'Yes':'No'].forEach((value,index)=>row.children[index+1].textContent=value);}
      document.getElementById('empty-schedules').hidden=snapshot.schedules.length>0;document.getElementById('schedule-table').hidden=!snapshot.schedules.length;
    }
    const schedules=document.getElementById('live-schedules');if(schedules){schedules.replaceChildren();for(const s of snapshot.schedules){const li=document.createElement('li'),a=document.createElement('a');a.href='/schedules/'+encodeURIComponent(s.ID);a.textContent=s.CALLSIGN+' — '+s.NAME+' · '+(s.NEXT_START?new Date(s.NEXT_START).toLocaleString():'No upcoming occurrence');li.append(a);schedules.append(li);}}
  }
  filter?.addEventListener('input',()=>{prefs.write({value:filter.value});render();});
  connectLive(next=>{snapshot=next;limitNotice.hidden=!next.listsLimited||!root.querySelector('.live-net-list');render();},()=>{snapshot={counts:{},nets:[],schedules:[]};limitNotice.hidden=true;render();},document.getElementById('live-status'));
}

