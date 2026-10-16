import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { api, isAdmin, type AdminMe, type Me } from './api';
import { AdminPanel } from './Admin';
import { Landing } from './Landing';
import { PrivacyPage, TermsPage } from './Legal';
import { Login } from './Login';
import { Panel } from './Panel';
import { PublicPage } from './PublicPage';
import { Signup } from './Signup';
import { navigate, usePath } from './router';
import './style.css';

/** Páginas abertas a quem não entrou. Termos e privacidade abrem logado ou não. */
const OPEN = ['/', '/login', '/cadastro', '/admin'];
const LEGAL = ['/termos', '/privacidade'];

function App() {
  const path = usePath();
  const [me, setMe] = useState<Me | AdminMe | null | undefined>(undefined);
  const [signupEnabled, setSignupEnabled] = useState(false);
  const publicMatch = path.match(/^\/c\/([a-z0-9-]+)(\/agendar)?\/?$/);
  const publicSlug = publicMatch?.[1];

  useEffect(() => {
    if (publicSlug) return;
    api<Me | AdminMe>('/api/v1/auth/me').then(setMe).catch(() => setMe(null));
    const expired = () => setMe(null);
    window.addEventListener('session-expired', expired);
    return () => window.removeEventListener('session-expired', expired);
  }, [publicSlug]);
  useEffect(() => {
    fetch('/api/v1/public/platform').then(r => (r.ok ? r.json() : null))
      .then((p: { signupEnabled: boolean } | null) => setSignupEnabled(!!p?.signupEnabled)).catch(() => undefined);
  }, []);

  // Logado vai para /painel (equipe da plataforma: /admin); deslogado fica nas páginas abertas (o resto vai para o login).
  useEffect(() => {
    if (publicSlug || me === undefined || LEGAL.includes(path)) return;
    if (me && isAdmin(me) && !path.startsWith('/admin')) navigate('/admin');
    if (me && !isAdmin(me) && !path.startsWith('/painel')) navigate('/painel');
    if (me === null && !OPEN.includes(path) && !path.startsWith('/admin')) navigate('/login');
  }, [me, path, publicSlug]);

  const logged = () => api<Me | AdminMe>('/api/v1/auth/me').then(setMe);
  if (publicSlug) return <PublicPage slug={publicSlug} booking={!!publicMatch?.[2]} />;
  if (path === '/termos') return <TermsPage />;
  if (path === '/privacidade') return <PrivacyPage />;
  if (me === undefined) return <div className="center muted">Carregando…</div>;
  if (me === null && path === '/') return <Landing signupEnabled={signupEnabled} />;
  if (me === null && path === '/cadastro' && signupEnabled) return <Signup onLogged={logged} />;
  if (me === null && path.startsWith('/admin')) return <Login onLogged={logged} signupEnabled={false} admin />;
  if (me === null) return <Login onLogged={logged} signupEnabled={signupEnabled} />;
  if (isAdmin(me)) return <AdminPanel me={me} onLogout={() => setMe(null)} />;
  return <Panel me={me} onLogout={() => setMe(null)} onRefresh={() => api<Me>('/api/v1/auth/me').then(setMe)} />;
}

createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);
