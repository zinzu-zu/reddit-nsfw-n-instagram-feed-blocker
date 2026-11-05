// Reddit NSFW Blocker: tells the Feed Guard app on Android that this page is
// protected. It adds one invisible element at the very start of the page,
// labelled "feedguard <unix seconds>", and rewrites the time every 2 seconds.
// guard-marker.css is what makes the element visible to Android, and Firefox
// removes that stylesheet as soon as the add-on is turned off. The timer stops
// at the same moment. Either way Feed Guard notices within seconds and covers
// the page. Without Feed Guard this does nothing.
(() => {
  if (window.top !== window || window.__rnbGuardMarker) return;
  window.__rnbGuardMarker = true;

  let el = null;
  function stamp() {
    const root = document.documentElement;
    if (!root) return;
    if (!el) {
      el = document.createElement('div');
      el.id = 'rnb-guard-marker';
      el.setAttribute('role', 'img');
      // Hidden unless the add-on's stylesheet is applied.
      el.style.setProperty('display', 'none');
    }
    el.setAttribute('aria-label', 'feedguard ' + Math.floor(Date.now() / 1000));
    if (root.firstChild !== el) root.insertBefore(el, root.firstChild);
  }

  stamp();
  setInterval(stamp, 2000);
  document.addEventListener('DOMContentLoaded', stamp);
  document.addEventListener('visibilitychange', stamp);
  window.addEventListener('pageshow', stamp);
})();
