(() => {
  document.querySelectorAll('[data-dialog-cancel]').forEach(button => button.addEventListener('click', () => button.closest('dialog').close('cancel')));
  const roleDialog = document.getElementById('role-confirm'), roleEditor = document.getElementById('role-editor');
  const roleChoice = document.getElementById('role-choice'), roleSave = document.getElementById('role-save');
  let roleForm, deleteForm;
  document.getElementById('account-create')?.addEventListener('click', () => document.getElementById('account-create-dialog').showModal());
  document.querySelectorAll('[data-role-edit]').forEach(button => button.addEventListener('click', () => {
    roleForm = button.closest('form'); roleChoice.value = roleForm.dataset.currentRole; roleSave.disabled = true;
    document.getElementById('role-editor-account').textContent = roleForm.dataset.account; roleEditor.showModal();
  }));
  roleChoice?.addEventListener('change', () => { roleSave.disabled = roleChoice.value === roleForm.dataset.currentRole; });
  roleSave?.addEventListener('click', () => {
    if (roleChoice.value === roleForm.dataset.currentRole) return;
    roleForm.elements.role.value = roleChoice.value;
    document.getElementById('role-confirm-summary').textContent = `Change ${roleForm.dataset.account} from ${roleForm.dataset.currentRole} to ${roleChoice.value}?`;
    roleEditor.close(); roleDialog.returnValue = ''; roleDialog.showModal();
  });
  roleDialog?.addEventListener('close', () => { if (roleDialog.returnValue !== 'saved') roleEditor.showModal(); });
  document.getElementById('role-confirm-apply')?.addEventListener('click', () => {
    roleDialog.close('saved'); roleForm.requestSubmit();
  });
  const deleteDialog = document.getElementById('account-delete-confirm');
  document.querySelectorAll('.account-delete-form').forEach(form => form.addEventListener('submit', event => {
    if (form.dataset.confirmed === 'true') return;
    event.preventDefault(); deleteForm = form;
    document.getElementById('account-delete-summary').textContent = `Permanently delete ${form.dataset.account}? This cannot be undone.`;
    deleteDialog.showModal();
  }));
  document.getElementById('account-delete-apply')?.addEventListener('click', () => {
    deleteForm.dataset.confirmed = 'true'; deleteDialog.close(); deleteForm.requestSubmit();
  });

  const accessEditor = document.getElementById('access-editor'), accessConfirm = document.getElementById('access-confirm');
  const accessForm = document.getElementById('access-form');
  document.getElementById('access-add')?.addEventListener('click', () => { accessForm.reset(); accessForm.dataset.confirmed = ''; accessEditor.showModal(); });
  accessForm?.addEventListener('submit', event => {
    if (accessForm.dataset.confirmed === 'true') return;
    event.preventDefault();
    const fields = accessForm.elements;
    document.getElementById('access-summary').textContent = `Add rule: ${fields.allowed.value === 'true' ? 'Allow' : 'Deny'} ${fields.checked.value} access to ${fields.target.value}; type ${fields.type.value}; proximity ${fields.proximity.value} feet?`;
    accessEditor.close(); accessConfirm.returnValue = ''; accessConfirm.showModal();
  });
  accessConfirm?.addEventListener('close', () => { if (accessConfirm.returnValue !== 'added') accessEditor.showModal(); });
  document.getElementById('access-confirm-apply')?.addEventListener('click', () => {
    accessForm.dataset.confirmed = 'true'; accessConfirm.close('added'); accessForm.requestSubmit();
  });

  const ignoreEditor=document.getElementById('ignore-editor'), ignoreConfirm=document.getElementById('ignore-confirm');
  let ignoreForm;
  document.getElementById('ignore-add')?.addEventListener('click',()=>{ignoreEditor.querySelector('form').reset();ignoreEditor.showModal();});
  document.querySelectorAll('[data-ignore-operation]').forEach(form=>form.addEventListener('submit',event=>{
    if(form.dataset.confirmed==='true')return;
    event.preventDefault();ignoreForm=form;
    const removing=form.elements.remove?.value==='true';
    document.getElementById('ignore-summary').textContent=removing?`Stop ignoring ${form.elements.callsign.value}?`:`Ignore ${form.elements.callsign.value}? Packets from this station will not be processed.`;
    ignoreConfirm.showModal();
  }));
  document.getElementById('ignore-apply')?.addEventListener('click',()=>{
    ignoreForm.dataset.confirmed='true';document.getElementById('ignore-apply').disabled=true;ignoreForm.requestSubmit();
  });

  const panel = document.getElementById('classification-panel');
  if (!panel) return;
  const editor = document.getElementById('classification-editor'), confirmation = document.getElementById('classification-confirm');
  const form = document.getElementById('classification-form'), list = document.getElementById('classification-list');
  const error = document.getElementById('classification-error');
  const apply = document.getElementById('classification-confirm-apply');
  let pending, busy = false;
  const describe = data => `${data.ruleTarget} ${data.ruleType} ${data.value} → ${data.trackedStationType}`;
  const sortRules=()=>[...list.children].sort((a,b)=>(a.dataset.sort||'').localeCompare(b.dataset.sort||'',undefined,{sensitivity:'base'})||a.textContent.localeCompare(b.textContent)).forEach(row=>list.append(row));
  sortRules();
  const empty = () => { document.getElementById('classification-empty').hidden = list.children.length > 0; };
  async function api(path, method, body) {
    const tokenResponse = await fetch('/api/v1/csrf', {cache:'no-store'});
    if (!tokenResponse.ok) throw new Error('Session unavailable. Sign in again.');
    const csrf = await tokenResponse.json();
    const response = await fetch(path, {method, headers:{[csrf.headerName]:csrf.token,'Content-Type':'application/json'}, ...(body ? {body:JSON.stringify(body)} : {})});
    if (!response.ok || response.redirected) {
      let message = response.status === 403 ? 'Administrator access is required. Sign in again if your session expired.' : 'Could not save the change. Refresh the list and try again.';
      try { const detail = await response.json(); message = detail.detail || detail.message || message; } catch {}
      throw new Error(message);
    }
    return method === 'DELETE' ? null : response.json();
  }
  document.getElementById('classification-add').addEventListener('click', () => { form.reset(); editor.showModal(); });
  form.addEventListener('submit', event => {
    event.preventDefault(); pending = Object.fromEntries(new FormData(form));
    document.getElementById('classification-summary').textContent = `Add ${describe(pending)}?`;
    error.textContent = ''; editor.close('review'); confirmation.returnValue = ''; confirmation.showModal();
  });
  confirmation.addEventListener('close', () => { if (confirmation.returnValue !== 'added') editor.showModal(); });
  confirmation.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
  apply.addEventListener('click', async () => {
    if (busy) return; busy = true;
    confirmation.querySelectorAll('button').forEach(button => button.disabled = true);
    try {
      const rule = await api('/api/v1/trackedStationTypeRules', 'POST', pending);
      const row = document.createElement('li'); row.dataset.id = rule.id; row.dataset.version = rule.version; row.dataset.sort = rule.data.value;
      const text = document.createElement('span'); text.textContent = describe(rule.data);
      const remove = document.createElement('button'); remove.type = 'button'; remove.dataset.removeRule = ''; remove.textContent = 'Remove rule';
      row.append(text, remove); list.append(row); sortRules(); empty(); form.reset();
      confirmation.close('added');
      row.scrollIntoView({block:'nearest'});
    } catch (failure) { error.textContent = failure.message; }
    finally { busy = false; confirmation.querySelectorAll('button').forEach(button => button.disabled = false); }
  });
  const removeDialog = document.getElementById('classification-remove-confirm');
  const removeError = document.getElementById('classification-remove-error');
  let removeRow, removing = false;
  list.addEventListener('click', event => {
    const button = event.target.closest('[data-remove-rule]'); if (!button) return;
    removeRow = button.closest('li'); removeError.textContent = '';
    document.getElementById('classification-remove-summary').textContent = `Remove ${removeRow.querySelector('span').textContent}?`;
    removeDialog.showModal();
  });
  removeDialog.addEventListener('cancel', event => { if (removing) event.preventDefault(); });
  document.getElementById('classification-remove-apply').addEventListener('click', async () => {
    if (removing) return; removing = true;
    removeDialog.querySelectorAll('button').forEach(button => button.disabled = true);
    try { await api(`/api/v1/trackedStationTypeRules/${encodeURIComponent(removeRow.dataset.id)}?version=${encodeURIComponent(removeRow.dataset.version)}`, 'DELETE'); removeRow.remove(); empty(); removeDialog.close(); }
    catch (failure) { removeError.textContent = failure.message; }
    finally { removing = false; removeDialog.querySelectorAll('button').forEach(button => button.disabled = false); }
  });
})();
