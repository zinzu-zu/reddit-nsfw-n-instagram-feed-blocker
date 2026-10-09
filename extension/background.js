// Reddit NSFW Blocker: background service worker.
// Decides whether a subreddit or user profile is adult-only (Reddit's own
// over18 flag from about.json) and redirects tabs away from them. Keeping it
// installed is left to browser policy (see lockdown/), not to this script.

const DAY = 24 * 60 * 60 * 1000;
const TTL_KNOWN = 7 * DAY;        // verdict from Reddit itself
const TTL_UNKNOWN = 60 * 60 * 1000; // lookup failed, retry after an hour

// Used only when Reddit won't tell us (private, banned, rate limited).
const NAME_HINTS = /nsfw|gonewild|porn|hentai|onlyfans|rule34|nude|xxx|r34|lewd|boobs|thick|milf|cumsluts|celebnsfw/i;
// Common SFW "...porn" image subreddits so the fallback doesn't hit them.
const SFW_PORN_SUBS = /^(earth|food|city|sky|space|history|map|architecture|village|cabin|room|military|machine|design|art|book|rural|winter|autumn|water|beach|destruction|infrastructure)porn$/i;

const blockedUrl = (reason, name) =>
  chrome.runtime.getURL(`blocked.html?reason=${encodeURIComponent(reason)}&name=${encodeURIComponent(name || '')}`);

// ---- verdict cache -------------------------------------------------------

const memo = new Map();     // key -> {nsfw, t, ttl}
const inflight = new Map(); // key -> Promise<boolean>
let loaded = null;

function loadCache() {
  if (!loaded) {
    loaded = chrome.storage.local.get('verdicts').then(({ verdicts }) => {
      for (const [k, v] of Object.entries(verdicts || {})) memo.set(k, v);
    });
  }
  return loaded;
}

let saveTimer = null;
function saveCache() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    const now = Date.now();
    const out = {};
    for (const [k, v] of memo) if (now - v.t < v.ttl) out[k] = v;
    chrome.storage.local.set({ verdicts: out });
  }, 1000);
}

// Simple throttle so a busy feed doesn't trip Reddit's rate limit.
let chain = Promise.resolve();
function throttled(fn) {
  const p = chain.then(fn);
  chain = p.catch(() => {}).then(() => new Promise(r => setTimeout(r, 350)));
  return p;
}

function hintSays(kind, name) {
  return kind === 'r' && NAME_HINTS.test(name) && !SFW_PORN_SUBS.test(name);
}

async function fetchVerdict(kind, name) {
  const path = kind === 'u' ? `user/${name}` : `r/${name}`;
  try {
    const res = await throttled(() =>
      fetch(`https://www.reddit.com/${path}/about.json?raw_json=1`, { credentials: 'include' }));
    if (!res.ok) return { nsfw: hintSays(kind, name), ttl: TTL_UNKNOWN };
    const json = await res.json();
    const d = json && json.data;
    if (!d) return { nsfw: hintSays(kind, name), ttl: TTL_UNKNOWN };
    const nsfw = kind === 'u'
      ? Boolean(d.subreddit && (d.subreddit.over_18 || d.subreddit.over18))
      : Boolean(d.over18 || d.over_18);
    return { nsfw, ttl: TTL_KNOWN };
  } catch (e) {
    return { nsfw: hintSays(kind, name), ttl: TTL_UNKNOWN };
  }
}

// Reddit's own naming rules; anything else never reaches the fetch URL.
const VALID_NAME = { r: /^[a-z0-9_]{1,21}$/, u: /^[a-z0-9_-]{1,20}$/ };

async function isNsfw(kind, name) {
  name = String(name).toLowerCase();
  if (!VALID_NAME[kind] || !VALID_NAME[kind].test(name)) return false;
  const key = `${kind}/${name}`;
  await loadCache();
  const hit = memo.get(key);
  if (hit && Date.now() - hit.t < hit.ttl) return hit.nsfw;
  if (inflight.has(key)) return inflight.get(key);
  const p = fetchVerdict(kind, name).then(v => {
    memo.set(key, { nsfw: v.nsfw, t: Date.now(), ttl: v.ttl });
    saveCache();
    inflight.delete(key);
    return v.nsfw;
  });
  inflight.set(key, p);
  return p;
}

// ---- URL classification --------------------------------------------------

const NOT_COMMUNITIES = new Set(['all', 'popular', 'friends', 'mod', 'random', 'randnsfw', 'myrandom', 'home', 'subreddits', 'me']);

// Returns null (nothing to check), {block:'gate'} or a list of {kind,name}.
function classify(rawUrl) {
  let url;
  try { url = new URL(rawUrl); } catch { return null; }
  if (!/(^|\.)reddit\.com$/i.test(url.hostname)) return null;
  const path = url.pathname;
  // Old Reddit's "are you over 18?" gate and the random-NSFW shortcut.
  if (/^\/over18\/?/i.test(path) || /^\/r\/randnsfw\b/i.test(path)) return { block: 'gate' };
  let m = path.match(/^\/r\/([A-Za-z0-9_+]+)/);
  if (m) {
    return m[1].split('+').filter(n => n && !NOT_COMMUNITIES.has(n.toLowerCase()))
      .map(name => ({ kind: 'r', name }));
  }
  m = path.match(/^\/(?:u|user)\/([A-Za-z0-9_-]+)/);
  if (m && m[1].toLowerCase() !== 'me') return [{ kind: 'u', name: m[1] }];
  return null;
}

async function verdictForUrl(url) {
  const c = classify(url);
  if (!c) return { block: false };
  if (c.block) return { block: true, reason: c.block };
  for (const t of c) {
    if (await isNsfw(t.kind, t.name)) return { block: true, reason: t.kind === 'u' ? 'user' : 'sub', name: t.name };
  }
  return { block: false };
}

async function checkTab(tabId, url) {
  const v = await verdictForUrl(url);
  if (v.block) chrome.tabs.update(tabId, { url: blockedUrl(v.reason, v.name) });
}

// Catches typed addresses, links, and new Reddit's in-page navigation.
chrome.tabs.onUpdated.addListener((tabId, info, tab) => {
  const url = info.url || (info.status === 'loading' ? tab.url : null);
  if (url) checkTab(tabId, url);
});
chrome.tabs.onCreated.addListener(tab => {
  const url = tab.pendingUrl || tab.url;
  if (url) checkTab(tab.id, url);
});

// Content scripts ask about the current page and about subreddits in feeds.
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg && msg.type === 'checkPage') {
    verdictForUrl(msg.url).then(v => {
      if (v.block && sender.tab && sender.frameId === 0) {
        chrome.tabs.update(sender.tab.id, { url: blockedUrl(v.reason, v.name) });
      }
      sendResponse(v);
    });
    return true;
  }
  if (msg && msg.type === 'lookup') {
    Promise.all(msg.items.slice(0, 100).map(([kind, name]) => isNsfw(kind, name).then(n => [`${kind}/${String(name).toLowerCase()}`, n])))
      .then(pairs => sendResponse(Object.fromEntries(pairs)));
    return true;
  }
});

// On startup, sweep tabs that were already open.
async function sweep() {
  for (const tab of await chrome.tabs.query({})) if (tab.url) checkTab(tab.id, tab.url);
}
chrome.runtime.onStartup.addListener(sweep);
chrome.runtime.onInstalled.addListener(sweep);
