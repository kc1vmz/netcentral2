(() => {
  let destination = null;
  const dirtyForms = new Set();
  document.addEventListener('input', event => {
    const form = event.target.closest('form');
    if (form && event.target.type !== 'hidden') dirtyForms.add(form);
  });
  document.addEventListener('reset', event => dirtyForms.delete(event.target), true);
  const leave = document.getElementById('monitor-leave-dialog');
  document.querySelectorAll('[data-carousel-link]').forEach(link => link.addEventListener('click', event => {
    if (!dirtyForms.size || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault(); destination = link.href; leave.showModal();
  }));
  document.getElementById('monitor-leave-cancel')?.addEventListener('click', () => leave.close());
  document.getElementById('monitor-leave-confirm')?.addEventListener('click', () => { dirtyForms.clear(); location.href = destination; });
  const deletion = document.getElementById('delete-schedule-dialog');
  document.getElementById('delete-schedule')?.addEventListener('click', () => deletion.showModal());
  document.getElementById('delete-schedule-cancel')?.addEventListener('click', () => deletion.close());
  for (const field of document.querySelectorAll('[data-monitor-time]')) {
    if (field.dataset.monitorTime) field.textContent = new Date(field.dataset.monitorTime).toLocaleString();
  }
})();
