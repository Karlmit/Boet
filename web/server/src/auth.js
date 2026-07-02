import cookieSignature from 'cookie-signature';
import cookie from 'cookie';

export const COOKIE_NAME = 'boet_web_session';
const SESSION_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000; // 30 days

const cookieSecure = () => (process.env.COOKIE_SECURE ?? 'true') !== 'false';

export function issueSession(res) {
  const value = String(Date.now() + SESSION_MAX_AGE_MS);
  res.cookie(COOKIE_NAME, value, {
    httpOnly: true,
    secure: cookieSecure(),
    sameSite: 'lax',
    signed: true,
    maxAge: SESSION_MAX_AGE_MS,
  });
}

export function clearSession(res) {
  res.clearCookie(COOKIE_NAME);
}

function isValidExpiry(raw) {
  const expiry = raw ? parseInt(raw, 10) : NaN;
  return !Number.isNaN(expiry) && expiry >= Date.now();
}

// Gates every route mounted after it. API/uploads calls get a 401 (the SPA's
// fetch wrapper redirects to /login on that); page loads get redirected
// straight to /login since there's no SPA loaded yet to react to a 401.
export function requireSession(req, res, next) {
  const raw = req.signedCookies[COOKIE_NAME];
  if (!isValidExpiry(raw)) {
    if (req.path.startsWith('/api') || req.path.startsWith('/uploads')) {
      return res.status(401).json({ error: 'unauthorized' });
    }
    return res.redirect(`/login?next=${encodeURIComponent(req.originalUrl)}`);
  }
  next();
}

// The WebSocket handshake is a raw `upgrade` event on the http.Server, which
// never runs through Express/cookie-parser — parse + unsign the cookie by
// hand using the same signing scheme cookie-parser uses (`s:<value>.<hmac>`).
export function isRequestAuthorized(req) {
  const header = req.headers.cookie;
  if (!header) return false;
  const parsed = cookie.parse(header);
  const raw = parsed[COOKIE_NAME];
  if (!raw || !raw.startsWith('s:')) return false;
  const unsigned = cookieSignature.unsign(raw.slice(2), process.env.SESSION_SECRET);
  if (unsigned === false) return false;
  return isValidExpiry(unsigned);
}
