import { useState, type FormEvent } from 'react';
import { signIn } from './api';
import { Icon } from './ui';

export function Login({ onLogged }: { onLogged: () => void }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await signIn(email, password);
      onLogged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login">
      <form className="card login-card" onSubmit={submit}>
        <span className="brand-mark" aria-hidden="true"><Icon name="calendar" size={22} /></span>
        <h1>Entrar no painel</h1>
        <p className="muted">Agenda, pacientes, pacotes e o site da sua clínica, num lugar só.</p>
        <label>E-mail
          <input type="email" autoComplete="username" required value={email} onChange={e => setEmail(e.target.value)} />
        </label>
        <label>Senha
          <input type="password" autoComplete="current-password" required value={password}
                 onChange={e => setPassword(e.target.value)} />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button className="primary" disabled={busy}>{busy ? 'Entrando…' : 'Entrar'}</button>
      </form>
    </main>
  );
}
