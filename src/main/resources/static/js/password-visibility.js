(() => {
  const toggles = document.querySelectorAll('[data-password-toggle]');
  function setVisible(button, visible) {
    document.getElementById(button.dataset.passwordToggle).type = visible ? 'text' : 'password';
    const label = (visible ? 'Hide ' : 'Show ') + button.dataset.passwordLabel;
    button.setAttribute('aria-label', label);
    button.setAttribute('aria-pressed', String(visible));
    button.title = label;
    const icon = button.querySelector('i');
    icon.classList.toggle('fa-eye', !visible);
    icon.classList.toggle('fa-eye-slash', visible);
  }
  for (const button of toggles) {
    setVisible(button, false);
    button.addEventListener('click', () => {
      const input = document.getElementById(button.dataset.passwordToggle);
      setVisible(button, input.type === 'password');
    });
  }
  // Also hide values when returning through the browser's back/forward cache.
  window.addEventListener('pageshow', () => toggles.forEach(button => setVisible(button, false)));
})();
