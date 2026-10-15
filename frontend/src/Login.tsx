import { useState, type FormEvent } from 'react';
import { signIn } from './api';
import { navigate } from './router';
import './landing.css';

/** Login do painel da clínica; em /admin, o mesmo formulário serve à equipe da plataforma. */
export function Login({ onLogged, signupEnabled, admin = false }: { onLogged: () => void; signupEnabled: boolean; admin?: boolean }) {
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
      <form onInput={() => setError('')} className="card login-card" onSubmit={submit}>
        <img className="login-logo" src="/brand/physioevo-logo.svg" alt="PhysioEvo" height={36} />
        <h1>{admin ? 'Administração da plataforma' : 'Entrar no painel'}</h1>
        <p className="muted">{admin ? 'Acesso restrito à equipe PhysioEvo.' : 'Agenda, pacientes, pacotes e o site da sua clínica, num lugar só.'}</p>
        <label>E-mail
          <input type="email" autoComplete="username" required value={email} onChange={e => setEmail(e.target.value)} />
        </label>
        <label>Senha
          <input type="password" autoComplete="current-password" required value={password}
                 onChange={e => setPassword(e.target.value)} />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button className="primary" disabled={busy}>{busy ? 'Entrando…' : 'Entrar'}</button>
        {!admin && <p className="small muted">Esqueceu a senha? Peça à responsável da clínica para gerar uma senha temporária
          (Conta e equipe). Se você é a responsável, fale com o suporte.</p>}
        <div className="login-links small">
          {signupEnabled && !admin && <a href="/cadastro" onClick={e => { e.preventDefault(); navigate('/cadastro'); }}>Criar conta grátis</a>}
          <span><a href="/termos">Termos</a> · <a href="/privacidade">Privacidade</a></span>
        </div>
      </form>
    </main>
  );
}
