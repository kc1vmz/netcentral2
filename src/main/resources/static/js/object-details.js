(() => {
 const dialog=document.getElementById('object-action-confirm'),valueDialog=document.getElementById('resource-value-dialog');
 let pending,confirmed;
 for(const time of document.querySelectorAll('[data-eoc-time]')){
   const value=new Date(time.textContent);if(!Number.isNaN(value.getTime()))time.textContent=value.toLocaleString();
 }
 for(const button of document.querySelectorAll('[data-eoc-open]'))button.addEventListener('click',()=>{
   const edit=document.getElementById(button.dataset.eocOpen);edit.querySelector('form').reset();edit.showModal();
 });
 for(const button of document.querySelectorAll('[data-eoc-cancel]'))button.addEventListener('click',()=>button.closest('dialog').close());
 document.getElementById('add-resource-value')?.addEventListener('click',()=>valueDialog.showModal());
 document.getElementById('cancel-resource-value')?.addEventListener('click',()=>valueDialog.close());
 for(const form of document.querySelectorAll('[data-object-confirm]'))form.addEventListener('submit',event=>{
   if(confirmed===form)return;
   event.preventDefault();pending=form;
   document.getElementById('object-action-title').textContent=form.dataset.objectConfirm;
   document.getElementById('object-action-message').textContent=form.dataset.confirmMessage;
   if(form.hasAttribute('data-eoc-form')){
     const details=[...form.querySelectorAll('label')].map(label=>{
       const input=label.querySelector('input,select');
       return label.querySelector('span').textContent+': '+(input.tagName==='SELECT'?input.selectedOptions[0].textContent:input.value||'(blank)');
     });
     document.getElementById('object-action-message').textContent+='\n'+details.join('\n');
     document.getElementById('object-action-message').style.whiteSpace='pre-line';
   }
   dialog.showModal();
 });
 document.getElementById('object-action-cancel').addEventListener('click',()=>dialog.close());
 document.getElementById('object-action-apply').addEventListener('click',()=>{if(!pending)return;confirmed=pending;dialog.close();pending.requestSubmit();confirmed=null;});
})();
