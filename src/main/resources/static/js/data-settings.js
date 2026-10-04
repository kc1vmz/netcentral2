(() => {
  const dialog=document.getElementById('data-confirm'),apply=document.getElementById('data-delete-confirm'),cancel=document.getElementById('data-delete-cancel'),error=document.getElementById('data-error'),status=document.getElementById('data-status');
  let target=null,busy=false;
  const descriptions={aprs:'Permanently delete all heard stations and callsigns, observed objects, federated net observations, received reports, raw packets, and cached heard routes? New packets can populate these again. Accounts, settings, locally managed objects, and nets will remain. This cannot be undone.',nets:'Permanently delete all active nets, archived nets, scheduled nets, participants, messages, questions, answers, and their net settings? Accounts, application settings, communication instances, and locally managed objects will remain. This cannot be undone.'};
  document.querySelectorAll('[data-delete]').forEach(button=>button.addEventListener('click',()=>{
    if(busy)return;target=button.dataset.delete;error.hidden=true;status.textContent='';
    document.getElementById('data-confirm-title').textContent=target==='aprs'?'Delete APRS Data?':'Delete Net Central Data?';
    document.getElementById('data-confirm-description').textContent=descriptions[target];dialog.showModal();cancel.focus();
  }));
  cancel.addEventListener('click',()=>{if(!busy)dialog.close();});
  dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
  dialog.addEventListener('close',()=>{target=null;});
  apply.addEventListener('click',async()=>{
    if(busy||!target)return;busy=true;apply.disabled=cancel.disabled=true;error.hidden=true;
    const scope=target;
    try{
      const response=await fetch('/api/v1/data/'+scope,{method:'DELETE',headers:{[document.querySelector('meta[name="csrf-header"]').content]:document.querySelector('meta[name="csrf-token"]').content}});
      if(!response.ok){const body=await response.json().catch(()=>({}));throw new Error(body.message||'Unable to delete data ('+response.status+').');}
      dialog.close();status.textContent=scope==='aprs'?'Heard APRS data deleted.':'Active, archived, and scheduled nets deleted.';
    }catch(failure){error.textContent=failure.message;error.hidden=false;}
    finally{busy=false;apply.disabled=cancel.disabled=false;}
  });
})();
