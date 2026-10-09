// Reddit NSFW Blocker: content script for new Reddit (shreddit) and old Reddit.
// Hides every post Reddit flags as NSFW, plus posts from subreddits whose
// about.json says over18 even when the post itself wasn't tagged.
(() => {
  if (window.__rnbLoaded) return;
  window.__rnbLoaded = true;

  const ADULT_HOSTS = /(^|\.)(redgifs\.com|erome\.com|pornhub\.com|xvideos\.com|xhamster\.com|xnxx\.com|onlyfans\.com|fansly\.com|scrolller\.com|imagefap\.com|spankbang\.com|eporner\.com|rule34\.xxx|motherless\.com|chaturbate\.com)$/i;
  const POST_CONTAINERS = 'shreddit-post, shreddit-ad-post, article, .thing, [data-testid="search-post-unit"], search-telemetry-tracker, faceplate-tracker[noun="post"]';

  // Hide the page itself until the background has checked a subreddit/user URL.
  const pageNeedsCheck = () => /^\/(r|u|user)\/|^\/over18/i.test(location.pathname);
  if (window.top === window && pageNeedsCheck()) {
    const veil = document.createElement('style');
    veil.textContent = 'html{visibility:hidden!important}';
    document.documentElement.appendChild(veil);
    const lift = () => veil.remove();
    setTimeout(lift, 5000);
    chrome.runtime.sendMessage({ type: 'checkPage', url: location.href }).then(v => { if (!v || !v.block) lift(); }, lift);
  }

  const hide = el => {
    const box = el.closest(POST_CONTAINERS) || el;
    if (box.dataset.rnbHidden) return;
    box.dataset.rnbHidden = '1';
    box.style.setProperty('display', 'none', 'important');
  };

  const truthy = v => v !== null && v !== 'false' && v !== '0';

  function flaggedNsfw(el) {
    // Old Reddit
    if (el.classList && el.classList.contains('over18')) return true;
    if (el.dataset && (el.dataset.nsfw === 'true' || el.dataset.over18 === 'true')) return true;
    // New Reddit: shreddit-post exposes nsfw/over-18 style attributes.
    for (const a of el.attributes || []) {
      if (/^(nsfw|is-nsfw|over-?18|is-over-?18|data-nsfw)$/i.test(a.name) && truthy(a.value)) return true;
    }
    // Tracking context JSON often carries "nsfw":true.
    const ctx = el.getAttribute && el.getAttribute('data-faceplate-tracking-context');
    if (ctx && /"(nsfw|over_?18)"\s*:\s*true/i.test(ctx)) return true;
    // Blurred NSFW media wrapper and NSFW badges.
    if (el.matches && el.matches('shreddit-blurred-container[reason="nsfw"], .nsfw-stamp, .nsfw-tag, [slot="nsfw-tag"], span[data-testid="nsfw-tag"]')) return true;
    return false;
  }

  function subredditOf(el) {
    const named = el.getAttribute('subreddit-prefixed-name') || el.getAttribute('subreddit-name') || (el.dataset && el.dataset.subreddit);
    if (named) return named.replace(/^r\//i, '');
    const sp = el.getAttribute('data-subreddit-prefixed') || '';
    if (/^r\//i.test(sp)) return sp.slice(2);
    return null;
  }

  function adultMedia(el) {
    const src = el.getAttribute('src') || el.getAttribute('content-href') || el.getAttribute('href') || el.getAttribute('data-url') || '';
    if (!src) return false;
    try { return ADULT_HOSTS.test(new URL(src, location.href).hostname); } catch { return false; }
  }

  // Batch subreddit lookups through the background (which caches and throttles).
  const pending = new Map(); // sub -> [elements]
  let flushTimer = null;
  function queueLookup(sub, el) {
    const key = sub.toLowerCase();
    if (!pending.has(key)) pending.set(key, []);
    pending.get(key).push(el);
    clearTimeout(flushTimer);
    flushTimer = setTimeout(flush, 150);
  }
  async function flush() {
    const batch = new Map(pending);
    pending.clear();
    const items = [...batch.keys()].map(n => ['r', n]);
    if (!items.length) return;
    try {
      const res = await chrome.runtime.sendMessage({ type: 'lookup', items });
      for (const [sub, els] of batch) if (res && res[`r/${sub}`]) els.forEach(hide);
    } catch { /* extension reloaded */ }
  }

  const SELECTOR = [
    'shreddit-post', 'shreddit-ad-post', '.thing', '[data-testid="search-post-unit"]',
    '[data-faceplate-tracking-context]', 'shreddit-blurred-container', '.nsfw-stamp', '.nsfw-tag',
    '[slot="nsfw-tag"]', 'img', 'video', 'source', 'iframe', 'a[href*="redgifs"]',
  ].join(',');

  function scan(root) {
    const els = root.querySelectorAll ? root.querySelectorAll(SELECTOR) : [];
    const list = root.matches && root.matches(SELECTOR) ? [root, ...els] : els;
    for (const el of list) {
      if (el.dataset && el.dataset.rnbSeen) continue;
      if (el.dataset) el.dataset.rnbSeen = '1';
      if (flaggedNsfw(el)) { hide(el); continue; }
      if (/^(IMG|VIDEO|SOURCE|IFRAME|A)$/.test(el.tagName)) {
        if (adultMedia(el)) hide(el);
        continue;
      }
      if (el.matches('shreddit-post, shreddit-ad-post, .thing, [data-testid="search-post-unit"]')) {
        if (adultMedia(el)) { hide(el); continue; }
        const sub = subredditOf(el);
        if (sub) queueLookup(sub, el);
      }
    }
  }

  const observer = new MutationObserver(muts => {
    for (const m of muts) {
      if (m.type === 'attributes') { if (flaggedNsfw(m.target) || (/^(IMG|VIDEO|IFRAME)$/.test(m.target.tagName) && adultMedia(m.target))) hide(m.target); continue; }
      for (const n of m.addedNodes) if (n.nodeType === 1) scan(n);
    }
  });
  const start = () => {
    scan(document);
    observer.observe(document.documentElement, {
      childList: true, subtree: true, attributes: true,
      attributeFilter: ['nsfw', 'data-nsfw', 'src', 'reason'],
    });
  };

  // ---- Home feed -------------------------------------------------------
  // The front page and Reddit's other endless feeds are hidden; subreddits,
  // posts, search and profiles still work. Set to false to keep r/popular
  // and r/all.
  const HIDE_POPULAR_AND_ALL = true;
  const FEED_PATHS = /^\/(best|hot|new|top|rising|home|controversial)?\/?$/i;
  const DISCOVERY_PATHS = /^\/r\/(popular|all)(\/(best|hot|new|top|rising|controversial))?\/?$/i;
  const isFeedPage = () => FEED_PATHS.test(location.pathname) ||
    (HIDE_POPULAR_AND_ALL && DISCOVERY_PATHS.test(location.pathname));

  function showNotice() {
    if (document.getElementById('rnb-feed-notice') || !document.body) return;
    const box = document.createElement('div');
    box.id = 'rnb-feed-notice';
    box.innerHTML = '<strong>The home feed is turned off.</strong>' +
      '<span>Search, or go straight to a subreddit you came for.</span>' +
      '<form><input type="search" name="q" placeholder="Search Reddit" aria-label="Search Reddit"></form>';
    box.querySelector('form').addEventListener('submit', e => {
      e.preventDefault();
      const q = box.querySelector('input').value.trim();
      if (q) location.href = `/search/?q=${encodeURIComponent(q)}`;
    });
    document.body.prepend(box);
  }

  let lastPath = null;
  function syncFeed() {
    if (location.pathname === lastPath) return;
    lastPath = location.pathname;
    const on = isFeedPage();
    document.documentElement.classList.toggle('rnb-nofeed', on);
    if (on) showNotice();
    else document.getElementById('rnb-feed-notice')?.remove();
  }
  if (window.top === window) {
    syncFeed();
    document.addEventListener('DOMContentLoaded', () => { lastPath = null; syncFeed(); });
    // New Reddit changes pages without reloading, so keep checking the path.
    setInterval(syncFeed, 400);
  }

  if (document.documentElement) start();
  else document.addEventListener('readystatechange', start, { once: true });
})();
