import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';

interface AuthState {
  authenticated: boolean;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [auth, setAuth] = useState<AuthState | null>(null);

  useEffect(() => {
    let cancelled = false;
    // Raw fetch, not the api client — /auth/me is public and a failure must
    // never trigger the client's 401 → /login redirect.
    fetch('/auth/me')
      .then((res) => (res.ok ? res.json() : { authenticated: false }))
      .catch(() => ({ authenticated: false }))
      .then((data: AuthState) => {
        if (!cancelled) setAuth({ authenticated: Boolean(data.authenticated) });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // One tiny request — holding back the first paint avoids an anon/authed
  // UI flash and lets the store provider mount already knowing its mode.
  if (!auth) return null;

  return <AuthContext.Provider value={auth}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
