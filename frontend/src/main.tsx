import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { api, type Me } from './api';
import { Login } from './Login';
import { Panel } from './Panel';
import { PublicPage } from './PublicPage';
import { navigate, usePath } from './router';
import './style.css';

function App() {
  const path = usePath();
  const [me, setMe] = useState<Me | null | undefined>(undefined);
  const publicSlug = path.match(/^\/c\/([a-z0-9-]+)\/?$/)?.[1];

  useEffect(() => {
    if (publicSlug) return;
    api<Me>('/api/v1/auth/me').then(setMe).catch(() => setMe(null));
    const expired = () => setMe(null);
    window.addEventListener('session-expired', expired);
    return () => window.removeEventListener('session-expired', expired);
  }, [publicSlug]);

  // Logado vai para /painel; deslogado vai para /login.
  useEffect(() => {
    if (publicSlug || me === undefined) return;
    if (me && !path.startsWith('/painel')) navigate('/painel');
    if (me === null && path !== '/login') navigate('/login');
  }, [me, path, publicSlug]);

  if (publicSlug) return <PublicPage slug={publicSlug} />;
  if (me === undefined) return <div className="center muted">Carregando…</div>;
  if (me === null) return <Login onLogged={() => api<Me>('/api/v1/auth/me').then(setMe)} />;
  return <Panel me={me} onLogout={() => setMe(null)} />;
}

createRoot(document.getElementById('root')!).render(<React.StrictMode><App /></React.StrictMode>);
