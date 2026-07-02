// Kalle/Klara identity picked after PIN login. Matches Android's Prefs.identity:
// not a security boundary (the PIN already gates access), purely attribution.
export type Identity = 'kalle' | 'klara';

const KEY = 'boet.identity';

export function getIdentity(): Identity | null {
  const v = localStorage.getItem(KEY);
  return v === 'kalle' || v === 'klara' ? v : null;
}

export function setIdentity(identity: Identity) {
  localStorage.setItem(KEY, identity);
}

export function displayName(identity: Identity): string {
  return identity === 'kalle' ? 'Kalle' : 'Klara';
}

// Stable per-browser member id sent as the WS `memberId` query param, distinct
// from the identity name (mirrors Android's per-install memberId).
const MEMBER_ID_KEY = 'boet.memberId';

export function getMemberId(): string {
  let id = localStorage.getItem(MEMBER_ID_KEY);
  if (!id) {
    id = crypto.randomUUID();
    localStorage.setItem(MEMBER_ID_KEY, id);
  }
  return id;
}
