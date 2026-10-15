import { useState, type FormEvent } from 'react';
import { api, signIn } from './api';
import { maskPhone } from './format';
import { PRODUCT_NAME } from './product';
import { navigate } from './router';
import './landing.css';

type Form = { clinicName: string; professionalName: string; email: string; password: string; whatsapp: string; acceptTerms: boolean; website: string };

/** Cadastro de uma nova clínica (período de testes): cria a conta e já entra no painel. */
export function Signup({ onLogged }: { onLogged: () => void }) {
  const [form, setForm] = useState<Form>({ clinicName: '', professionalName: '', email: '', password: '', whatsapp: '', acceptTerms: false, website: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (patch: Partial<Form>) => setForm({ ...form, ...patch });

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    setBusy(true);
    try {
      await api('/api/v1/public/signup', 'POST', form);
      await signIn(form.email, form.password);
      navigate('/painel');
      onLogged();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  return (
    <main className="login">
      <form onInput={() => setError('')} className="card login-card wide" onSubmit={submit}>
        <img className="login-logo" src="/brand/physioevo-logo.svg" alt="PhysioEvo" height={36} />
        <h1>Criar conta grátis</h1>
        <p className="muted">Comece a usar o {PRODUCT_NAME} agora, grátis no período de testes. O seu site profissional já vem incluso.</p>
        <label>Nome da clínica (ou o seu nome profissional)
          <input required maxLength={120} placeholder="Ex.: Fisio Ana Lima" value={form.clinicName} onChange={e => set({ clinicName: e.target.value })} /></label>
        <div className="row">
          <label>Seu nome<input maxLength={120} autoComplete="name" value={form.professionalName}
                                onChange={e => set({ professionalName: e.target.value })} /></label>
          <label>WhatsApp<input inputMode="tel" maxLength={20} placeholder="(98) 99999-0000" value={form.whatsapp}
                                onChange={e => set({ whatsapp: maskPhone(e.target.value) })} /></label>
        </div>
        <label>E-mail<input type="email" required autoComplete="username" value={form.email} onChange={e => set({ email: e.target.value })} /></label>
        <label>Senha<input type="password" required minLength={12} autoComplete="new-password" value={form.password}
                           onChange={e => set({ password: e.target.value })} /></label>
        <p className="muted small">Pelo menos 12 caracteres. Dica: uma frase, como “pilates-de-segunda-8h”.</p>
        <input className="hp" tabIndex={-1} autoComplete="off" aria-hidden="true" name="website" value={form.website}
               onChange={e => set({ website: e.target.value })} />
        <label className="signup-terms">
          <input type="checkbox" required checked={form.acceptTerms} onChange={e => set({ acceptTerms: e.target.checked })} />
          <span>Li e aceito os <a href="/termos" target="_blank" rel="noreferrer">termos de uso</a> e a{' '}
            <a href="/privacidade" target="_blank" rel="noreferrer">política de privacidade</a>.</span>
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <button className="primary" disabled={busy}>{busy ? 'Criando…' : 'Criar conta e entrar'}</button>
        <p className="small muted">Já tem conta? <a href="/login" onClick={e => { e.preventDefault(); navigate('/login'); }}>Entrar</a></p>
      </form>
    </main>
  );
}
