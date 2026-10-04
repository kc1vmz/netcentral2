(() => {
 const form=document.getElementById('object-form'),dialog=document.getElementById('object-save-dialog');
 let confirmed=false;
 form.addEventListener('submit',event=>{if(!confirmed){event.preventDefault();dialog.showModal();}});
 document.getElementById('object-save-back').addEventListener('click',()=>dialog.close());
 document.getElementById('object-save-confirm').addEventListener('click',()=>{dialog.close();confirmed=true;form.requestSubmit();confirmed=false;});
})();
