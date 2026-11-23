(() => {
  const form = document.getElementById('net-editor-form');
  const mode = form.dataset.editorMode;
  const choice = form.elements.type;
  const dialog = document.getElementById('schedule-save-dialog');
  const save = document.getElementById('net-save-button');
  let confirmed = false;
  function schedule() {
    const now = choice.value === 'NOW';
    const fields = form.querySelector('[data-schedule-only]');
    fields.hidden = now;
    for (const control of fields.querySelectorAll('input,select')) control.disabled = now;
    for (const group of form.querySelectorAll('[data-recurrence]')) {
      const shown = !now && group.dataset.recurrence.split(' ').includes(choice.value);
      group.hidden = !shown;
      for (const control of group.querySelectorAll('input,select')) control.disabled = !shown;
    }
    form.action = mode === 'SCHEDULE' || (mode === 'CREATE' && !now)
      ? form.dataset.scheduleAction : form.dataset.netAction;
    save.textContent = mode === 'CREATE' ? (now ? 'Create net' : 'Create scheduled net') : 'Update';
  }
  choice.addEventListener('change', schedule);
  schedule();
  form.addEventListener('submit', event => {
    if (confirmed) return;
    event.preventDefault();
    const now = choice.value === 'NOW';
    document.getElementById('net-save-title').textContent = mode === 'CREATE'
      ? (now ? 'Create net now?' : 'Create scheduled net?')
      : mode === 'SCHEDULE' ? 'Update scheduled net?' : 'Update net?';
    document.getElementById('net-save-description').textContent = mode === 'CREATE'
      ? (now ? 'Start this net now with the entered settings?' : 'Create this scheduled net with the entered settings?')
      : mode === 'SCHEDULE' ? 'Apply these changes to future occurrences of this scheduled net?'
      : 'Apply these changes to this running net?';
    document.getElementById('schedule-save-confirm').textContent = save.textContent;
    dialog.showModal();
  });
  document.getElementById('schedule-save-back').addEventListener('click', () => dialog.close());
  document.getElementById('schedule-save-confirm').addEventListener('click', () => {
    dialog.close(); confirmed = true;
    try { form.requestSubmit(); } finally { confirmed = false; }
  });
})();
