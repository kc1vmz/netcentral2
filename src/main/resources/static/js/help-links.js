(() => {
  function reveal() {
    const target = document.getElementById(location.hash.slice(1));
    if (!target) return;
    for (let parent = target.parentElement; parent; parent = parent.parentElement)
      if (parent.tagName === 'DETAILS') parent.open = true;
    target.scrollIntoView({block:'start'});
  }
  window.addEventListener('hashchange', reveal);
  reveal();
})();
