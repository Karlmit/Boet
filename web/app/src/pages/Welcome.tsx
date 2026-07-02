import { useNavigate } from 'react-router-dom';
import { setIdentity, type Identity } from '../state/identity';

export default function Welcome() {
  const navigate = useNavigate();

  function choose(identity: Identity) {
    setIdentity(identity);
    navigate('/', { replace: true });
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 32,
        padding: 24,
      }}
    >
      <h1 className="wordmark">Boet</h1>
      <p className="body-text">Vem är du?</p>
      <div style={{ display: 'flex', gap: 16 }}>
        <button className="btn-primary" onClick={() => choose('kalle')}>
          Kalle
        </button>
        <button className="btn-primary" onClick={() => choose('klara')}>
          Klara
        </button>
      </div>
    </div>
  );
}
