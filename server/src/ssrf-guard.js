// Guards the URL-scrape feature (routes/scrape.js, scrape.js) against SSRF: the
// scrape endpoint fetches an arbitrary user-supplied URL server-side, and this
// server also shares its network with internal services (Postgres, ollama, the
// translate sidecar, Docker bridge gateways) that must never be reachable via a
// pasted "recipe" link. Every outbound request the scraper makes — the initial
// fetch, each redirect hop, and each Playwright-headless navigation/redirect —
// must go through assertUrlAllowed() first.
//
// This checks scheme + resolved-IP range, which blocks the obvious cases
// (http://localhost, http://172.17.0.1, a redirect chain that bounces inward).
// It does NOT pin the validated IP for the actual connection, so a DNS server
// that flips its answer between this check and the real connect (DNS rebinding)
// could theoretically slip through — accepted as a low-risk simplification for
// a self-hosted app used by two trusted household members, not a public
// multi-tenant service.

import dns from 'node:dns/promises';

export class SsrfBlockedError extends Error {}

// [network, prefixLen] — RFC1918 + loopback + link-local + "this network".
const V4_BLOCKS = [
  ['10.0.0.0', 8],
  ['172.16.0.0', 12],
  ['192.168.0.0', 16],
  ['127.0.0.0', 8],
  ['169.254.0.0', 16],
  ['0.0.0.0', 8],
];

function v4ToInt(ip) {
  const parts = ip.split('.').map(Number);
  if (parts.length !== 4 || parts.some((p) => !Number.isInteger(p) || p < 0 || p > 255)) return null;
  return ((parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]) >>> 0;
}

function isBlockedV4(ip) {
  const n = v4ToInt(ip);
  if (n === null) return true; // unparsable -> refuse rather than risk it
  return V4_BLOCKS.some(([network, prefixLen]) => {
    const netInt = v4ToInt(network);
    const mask = prefixLen === 0 ? 0 : (0xffffffff << (32 - prefixLen)) >>> 0;
    return (n & mask) === (netInt & mask);
  });
}

// IPv6 loopback (::1), unique-local (fc00::/7), link-local (fe80::/10), and an
// IPv4-mapped address (::ffff:a.b.c.d) carrying a blocked v4 address.
function isBlockedV6(ip) {
  const norm = ip.toLowerCase();
  if (norm === '::1') return true;
  const mapped = norm.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/);
  if (mapped) return isBlockedV4(mapped[1]);
  const firstGroup = norm.split(':')[0];
  const firstHextet = parseInt(firstGroup || '0', 16) || 0;
  if ((firstHextet & 0xfe00) === 0xfc00) return true; // fc00::/7
  if ((firstHextet & 0xffc0) === 0xfe80) return true; // fe80::/10
  return false;
}

export function isDisallowedIp(ip, family) {
  return family === 6 ? isBlockedV6(ip) : isBlockedV4(ip);
}

// Throws SsrfBlockedError if the URL isn't http(s) or resolves to any
// private/loopback/link-local/reserved address. Returns the parsed URL.
export async function assertUrlAllowed(urlString) {
  let u;
  try {
    u = new URL(urlString);
  } catch {
    throw new SsrfBlockedError(`invalid url: ${urlString}`);
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') {
    throw new SsrfBlockedError(`disallowed scheme: ${u.protocol}`);
  }
  let addrs;
  try {
    addrs = await dns.lookup(u.hostname, { all: true });
  } catch (e) {
    throw new SsrfBlockedError(`could not resolve host: ${u.hostname}`);
  }
  if (addrs.length === 0 || addrs.some((a) => isDisallowedIp(a.address, a.family))) {
    throw new SsrfBlockedError(`blocked host (resolves to a private/reserved address): ${u.hostname}`);
  }
  return u;
}
