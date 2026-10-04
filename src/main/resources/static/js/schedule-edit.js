(() => {
 const form=document.getElementById('schedule-form'),relative=document.getElementById('relative-week'),dialog=document.getElementById('schedule-save-dialog');
 function recurrence(){
   for(const group of form.querySelectorAll('[data-recurrence]')){
     const shown=group.dataset.recurrence.split(' ').includes(form.elements.type.value);
     group.hidden=!shown;for(const input of group.querySelectorAll('input,select'))input.disabled=!shown;
   }
 }
 form.elements.type.addEventListener('change',recurrence);recurrence();
 let confirmed=false;
 form.addEventListener('submit',event=>{if(!confirmed){event.preventDefault();dialog.showModal();}});
 document.getElementById('schedule-save-back').addEventListener('click',()=>dialog.close());
 document.getElementById('schedule-save-confirm').addEventListener('click',()=>{dialog.close();confirmed=true;form.requestSubmit();confirmed=false;});
})();
