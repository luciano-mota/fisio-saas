import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, type Access, type Me, type Member } from './api';
import { Avatar, EmptyState, Icon, Menu, PageHeader, Sheet, useUi } from './ui';

const ROLE: Record<Member['role'], string> = { OWNER: 'Responsável', STAFF: 'Colaborador(a)' };

/** Troca de senha (Minha conta e tela obrigatória do primeiro acesso). */
export function PasswordForm({ me, onDone, submitLabel = 'Trocar senha' }: { me: Me; onDone: () => void; submitLabel?: string }) {
  const [current, setCurrent] = useState('');
  const [password, setPassword] = useState('');
  const [repeat, setRepeat] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    if (password !== repeat) { setError('As duas senhas novas não são iguais'); return; }
    setBusy(true);
    try {
      await api('/api/v1/account/password', 'PUT', { current, password });
      setCurrent(''); setPassword(''); setRepeat('');
      onDone();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }

  return (
    <form onInput={() => setError('')} className="form" onSubmit={submit}>
      <input type="email" autoComplete="username" value={me.email} readOnly hidden />
      <label>{me.mustChangePassword ? 'Senha temporária' : 'Senha atual'}
        <input type="password" required autoComplete="current-password" value={current} onChange={e => setCurrent(e.target.value)} /></label>
      <div className="row">
        <label>Nova senha<input type="password" required minLength={12} autoComplete="new-password" value={password}
                                onChange={e => setPassword(e.target.value)} /></label>
        <label>Repita a nova senha<input type="password" required minLength={12} autoComplete="new-password" value={repeat}
                                         onChange={e => setRepeat(e.target.value)} /></label>
      </div>
      <p className="muted small">Pelo menos 12 caracteres. Dica: uma frase fácil de lembrar, como “pilates-de-segunda-8h”.</p>
      {error && <p className="error-box" role="alert">{error}</p>}
      <div className="actions end"><button className="primary" disabled={busy}>{busy ? 'Salvando…' : submitLabel}</button></div>
    </form>
  );
}

/** Primeiro acesso com senha temporária: troca obrigatória antes de usar o painel. */
export function MustChangePassword({ me, onDone, onLogout }: { me: Me; onDone: () => void; onLogout: () => void }) {
  return (
    <main className="login">
      <div className="card login-card wide">
        <span className="brand-mark" aria-hidden="true"><Icon name="key" size={22} /></span>
        <h1>Crie sua senha</h1>
        <p className="muted">Você entrou com uma senha temporária em <strong>{me.clinicName}</strong>. Escolha uma senha só sua para continuar.</p>
        <PasswordForm me={me} onDone={onDone} submitLabel="Salvar e entrar" />
        <button className="link-button" onClick={onLogout}>Sair</button>
      </div>
    </main>
  );
}

/** Minha conta e equipe da clínica. */
export function AccountTab({ me }: { me: Me }) {
  const { toast, confirm } = useUi();
  const owner = me.role === 'OWNER';
  const [team, setTeam] = useState<Member[] | null>(null);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<Member | null>(null);
  const [access, setAccess] = useState<Access | null>(null);
  const [error, setError] = useState('');
  const load = useCallback(() => api<Member[]>('/api/v1/team').then(setTeam).catch(e => setError((e as Error).message)), []);
  useEffect(() => { load(); }, [load]);

  async function run(action: () => Promise<unknown>, message: string) {
    setError('');
    try { await action(); toast(message); await load(); } catch (e) { setError((e as Error).message); }
  }
  async function deactivate(m: Member) {
    if (!await confirm(`Remover o acesso de ${m.fullName ?? m.email}? A pessoa não consegue mais entrar; dá para reativar depois.`,
      { confirmLabel: 'Remover acesso', danger: true })) return;
    await run(() => api(`/api/v1/team/${m.id}`, 'DELETE'), 'Acesso removido');
  }
  async function reset(m: Member) {
    if (!await confirm(`Gerar uma senha temporária nova para ${m.fullName ?? m.email}? A senha atual dela deixa de valer.`,
      { confirmLabel: 'Gerar senha' })) return;
    setError('');
    try { setAccess(await api<Access>(`/api/v1/team/${m.id}/reset-password`, 'POST')); await load(); }
    catch (e) { setError((e as Error).message); }
  }

  return (
    <section>
      <PageHeader title="Conta e equipe" subtitle={`Você entrou como ${me.email}`} />
      <div className="card">
        <h3>Trocar minha senha</h3>
        <PasswordForm me={me} onDone={() => toast('Senha trocada')} />
      </div>

      <div className="section-title">
        <h2>Equipe</h2>
        {owner && <button className="primary" onClick={() => setAdding(true)}><Icon name="plus" size={18} /> Dar acesso</button>}
      </div>
      <p className="muted small">Responsável altera tudo. Colaborador(a) só consulta (agenda, pacientes, financeiro) — bom para recepção.</p>
      {error && <p className="error-box" role="alert">{error}</p>}
      {team?.length === 1 && <EmptyState icon="users" title="Só você por aqui" text="Dê acesso a quem ajuda na recepção ou divide o atendimento." />}
      <ul className="cards">
        {team?.map(m => (
          <li key={m.id} className={m.active ? 'row-card' : 'row-card inactive'}>
            <Avatar name={m.fullName ?? m.email} />
            <div className="grow">
              <strong>{m.fullName ?? m.email}{m.me && ' (você)'}</strong>
              <span className={m.role === 'OWNER' ? 'pill ok' : 'pill'}>{ROLE[m.role]}</span>
              {!m.active && <span className="pill">sem acesso</span>}
              <div className="meta">
                {m.fullName && <span>{m.email}</span>}
                {m.active && m.mustChangePassword && <span className="pill warn">ainda não trocou a senha temporária</span>}
              </div>
            </div>
            {owner && !m.me && (
              <Menu label={`Ações de ${m.fullName ?? m.email}`} items={m.active ? [
                { label: 'Editar nome e papel', icon: 'edit', onClick: () => setEditing(m) },
                { label: 'Gerar senha temporária', icon: 'key', onClick: () => reset(m) },
                { label: 'Remover acesso', icon: 'trash', danger: true, onClick: () => deactivate(m) }
              ] : [
                { label: 'Reativar acesso', icon: 'repeat', onClick: () => run(() => api(`/api/v1/team/${m.id}/reactivate`, 'POST'), 'Acesso reativado') }
              ]} />
            )}
          </li>
        ))}
      </ul>

      {adding && <MemberSheet onClose={() => setAdding(false)} onSaved={async a => { setAdding(false); setAccess(a); await load(); }} />}
      {editing && <MemberSheet member={editing} onClose={() => setEditing(null)}
                               onSaved={async () => { setEditing(null); toast('Acesso atualizado'); await load(); }} />}
      {access && <AccessSheet access={access} clinicName={me.clinicName} onClose={() => setAccess(null)} />}
    </section>
  );
}

function MemberSheet({ member, onClose, onSaved }: { member?: Member; onClose: () => void; onSaved: (a: Access) => void }) {
  const [email, setEmail] = useState(member?.email ?? '');
  const [fullName, setFullName] = useState(member?.fullName ?? '');
  const [role, setRole] = useState<Member['role']>(member?.role ?? 'STAFF');
  const [error, setError] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      if (member) {
        const saved = await api<Member>(`/api/v1/team/${member.id}`, 'PUT', { fullName, role });
        onSaved({ member: saved, temporaryPassword: '' });
      } else {
        onSaved(await api<Access>('/api/v1/team', 'POST', { email, fullName, role }));
      }
    } catch (e) { setError((e as Error).message); }
  }
  return (
    <Sheet title={member ? 'Editar acesso' : 'Dar acesso ao painel'} onClose={onClose}>
      <form onInput={() => setError('')} className="form" onSubmit={submit}>
        {!member && <label>E-mail<input type="email" required maxLength={255} value={email} onChange={e => setEmail(e.target.value)} /></label>}
        <label>Nome<input maxLength={120} value={fullName} placeholder="Ex.: Carla (recepção)" onChange={e => setFullName(e.target.value)} /></label>
        <fieldset className="field">
          <legend>Papel</legend>
          <div className="chips" role="radiogroup" aria-label="Papel">
            {(['STAFF', 'OWNER'] as const).map(r => (
              <button key={r} type="button" role="radio" aria-checked={role === r} className={role === r ? 'chip on' : 'chip'}
                      onClick={() => setRole(r)}>{ROLE[r]}</button>
            ))}
          </div>
        </fieldset>
        <p className="muted small">{role === 'STAFF' ? 'Consulta agenda, pacientes e financeiro; não altera nada.' : 'Pode alterar tudo, inclusive a equipe.'}</p>
        {error && <p className="error-box" role="alert">{error}</p>}
        <div className="sheet-actions">
          <button type="button" onClick={onClose}>Cancelar</button>
          <button className="primary">{member ? 'Salvar' : 'Criar acesso'}</button>
        </div>
      </form>
    </Sheet>
  );
}

/** Mostra a senha temporária uma única vez, com mensagem pronta para mandar pelo WhatsApp. */
function AccessSheet({ access, clinicName, onClose }: { access: Access; clinicName: string; onClose: () => void }) {
  const { toast } = useUi();
  const url = `${window.location.origin}/login`;
  const message = `Olá! Seu acesso ao painel da ${clinicName}:\nEndereço: ${url}\nE-mail: ${access.member.email}\n` +
    `Senha temporária: ${access.temporaryPassword}\nNo primeiro acesso, o sistema pede para você criar a sua senha.`;
  return (
    <Sheet title="Acesso criado" onClose={onClose}>
      <div className="form">
        <p>Repasse estes dados para <strong>{access.member.fullName ?? access.member.email}</strong>. A senha temporária aparece
          só agora; se perder, gere outra.</p>
        <pre className="message-preview">{message}</pre>
        <div className="actions">
          <button type="button" onClick={() => navigator.clipboard.writeText(message).then(() => toast('Copiado'))}>Copiar</button>
          <a className="button wa" target="_blank" rel="noreferrer" href={`https://wa.me/?text=${encodeURIComponent(message)}`}>
            <Icon name="whatsapp" size={18} /> Enviar pelo WhatsApp</a>
        </div>
      </div>
    </Sheet>
  );
}
