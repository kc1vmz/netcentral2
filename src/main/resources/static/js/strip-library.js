(() => {
  const byId=id=>document.getElementById(id), list=byId('strip-library-list'), form=byId('strip-template-form'), editor=byId('strip-template-editor'), confirmation=byId('strip-confirm');
  let editing=null,busy=false,pending=null;
  async function request(method,path='',body) {
    const headers={'Content-Type':'application/json'};
    headers[document.querySelector('meta[name="csrf-header"]').content]=document.querySelector('meta[name="csrf-token"]').content;
    const response=await fetch('/api/v1/strip-library'+path,{method,headers,body:body?JSON.stringify(body):undefined});
    if(!response.ok){const error=await response.json().catch(()=>({}));throw Error(error.message||error.detail||'Library request failed');}
    return response.json();
  }
  function open(template=null){editing=template;form.reset();form.elements.name.value=template?.name||'';form.elements.definition.value=template?.definition||'';byId('strip-editor-error').textContent='';byId('strip-template-heading').textContent=template?'Edit strip':'Create strip';byId('strip-save').textContent=template?'Update':'Create';editor.showModal();}
  function confirm(summary,action){pending=action;byId('strip-confirm-summary').textContent=summary;byId('strip-confirm-error').textContent='';confirmation.showModal();}
  function render(data){list.replaceChildren();byId('strip-template-new').hidden=!data.canManage;
    for(const template of data.templates){const row=document.createElement('li'),text=document.createElement('span'),name=document.createElement('strong');name.textContent=template.name;text.textContent=template.definition;row.append(name,text);
      if(data.canManage){const actions=document.createElement('div');actions.className='dialog-actions';const edit=document.createElement('button');edit.type='button';edit.textContent='Edit';edit.onclick=()=>open(template);actions.append(edit);
        const remove=document.createElement('button');remove.type='button';remove.textContent='Remove';remove.onclick=()=>confirm('Remove strip "'+template.name+'"?',()=>request('DELETE','/'+encodeURIComponent(template.id)+'?version='+template.version));actions.append(remove);row.append(actions);}
      list.append(row);
    }if(!data.templates.length){const empty=document.createElement('li');empty.textContent='No strips in the library.';list.append(empty);}
  }
  document.querySelectorAll('[data-strip-cancel]').forEach(button=>button.onclick=()=>{if(!busy)button.closest('dialog').close();});
  for(const dialog of [confirmation,editor])dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
  byId('strip-confirm-apply').onclick=async()=>{if(busy||!pending)return;busy=true;byId('strip-confirm-apply').disabled=true;try{render(await pending());confirmation.close();if(editor.open)editor.close();byId('strip-library-status').textContent='';}catch(e){byId('strip-confirm-error').textContent=e.message;}finally{busy=false;byId('strip-confirm-apply').disabled=false;}};
  form.onsubmit=async event=>{
    event.preventDefault();if(busy)return;
    const body={name:form.elements.name.value.trim(),definition:form.elements.definition.value,version:editing?.version??0};
    busy=true;byId('strip-save').disabled=true;byId('strip-editor-error').textContent='';
    try{render(await request(editing?'PUT':'POST',editing?'/'+encodeURIComponent(editing.id):'',body));editor.close();}
    catch(error){byId('strip-editor-error').textContent=error.message;}
    finally{busy=false;byId('strip-save').disabled=false;}
  };
  byId('strip-template-new').onclick=()=>open();
  request('GET').then(render).catch(e=>byId('strip-library-status').textContent=e.message);
})();
