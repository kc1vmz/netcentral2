(() => {
  const root=document.getElementById('net-monitor');if(!root)return;
  const grid=root.querySelector('.monitor-grid'),map=grid.children[0],participants=grid.children[1];
  const messages=document.getElementById('messages').closest('section'),questions=document.getElementById('questions').closest('section');
  map.id='map-pane';participants.id='participant-pane';messages.id='message-pane';questions.id='question-pane';
  const panes=document.createElement('div');panes.className='monitor-panes';
  const center=document.createElement('div'),right=document.createElement('div');center.className=right.className='pane-column';
  grid.before(panes);panes.append(map,center,right);center.append(participants);right.append(messages,questions);grid.remove();
  const details=document.createElement('section');details.className='panel';details.id='participant-details';details.innerHTML='<h2>Participant details</h2><div id="selected-participant">Select a participant or map marker.</div>';center.append(details);
  const size=document.createElement('label');size.className='pane-controls';size.textContent='Map pane width ';const slider=document.createElement('input');slider.type='range';slider.min='20';slider.max='55';slider.value='34';slider.setAttribute('aria-label','Monitor map pane width');size.append(slider);map.append(size);
  try{slider.value=localStorage.getItem('netcentral.monitor.mapWidth')||'34';}catch{}
  function resize(){panes.style.setProperty('--monitor-map',slider.value+'%');window.dispatchEvent(new Event('resize'));try{localStorage.setItem('netcentral.monitor.mapWidth',slider.value);}catch{}}
  slider.addEventListener('input',resize);resize();
  const answers=document.createElement('section');answers.className='panel';answers.innerHTML='<h2>Answers</h2><div id="question-answers">Select a question.</div>';right.append(answers);
  const sectionActions=new Map();
  for(const section of [participants,details,messages,questions,answers]){
    const heading=section.querySelector('h2'),header=document.createElement('div'),actions=document.createElement('div');
    header.className='monitor-section-heading';actions.className='monitor-section-actions';actions.dataset.management='';
    heading.before(header);header.append(heading,actions);sectionActions.set(section,actions);
  }
  sectionActions.get(details).id='participant-actions';
  const controls=root.querySelector('section[data-management]');
  if(controls){
    controls.querySelector('h2')?.remove();
    const actionSections={'check-in':participants,'invite-stations':participants,'send-message':messages,'ask-question':questions,'answer-question':answers};
    for(const [id,label] of [['check-in','Check in station'],['participant-edit','Participant details'],['participant-equipment','Participant equipment'],['send-message','Send message'],['ask-question','Ask question'],['answer-question','Record answer'],['invite-stations','Invite stations']]){
      const form=document.getElementById(id);if(!form)continue;const dialog=document.createElement('dialog');dialog.id=id+'-dialog';dialog.setAttribute('aria-label',label);
      const close=document.createElement('button');close.type='button';close.className='dialog-close';close.textContent='Close';close.addEventListener('click',()=>dialog.close());
      if(id.startsWith('participant-') || id==='ask-question' || id==='invite-stations' || id==='check-in'){
        close.className='';close.textContent=id==='invite-stations'?'Close':'Cancel';close.dataset.dialogCancel='';
        const buttons=document.createElement('div');buttons.className='dialog-actions';
        const update=form.querySelector('button');if(id.startsWith('participant-'))update.textContent='Update';buttons.append(update,close);form.append(buttons);dialog.append(form);
      }else{
        const feedback=document.createElement('p');feedback.dataset.dialogStatus='';feedback.setAttribute('role','status');dialog.append(close,form,feedback);
      }
      root.append(dialog);
      if(!id.startsWith('participant-')){const open=document.createElement('button');open.type='button';open.textContent=label;open.addEventListener('click',()=>dialog.showModal());sectionActions.get(actionSections[id]).append(open);}
    }
    controls.remove();
  }
})();
