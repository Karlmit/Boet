// In-memory PIN brute-force guard, per source IP. A single shared household
// PIN has low entropy, so this is a real (if simple) protection, not
// decoration — no need for a persistent store, a restart resetting the
// counters is an acceptable tradeoff.
const MAX_ATTEMPTS = 10;
const WINDOW_MS = 15 * 60 * 1000;
const attempts = new Map(); // ip -> { count, resetAt }

export function isLocked(ip) {
  const a = attempts.get(ip);
  return !!a && a.count >= MAX_ATTEMPTS && a.resetAt > Date.now();
}

export function recordFailure(ip) {
  const now = Date.now();
  const a = attempts.get(ip);
  if (!a || a.resetAt < now) {
    attempts.set(ip, { count: 1, resetAt: now + WINDOW_MS });
  } else {
    a.count += 1;
  }
}

export function recordSuccess(ip) {
  attempts.delete(ip);
}
