// Narrow-purpose Instagram Reel URL validation — same single-responsibility
// scope as ssrf-guard.js, but for a different concern: this only decides
// whether a URL is shaped like an Instagram Reel worth routing into
// instagram-import.js, not whether it's network-safe to fetch. No DNS/SSRF
// check is needed here because this server never `fetch()`s the URL itself —
// yt-dlp (instagram-import.js) does its own networking against a fixed,
// well-known host (instagram.com), not an arbitrary user-supplied host the
// way url-scrape's target pages are.

export class InstagramUrlError extends Error {}

const ALLOWED_HOSTS = new Set(['instagram.com', 'www.instagram.com', 'm.instagram.com']);
const REEL_PATH = /^\/(reel|reels|share\/reel)\/[^/]+\/?/;

// Throws InstagramUrlError unless the URL is https, hosted on instagram.com
// (or the www/m subdomains), and points at a Reel (/reel/<id>, /reels/<id>,
// or the share-sheet's short-link form /share/reel/<token>, which redirects
// to a canonical /reel/<id> — yt-dlp follows that redirect itself, so no
// resolution step is needed here). Rejects feed posts (/p/...), stories,
// bare profile URLs, and non-Instagram hosts. Returns the parsed URL.
export function validateInstagramUrl(inputUrl) {
  let u;
  try {
    u = new URL(inputUrl);
  } catch {
    throw new InstagramUrlError(`invalid url: ${inputUrl}`);
  }
  if (u.protocol !== 'https:') {
    throw new InstagramUrlError(`disallowed scheme: ${u.protocol}`);
  }
  if (!ALLOWED_HOSTS.has(u.hostname.toLowerCase())) {
    throw new InstagramUrlError(`not an instagram host: ${u.hostname}`);
  }
  if (!REEL_PATH.test(u.pathname)) {
    throw new InstagramUrlError(`not a reel url: ${u.pathname}`);
  }
  return u;
}
