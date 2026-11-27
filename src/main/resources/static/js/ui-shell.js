(() => {
  const logoutForm=document.getElementById('logout-form'),logoutDialog=document.getElementById('logout-confirm');
  let logoutConfirmed=false;
  logoutForm?.addEventListener('submit',event=>{if(logoutConfirmed)return;event.preventDefault();logoutDialog.showModal();});
  document.getElementById('logout-cancel')?.addEventListener('click',()=>logoutDialog.close());
  document.getElementById('logout-apply')?.addEventListener('click',()=>{logoutConfirmed=true;logoutDialog.close();logoutForm.requestSubmit();});
  const pause=document.getElementById('pause-live');let updatesPaused=false;
  try{updatesPaused=sessionStorage.getItem('netcentral.updatesPaused')==='true';}catch{}
  function showPause(){if(pause){pause.textContent=updatesPaused?'Resume updates':'Pause updates';pause.setAttribute('aria-pressed',String(updatesPaused));}}
  showPause();pause?.addEventListener('click',()=>{updatesPaused=!updatesPaused;try{sessionStorage.setItem('netcentral.updatesPaused',String(updatesPaused));}catch{}showPause();window.dispatchEvent(new CustomEvent('netcentral:updates',{detail:{paused:updatesPaused}}));});
  const toggle=document.getElementById('navigation-toggle'),nav=document.getElementById('main-navigation');
  toggle?.addEventListener('click',()=>{const open=toggle.getAttribute('aria-expanded')!=='true';toggle.setAttribute('aria-expanded',String(open));nav.classList.toggle('open',open);});
  for(const link of nav?.querySelectorAll('a')||[])if(link.pathname===location.pathname)link.setAttribute('aria-current','page');
  const setup=location.pathname.startsWith('/setup/')||['/users'].includes(location.pathname);
  if(setup){
    const tabs=document.createElement('nav');tabs.className='setup-tabs';tabs.setAttribute('aria-label','Setup');
    for(const [label,url] of [['Announcements','/setup/operations?section=announcements'],['Federation','/setup/operations?section=federation'],['Ignored','/setup/radio?section=ignored'],['Strip Library','/setup/strip-library'],['Map Cache','/setup/map-cache'],['Net Manager','/setup/operations?section=net-manager'],['Classification','/setup/radio?section=classification'],['Object security','/setup/radio?section=access'],['Users','/users'],['Timers','/setup/operations?section=timers']]){
      const a=document.createElement('a');a.textContent=label;a.href=url;if(a.pathname===location.pathname&&(new URL(a.href).searchParams.get('section')===new URLSearchParams(location.search).get('section')||(!location.search&&((location.pathname==='/setup/operations'&&label==='Announcements')||(location.pathname==='/setup/radio'&&label==='Classification')))))a.setAttribute('aria-current','page');tabs.append(a);
    }
    if(document.querySelector('.site-header')?.dataset.systemAdministrator==='true'){
      const identity=document.createElement('a');identity.href='/setup/operations?section=identity';identity.textContent='Identity';
      if(location.pathname==='/setup/operations'&&new URLSearchParams(location.search).get('section')==='identity')identity.setAttribute('aria-current','page');tabs.append(identity);
      const link=document.createElement('a');link.href='/setup/data';link.textContent='Data';
      if(location.pathname==='/setup/data')link.setAttribute('aria-current','page');tabs.append(link);
    }
    const settingsTitle=document.getElementById('settings-title');
    if(settingsTitle)settingsTitle.after(tabs);else document.querySelector('main')?.prepend(tabs);
  }
  const managerEnabled=document.querySelector('[name="netManagerEnabled"]');
  function showManagerDetails(){
    const enabled=managerEnabled?.value==='true';
    document.querySelectorAll('[data-net-manager-detail]').forEach(label=>{
      label.hidden=!enabled;
      label.querySelectorAll('input,select').forEach(input=>{input.disabled=!enabled;});
    });
  }
  if(managerEnabled){showManagerDetails();managerEnabled.addEventListener('change',showManagerDetails);}
  const federationEnabled=document.querySelector('[name="federationEnabled"]');
  function showFederationDetails(){
    const enabled=federationEnabled?.value==='true';
    document.querySelectorAll('[data-federation-detail]').forEach(label=>{
      label.hidden=!enabled;
      label.querySelectorAll('input,select').forEach(input=>{input.disabled=!enabled;});
    });
  }
  if(federationEnabled){showFederationDetails();federationEnabled.addEventListener('change',showFederationDetails);}
  document.querySelectorAll('form').forEach(form=>{
    let dirty=false;form.addEventListener('input',()=>{dirty=true;});form.addEventListener('submit',()=>{dirty=false;});
    window.addEventListener('beforeunload',event=>{if(dirty){event.preventDefault();event.returnValue='';}});
  });
})();

