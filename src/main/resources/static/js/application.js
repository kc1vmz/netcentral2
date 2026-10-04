// Keep keyboard navigation predictable when using the skip link.
document.querySelector('.skip-link')?.addEventListener('click', () => {
    const main = document.querySelector('main');
    main.setAttribute('tabindex', '-1');
    main.focus();
});
