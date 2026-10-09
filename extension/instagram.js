// Reddit NSFW Blocker: Instagram home feed.
// Covers the endless feeds (home, Explore, the Reels tab) with a full-screen
// panel and leaves profiles, single posts and reels, stories, search and
// messages working. The cover doesn't depend on Instagram's page layout, which
// differs between phone and desktop and changes often.
(() => {
  if (window.__rnbIgLoaded || window.top !== window) return;
  window.__rnbIgLoaded = true;

  // "/" is the home feed; "/explore/" the Explore grid; "/reels/" the Reels tab.
  // A single reel ("/reel/<id>/") or post ("/p/<id>/") opened from a message
  // stays visible.
  const FEED_PATHS = /^\/(explore|reels)?\/?$/i;

  function buildCover() {
    const cover = document.createElement('div');
    cover.id = 'rnb-ig-cover';
    cover.innerHTML = '<div class="rnb-card">' +
      '<strong>The feed is turned off.</strong>' +
      '<span>Go to a profile, or open your messages.</span>' +
      '<form><input type="text" name="u" placeholder="Username" aria-label="Instagram username" autocapitalize="off" autocomplete="off"></form>' +
      '<a href="/direct/inbox/">Open messages</a>' +
      '<a href="/accounts/login/">Log in</a>' +
      '</div>';
    return cover;
  }

  // Listen on the document, so the form still works if Instagram copies the
  // cover while rebuilding the page.
  document.addEventListener('submit', e => {
    const form = e.target.closest && e.target.closest('#rnb-ig-cover form');
    if (!form) return;
    e.preventDefault();
    e.stopPropagation();
    const name = form.querySelector('input').value.trim().replace(/^@/, '');
    if (/^[A-Za-z0-9._]{1,30}$/.test(name)) location.href = `/${name}/`;
  }, true);

  let cover = null;
  function setCover(on) {
    // Drop stray copies left behind when Instagram rebuilds the page.
    for (const c of document.querySelectorAll('#rnb-ig-cover')) if (c !== cover) c.remove();
    document.documentElement.classList.toggle('rnb-ig-nofeed', on);
    if (on) {
      if (!cover) cover = buildCover();
      // Instagram rebuilds the page often, so put the cover back if it's removed.
      const host = document.body || document.documentElement;
      if (cover.parentNode !== host) host.appendChild(cover);
      // Stop feed videos playing sound behind the cover.
      for (const v of document.querySelectorAll('video')) { if (!v.paused) v.pause(); v.muted = true; }
    } else if (cover) {
      cover.remove();
    }
  }

  // Logged out, Instagram shows its login form at "/", so leave that visible.
  const loginForm = () => document.querySelector('input[type="password"], input[name="password"]');
  const tick = () => setCover(FEED_PATHS.test(location.pathname) && !loginForm());
  tick();
  document.addEventListener('DOMContentLoaded', tick);
  // Instagram changes pages without reloading, so keep checking.
  setInterval(tick, 500);
})();
