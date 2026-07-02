import { useNavigate } from 'react-router-dom';
import { getIdentity, displayName, setIdentity } from '../state/identity';

async function logout() {
  await fetch('/auth/logout', { method: 'POST' });
  window.location.href = '/login';
}

export default function Settings() {
  const navigate = useNavigate();
  const identity = getIdentity()!;

  function switchIdentity() {
    setIdentity(identity === 'kalle' ? 'klara' : 'kalle');
    navigate('/', { replace: true });
    window.location.reload();
  }

  return (
    <div style={{ maxWidth: 480 }}>
      <h1 className="headline" style={{ marginBottom: 16 }}>
        Inställningar
      </h1>

      <div className="card" style={{ marginBottom: 12 }}>
        <div className="label">Du är inloggad som</div>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 8 }}>
          <span className="title">{displayName(identity)}</span>
          <button className="btn-ghost" onClick={switchIdentity}>
            Byt till {identity === 'kalle' ? 'Klara' : 'Kalle'}
          </button>
        </div>
      </div>

      <div className="card" style={{ marginBottom: 12 }}>
        <div className="label">Om Boet</div>
        <p className="body-text" style={{ marginTop: 8 }}>
          Boet — ett självhostat delat hushåll för Kalle &amp; Klara. Webbversionen delar samma backend som appen.
        </p>
      </div>

      <button className="btn-ghost" onClick={logout}>
        Logga ut
      </button>
    </div>
  );
}
