import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, logoUrl, money, signOut, type ClinicProfile, type Me, type Service, type SessionPackage } from './api';
import { themeVariables } from './color';
import { AgendaTab } from './Agenda';
import { SiteTab } from './SiteTab';
import { PatientsTab } from './Patients';
import { navigate, usePath } from './router';

type Tab = 'agenda' | 'pacientes' | 'servicos' | 'pacotes' | 'site' | 'clinica';
const TABS: { id: Tab; label: string }[] = [
  { id: 'agenda', label: 'Agenda' },
  { id: 'pacientes', label: 'Pacientes' },
  { id: 'servicos', label: 'Serviços' },
  { id: 'pacotes', label: 'Pacotes' },
  { id: 'site', label: 'Site' },
  { id: 'clinica', label: 'Clínica' }
];

function applyTheme(profile: ClinicProfile | null) {
  if (!profile) return;
  const vars = themeVariables({
    primary: profile.colorPrimary, secondary: profile.colorSecondary, accent: profile.colorAccent
  });
  Object.entries(vars).forEach(([name, value]) => document.documentElement.style.setProperty(name, value));
}

export function Panel({ me, onLogout }: { me: Me; onLogout: () => void }) {
  const path = usePath();
  const tab = (TABS.find(t => path === '/painel/' + t.id || path.startsWith('/painel/' + t.id + '/'))?.id ?? TABS[0].id) as Tab;
  const [profile, setProfile] = useState<ClinicProfile | null>(null);
  const owner = me.role === 'OWNER';

  useEffect(() => {
    api<ClinicProfile>('/api/v1/clinic').then(p => { setProfile(p); applyTheme(p); });
  }, []);

  async function logout() {
    await signOut().catch(() => undefined);
    onLogout();
  }

  return (
    <div className="panel">
      <header className="topbar">
        {profile?.logoVersion && <img className="topbar-logo" src={logoUrl(profile.logoVersion)!} alt="" />}
        <strong>{profile?.displayName ?? me.clinicName}</strong>
        <span className="muted small">{me.email}</span>
        <button className="link" onClick={logout}>Sair</button>
      </header>
      <nav className="tabs" aria-label="Seções do painel">
        {TABS.map(t => (
          <button key={t.id} className={t.id === tab ? 'tab active' : 'tab'}
                  aria-current={t.id === tab ? 'page' : undefined}
                  onClick={() => navigate('/painel/' + t.id)}>{t.label}</button>
        ))}
      </nav>
      <main className="content">
        {!owner && <p className="notice">Você está como colaborador: pode consultar, mas só a responsável altera.</p>}
        {tab === 'agenda' && <AgendaTab owner={owner} />}
        {tab === 'pacientes' && <PatientsTab owner={owner} patientId={path.split('/')[3]} />}
        {tab === 'servicos' && <ServicesTab owner={owner} />}
        {tab === 'pacotes' && <PackagesTab owner={owner} />}
        {tab === 'site' && profile &&
          <SiteTab owner={owner} profile={profile} onSaved={p => { setProfile(p); applyTheme(p); }}
                   onLogoChange={setProfile} />}
        {tab === 'clinica' && profile &&
          <ClinicTab owner={owner} profile={profile} onSaved={p => { setProfile(p); applyTheme(p); }} />}
      </main>
    </div>
  );
}

// ---------- Serviços ----------

type ServiceForm = {
  id?: string; name: string; description: string; durationMinutes: string; price: string;
  showPrice: boolean; onlineBookingEnabled: boolean;
};
const EMPTY_SERVICE: ServiceForm = {
  name: '', description: '', durationMinutes: '50', price: '', showPrice: true, onlineBookingEnabled: true
};

function parsePrice(text: string): number | null {
  const clean = text.trim().replace(/\./g, '').replace(',', '.');
  if (!clean) return null;
  const value = Number(clean);
  if (!Number.isFinite(value) || value < 0) throw new Error('Preço inválido. Use, por exemplo, 90,00.');
  return Math.round(value * 100) / 100;
}

function priceText(value: number | null): string {
  return value === null ? '' : value.toFixed(2).replace('.', ',');
}

function ServicesTab({ owner }: { owner: boolean }) {
  const [items, setItems] = useState<Service[]>([]);
  const [form, setForm] = useState<ServiceForm>(EMPTY_SERVICE);
  const [error, setError] = useState('');
  const load = useCallback(() => api<Service[]>('/api/v1/services').then(setItems), []);
  useEffect(() => { load(); }, [load]);

  async function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      const payload = {
        name: form.name, description: form.description, durationMinutes: Number(form.durationMinutes),
        price: parsePrice(form.price), showPrice: form.showPrice, onlineBookingEnabled: form.onlineBookingEnabled,
        active: true
      };
      if (form.id) await api(`/api/v1/services/${form.id}`, 'PUT', payload);
      else await api('/api/v1/services', 'POST', payload);
      setForm(EMPTY_SERVICE);
      await load();
    } catch (e) { setError((e as Error).message); }
  }

  async function deactivate(service: Service) {
    if (!confirm(`Desativar "${service.name}"? Ele some da vitrine, junto com os pacotes dele.`)) return;
    await api(`/api/v1/services/${service.id}`, 'DELETE');
    await load();
  }

  return (
    <section>
      <h2>Serviços oferecidos</h2>
      <p className="muted">O que você cadastra aqui aparece na sua página pública. O preço é opcional.</p>
      {owner && (
        <form className="card form" onSubmit={save}>
          <h3>{form.id ? 'Editar serviço' : 'Novo serviço'}</h3>
          <label>Nome<input required maxLength={120} value={form.name}
                            placeholder="Ex.: Pilates, RPG, Fisioterapia ortopédica"
                            onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>Descrição curta<textarea maxLength={600} value={form.description}
                                          onChange={e => setForm({ ...form, description: e.target.value })} /></label>
          <div className="row">
            <label>Duração (min)<input type="number" min={5} max={480} required value={form.durationMinutes}
                                       onChange={e => setForm({ ...form, durationMinutes: e.target.value })} /></label>
            <label>Preço da sessão (opcional)<input inputMode="decimal" placeholder="Ex.: 90,00" value={form.price}
                                                    onChange={e => setForm({ ...form, price: e.target.value })} /></label>
          </div>
          <label className="check"><input type="checkbox" checked={form.showPrice}
                                          onChange={e => setForm({ ...form, showPrice: e.target.checked })} />
            Mostrar preço na página pública</label>
          <label className="check"><input type="checkbox" checked={form.onlineBookingEnabled}
                                          onChange={e => setForm({ ...form, onlineBookingEnabled: e.target.checked })} />
            Permitir agendamento online</label>
          {error && <p className="error" role="alert">{error}</p>}
          <div className="actions">
            <button className="primary">{form.id ? 'Salvar alterações' : 'Adicionar serviço'}</button>
            {form.id && <button type="button" onClick={() => setForm(EMPTY_SERVICE)}>Cancelar</button>}
          </div>
        </form>
      )}
      <ul className="list">
        {items.length === 0 && <li className="muted">Nenhum serviço cadastrado ainda.</li>}
        {items.map(s => (
          <li key={s.id} className={s.active ? 'item' : 'item inactive'}>
            <div>
              <strong>{s.name}</strong>{!s.active && <span className="tag">inativo</span>}
              <div className="muted small">
                {s.durationMinutes} min · {s.price === null ? 'sem preço' : money(s.price)}
                {s.price !== null && !s.showPrice && ' (oculto no site)'}
                {!s.onlineBookingEnabled && ' · sem agendamento online'}
              </div>
            </div>
            {owner && s.active && (
              <div className="actions">
                <button onClick={() => setForm({
                  id: s.id, name: s.name, description: s.description ?? '', durationMinutes: String(s.durationMinutes),
                  price: priceText(s.price), showPrice: s.showPrice, onlineBookingEnabled: s.onlineBookingEnabled
                })}>Editar</button>
                <button className="danger" onClick={() => deactivate(s)}>Desativar</button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

// ---------- Pacotes ----------

type PackageForm = {
  id?: string; serviceId: string; name: string; sessionCount: string; price: string;
  showPrice: boolean; validityDays: string;
};
const EMPTY_PACKAGE: PackageForm = { serviceId: '', name: '', sessionCount: '10', price: '', showPrice: true, validityDays: '' };

function PackagesTab({ owner }: { owner: boolean }) {
  const [items, setItems] = useState<SessionPackage[]>([]);
  const [services, setServices] = useState<Service[]>([]);
  const [form, setForm] = useState<PackageForm>(EMPTY_PACKAGE);
  const [error, setError] = useState('');
  const load = useCallback(async () => {
    setItems(await api<SessionPackage[]>('/api/v1/packages'));
    setServices((await api<Service[]>('/api/v1/services')).filter(s => s.active));
  }, []);
  useEffect(() => { load(); }, [load]);

  async function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      const payload = {
        serviceId: form.serviceId, name: form.name, sessionCount: Number(form.sessionCount),
        price: parsePrice(form.price), showPrice: form.showPrice,
        validityDays: form.validityDays ? Number(form.validityDays) : null, active: true
      };
      if (form.id) await api(`/api/v1/packages/${form.id}`, 'PUT', payload);
      else await api('/api/v1/packages', 'POST', payload);
      setForm(EMPTY_PACKAGE);
      await load();
    } catch (e) { setError((e as Error).message); }
  }

  async function deactivate(item: SessionPackage) {
    if (!confirm(`Desativar o pacote "${item.name}"?`)) return;
    await api(`/api/v1/packages/${item.id}`, 'DELETE');
    await load();
  }

  return (
    <section>
      <h2>Pacotes de sessões</h2>
      <p className="muted">Ex.: 10 sessões de Pilates. Aparecem na página pública como opções de quantidade.</p>
      {owner && services.length === 0 && <p className="notice">Cadastre um serviço antes de criar pacotes.</p>}
      {owner && services.length > 0 && (
        <form className="card form" onSubmit={save}>
          <h3>{form.id ? 'Editar pacote' : 'Novo pacote'}</h3>
          <label>Serviço
            <select required value={form.serviceId} onChange={e => setForm({ ...form, serviceId: e.target.value })}>
              <option value="">Escolha…</option>
              {services.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
          <label>Nome do pacote<input required maxLength={120} placeholder="Ex.: 10 sessões" value={form.name}
                                      onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <div className="row">
            <label>Nº de sessões<input type="number" min={1} max={200} required value={form.sessionCount}
                                       onChange={e => setForm({ ...form, sessionCount: e.target.value })} /></label>
            <label>Preço total (opcional)<input inputMode="decimal" placeholder="Ex.: 800,00" value={form.price}
                                                onChange={e => setForm({ ...form, price: e.target.value })} /></label>
            <label>Validade (dias, opcional)<input type="number" min={1} max={730} value={form.validityDays}
                                                   onChange={e => setForm({ ...form, validityDays: e.target.value })} /></label>
          </div>
          <label className="check"><input type="checkbox" checked={form.showPrice}
                                          onChange={e => setForm({ ...form, showPrice: e.target.checked })} />
            Mostrar preço na página pública</label>
          {error && <p className="error" role="alert">{error}</p>}
          <div className="actions">
            <button className="primary">{form.id ? 'Salvar alterações' : 'Adicionar pacote'}</button>
            {form.id && <button type="button" onClick={() => setForm(EMPTY_PACKAGE)}>Cancelar</button>}
          </div>
        </form>
      )}
      <ul className="list">
        {items.length === 0 && <li className="muted">Nenhum pacote cadastrado ainda.</li>}
        {items.map(p => (
          <li key={p.id} className={p.active ? 'item' : 'item inactive'}>
            <div>
              <strong>{p.serviceName} · {p.name}</strong>{!p.active && <span className="tag">inativo</span>}
              <div className="muted small">
                {p.sessionCount} sessões · {p.price === null ? 'sem preço' : money(p.price)}
                {p.price !== null && p.sessionCount > 1 && ` (${money(p.price / p.sessionCount)} por sessão)`}
                {p.price !== null && !p.showPrice && ' · oculto no site'}
                {p.validityDays && ` · validade ${p.validityDays} dias`}
              </div>
            </div>
            {owner && p.active && (
              <div className="actions">
                <button onClick={() => setForm({
                  id: p.id, serviceId: p.serviceId, name: p.name, sessionCount: String(p.sessionCount),
                  price: priceText(p.price), showPrice: p.showPrice, validityDays: p.validityDays ? String(p.validityDays) : ''
                })}>Editar</button>
                <button className="danger" onClick={() => deactivate(p)}>Desativar</button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

// ---------- Clínica e aparência ----------

function ClinicTab({ owner, profile, onSaved }: {
  owner: boolean; profile: ClinicProfile; onSaved: (p: ClinicProfile) => void;
}) {
  const [form, setForm] = useState<ClinicProfile>(profile);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const text = (key: keyof ClinicProfile) => (form[key] ?? '') as string;
  const set = (key: keyof ClinicProfile, value: string | boolean) => setForm({ ...form, [key]: value });

  async function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    setMessage('');
    try {
      const saved = await api<ClinicProfile>('/api/v1/clinic', 'PUT', form);
      setForm(saved);
      onSaved(saved);
      setMessage('Salvo! A página pública já usa as novas informações.');
    } catch (e) { setError((e as Error).message); }
  }

  const publicUrl = `${window.location.origin}/c/${form.slug}`;

  return (
    <section>
      <h2>Clínica</h2>
      <p className="muted">Logo, cores, fotos e textos do site ficam na aba <a href="/painel/site" onClick={e => { e.preventDefault(); navigate('/painel/site'); }}>Site</a>.</p>
      <form className="card form" onSubmit={save}>
        <fieldset disabled={!owner}>
          <h3>Dados da clínica</h3>
          <label>Nome da clínica<input required maxLength={120} value={text('displayName')}
                                       onChange={e => set('displayName', e.target.value)} /></label>
          <div className="row">
            <label>Profissional responsável<input maxLength={120} value={text('professionalName')}
                                                  onChange={e => set('professionalName', e.target.value)} /></label>
            <label>CREFITO<input maxLength={30} value={text('crefito')} onChange={e => set('crefito', e.target.value)} /></label>
          </div>
          <div className="row">
            <label>WhatsApp<input inputMode="tel" maxLength={20} placeholder="(98) 99999-0000" value={text('whatsapp')}
                                  onChange={e => set('whatsapp', e.target.value)} /></label>
            <label>CPF/CNPJ (para recibos, não aparece no site)<input maxLength={18} value={text('document')}
                                                                     onChange={e => set('document', e.target.value)} /></label>
          </div>
          <label>Endereço<input maxLength={255} value={text('address')} onChange={e => set('address', e.target.value)} /></label>

          <h3>Página pública</h3>
          <label>Endereço da página
            <div className="slug"><span className="muted">{window.location.origin}/c/</span>
              <input required pattern="[a-z0-9]([a-z0-9\-]{1,61}[a-z0-9])?" value={form.slug}
                     onChange={e => set('slug', e.target.value.toLowerCase())} /></div>
          </label>
          <label className="check"><input type="checkbox" checked={form.publicPageEnabled}
                                          onChange={e => set('publicPageEnabled', e.target.checked)} />
            Página pública ativada</label>

          <h3>Pacotes</h3>
          <label className="check"><input type="checkbox" checked={form.noShowConsumesSession}
                                          onChange={e => set('noShowConsumesSession', e.target.checked)} />
            Falta sem aviso consome sessão do pacote</label>
          <p className="muted small">Vale para as faltas marcadas daqui em diante; faltas já registradas não mudam.</p>
          {profile.publicPageEnabled && <p className="small"><a href={publicUrl} target="_blank" rel="noreferrer">Abrir minha página ↗</a></p>}
        </fieldset>
        {error && <p className="error" role="alert">{error}</p>}
        {message && <p className="success" role="status">{message}</p>}
        {owner && <div className="actions"><button className="primary">Salvar</button></div>}
      </form>
    </section>
  );
}
