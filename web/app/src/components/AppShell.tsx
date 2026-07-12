import { NavLink, Outlet, Navigate, useLocation } from 'react-router-dom';
import { getIdentity, displayName } from '../state/identity';
import { useAuth } from '../state/auth';
import { BoetStoreProvider } from '../state/store';

const navItems = [
  { to: '/', label: 'Inköpslista', end: true },
  { to: '/lists', label: 'Listor' },
  { to: '/recipes', label: 'Recept' },
  { to: '/history', label: 'Historik' },
  { to: '/settings', label: 'Inställningar' },
];

async function logout() {
  await fetch('/auth/logout', { method: 'POST' });
  window.location.href = '/login';
}

export default function AppShell() {
  const { authenticated } = useAuth();
  const location = useLocation();
  const identity = getIdentity();
  if (authenticated && !identity) return <Navigate to="/welcome" replace />;

  // On a phone the recipe page is a cooking surface, like the Android detail
  // screen: its own action bar (← Recept) is the top bar and the app header
  // just steals space — hide it there. Matches /recipes/<id> only, not the
  // /recipes/new* editors or /recipes/discover*.
  const isRecipeDetail = /^\/recipes\/(?!new$|discover$)[^/]+$/.test(location.pathname);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <header className={`app-header${isRecipeDetail ? ' app-header-hide-mobile' : ''}`}>
        <div className="app-header-inner">
          <NavLink to={authenticated ? '/' : '/recipes'} className="wordmark" style={{ fontSize: '1.5rem', textDecoration: 'none' }}>
            Boet
          </NavLink>
          {authenticated && (
            <nav className="app-nav">
              {navItems.map((item) => (
                <NavLink
                  key={item.to}
                  to={item.to}
                  end={item.end}
                  style={({ isActive }) => ({
                    fontWeight: 600,
                    fontSize: '0.9375rem',
                    color: isActive ? 'var(--moss-deep)' : 'var(--charcoal)',
                    textDecoration: 'none',
                  })}
                >
                  {item.label}
                </NavLink>
              ))}
            </nav>
          )}
          {authenticated && identity ? (
            <div className="app-auth">
              <span
                className="label"
                style={{
                  width: 32,
                  height: 32,
                  borderRadius: '50%',
                  background: 'var(--sage)',
                  color: 'var(--charcoal)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  flexShrink: 0,
                }}
                title={displayName(identity)}
              >
                {displayName(identity).charAt(0)}
              </span>
              <button className="btn-ghost btn-small" onClick={logout}>
                Logga ut
              </button>
            </div>
          ) : (
            <div className="app-auth">
              <a className="btn-primary btn-small" href={`/login?next=${encodeURIComponent(location.pathname)}`}>
                Logga in
              </a>
            </div>
          )}
        </div>
      </header>
      <main className="page-main" style={{ flex: 1 }}>
        <BoetStoreProvider>
          <Outlet />
        </BoetStoreProvider>
      </main>
    </div>
  );
}
