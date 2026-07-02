import { NavLink, Outlet, Navigate } from 'react-router-dom';
import { getIdentity, displayName } from '../state/identity';
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
  const identity = getIdentity();
  if (!identity) return <Navigate to="/welcome" replace />;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <header
        style={{
          background: 'var(--warm-white)',
          borderBottom: '1px solid var(--stone)',
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            maxWidth: 'var(--content-max-width)',
            margin: '0 auto',
            padding: '16px 32px',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 32 }}>
            <span className="wordmark" style={{ fontSize: '1.5rem' }}>
              Boet
            </span>
            <nav style={{ display: 'flex', gap: 20 }}>
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
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
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
              }}
              title={displayName(identity)}
            >
              {displayName(identity).charAt(0)}
            </span>
            <button className="btn-ghost" onClick={logout}>
              Logga ut
            </button>
          </div>
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
