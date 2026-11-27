globalThis.StripUi = (() => {
  function node(tag,text){const element=document.createElement(tag);element.textContent=text;return element;}
  function label(q){return q.strip ? q.identifier+' ['+q.question.split('/')[0]+']: '+q.question : 'Q '+q.number+': '+q.question;}
  function answers(container,q,answers){
    container.replaceChildren();
    if(!q){container.textContent='Select a question.';return;}
    if(q.strip){
      container.append(node('p',q.identifier+' ['+q.question.split('/')[0]+']'),node('p',q.question));
      const table=document.createElement('table'),head=document.createElement('thead'),row=document.createElement('tr');
      for(const name of ['Operator',...q.fields.map(f=>f.label),'Answered','Response strip'])row.append(node('th',name));
      head.append(row);table.append(head);const body=document.createElement('tbody');
      for(const a of answers){const row=document.createElement('tr'),values=a.answer.slice(0,-2).split('/');
        for(const value of [a.callsign,...values.map(v=>v===' '?'(blank)':v),new Date(a.answeredAt).toLocaleString(),a.answer])row.append(node('td',value));body.append(row);}
      table.append(body);container.append(table);
    }else for(const a of answers)container.append(node('p',a.callsign+': '+a.answer));
    if(!answers.length)container.append(node('p','No answers yet.'));
  }
  function editor(form,q){
    const container=document.getElementById('strip-answer-fields'),preview=document.getElementById('strip-answer-preview'),raw=document.getElementById('answer-text-label');
    if(!container)return;
    const key=q?.strip?q.id:'';if(container.dataset.question===key)return;container.dataset.question=key;container.replaceChildren();preview.textContent='';raw.hidden=!!q?.strip;form.elements.answer.required=!q?.strip;
    if(!q?.strip)return;
    const inputs=[];
    function update(){form.elements.answer.value=inputs.map(i=>i.value||' ').join('/')+'//';const wire='A '+q.identifier+' '+form.elements.answer.value;preview.textContent=wire+' ('+wire.length+'/67 characters; retrieval also includes operator callsign)';}
    for(const field of q.fields){const label=node('label',field.label+' ('+field.mask+')');let input;
      if(field.mask==='Y,N'){input=document.createElement('select');for(const value of ['','Y','N'])input.add(new Option(value||'Unanswered',value));}
      else {input=document.createElement('input');input.type='text';input.placeholder='Unanswered';if(field.mask==='X')input.maxLength=67;}
      inputs.push(input);input.addEventListener('input',update);label.append(input);container.append(label);
    } update();
  }
  return {label,answers,editor};
})();
