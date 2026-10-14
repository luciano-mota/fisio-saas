import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, money, type Service, type SessionPackage } from './api';
import { navigate } from './router';
import { SiteIcon } from './SiteIcons';
import { serviceIcon } from './siteStyle';
import { EmptyState, Icon, Menu, PageHeader, Sheet, useUi } from './ui';

export function parsePrice(text: string): number | null {
  const clean = text.trim().replace(/\./g, '').replace(',', '.');
  if (!clean) return null;
  const value = Number(clean);
  if (!Number.isFinite(value) || value < 0) throw new Error('Preço inválido. Use, por exemplo, 90,00.');
  return Math.round(value * 100) / 100;
}

function priceText(value: number | null): string {
  return value === null ? '' : value.toFixed(2).replace('.', ',');
}

/** Serviços e pacotes de sessões, numa tela só ("Catálogo"). */
export function CatalogTab({ owner, section }: { owner: boolean; section: 'servicos' | 'pacotes' }) {
  return (
    <section>
      <PageHeader title="Serviços e pacotes" subtitle="O que você oferece. Aparece no seu site; o preço é opcional." />
      <div className="segmented wide" role="tablist" aria-label="Catálogo">
        <button role="tab" aria-selected={section === 'servicos'} className={section === 'servicos' ? 'active' : ''}
                onClick={() => navigate('/painel/catalogo')}>Serviços</button>
        <button role="tab" aria-selected={section === 'pacotes'} className={section === 'pacotes' ? 'active' : ''}
                onClick={() => navigate('/painel/catalogo/pacotes')}>Pacotes</button>
      </div>
      {section === 'servicos' ? <ServicesList owner={owner} /> : <PackagesList owner={owner} />}
    </section>
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

function ServicesList({ owner }: { owner: boolean }) {
  const { toast, confirm } = useUi();
  const [items, setItems] = useState<Service[] | null>(null);
  const [form, setForm] = useState<ServiceForm | null>(null);
  const [error, setError] = useState('');
  const load = useCallback(() => api<Service[]>('/api/v1/services').then(setItems), []);
  useEffect(() => { load().catch(e => setError((e as Error).message)); }, [load]);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!form) return;
    setError('');
    try {
      const payload = {
        name: form.name, description: form.description, durationMinutes: Number(form.durationMinutes),
        price: parsePrice(form.price), showPrice: form.showPrice, onlineBookingEnabled: form.onlineBookingEnabled,
        active: true
      };
      if (form.id) await api(`/api/v1/services/${form.id}`, 'PUT', payload);
      else await api('/api/v1/services', 'POST', payload);
      toast(form.id ? 'Serviço salvo' : 'Serviço adicionado');
      setForm(null);
      await load();
    } catch (e) { setError((e as Error).message); }
  }

  async function deactivate(service: Service) {
    if (!await confirm(`Desativar "${service.name}"? Ele some do site, junto com os pacotes dele.`,
      { confirmLabel: 'Desativar', danger: true })) return;
    await api(`/api/v1/services/${service.id}`, 'DELETE');
    toast('Serviço desativado');
    await load();
  }

  const edit = (s: Service) => {
    setError('');
    setForm({
      id: s.id, name: s.name, description: s.description ?? '', durationMinutes: String(s.durationMinutes),
      price: priceText(s.price), showPrice: s.showPrice, onlineBookingEnabled: s.onlineBookingEnabled
    });
  };

  return (
    <>
      {owner && (
        <div className="list-toolbar">
          <button className="primary" onClick={() => { setError(''); setForm(EMPTY_SERVICE); }}>
            <Icon name="plus" size={18} /> Novo serviço</button>
        </div>
      )}
      {!form && error && <p className="error" role="alert">{error}</p>}
      {items?.length === 0 && (
        <EmptyState icon="tag" title="Nenhum serviço ainda" text="Cadastre o que você atende: Pilates, RPG, fisioterapia ortopédica…">
          {owner && <button className="primary" onClick={() => setForm(EMPTY_SERVICE)}>Cadastrar o primeiro serviço</button>}
        </EmptyState>
      )}
      <ul className="cards">
        {items?.map(s => (
          <li key={s.id} className={s.active ? 'row-card' : 'row-card inactive'}>
            <span className="row-icon"><SiteIcon name={serviceIcon(s.name, s.description)} size={22} /></span>
            <div className="grow">
              <strong>{s.name}</strong>
              {!s.active && <span className="pill">inativo</span>}
              <div className="meta">
                <span><Icon name="clock" size={15} /> {s.durationMinutes} min</span>
                <span>{s.price === null ? 'Sem preço' : money(s.price)}</span>
                {s.price !== null && !s.showPrice && <span className="pill">oculto no site</span>}
                {!s.onlineBookingEnabled && <span className="pill">sem agendamento online</span>}
              </div>
            </div>
            {owner && s.active && (
              <Menu label={`Ações de ${s.name}`} items={[
                { label: 'Editar', icon: 'edit', onClick: () => edit(s) },
                { label: 'Desativar', icon: 'trash', danger: true, onClick: () => deactivate(s) }
              ]} />
            )}
          </li>
        ))}
      </ul>

      {form && (
        <Sheet title={form.id ? 'Editar serviço' : 'Novo serviço'} onClose={() => setForm(null)}>
          <form className="form" onSubmit={save}>
            <label>Nome<input required maxLength={120} value={form.name} placeholder="Ex.: Pilates, RPG, Fisioterapia ortopédica"
                              onChange={e => setForm({ ...form, name: e.target.value })} /></label>
            <label>Descrição curta<textarea maxLength={600} value={form.description}
                                            placeholder="Aparece no site, abaixo do nome."
                                            onChange={e => setForm({ ...form, description: e.target.value })} /></label>
            <div className="row">
              <label>Duração (min)<input type="number" min={5} max={480} required value={form.durationMinutes}
                                         onChange={e => setForm({ ...form, durationMinutes: e.target.value })} /></label>
              <label>Preço da sessão (opcional)<input inputMode="decimal" placeholder="Ex.: 90,00" value={form.price}
                                                      onChange={e => setForm({ ...form, price: e.target.value })} /></label>
            </div>
            <label className="switch"><input type="checkbox" checked={form.showPrice}
                                             onChange={e => setForm({ ...form, showPrice: e.target.checked })} />
              <span>Mostrar preço na página pública</span></label>
            <label className="switch"><input type="checkbox" checked={form.onlineBookingEnabled}
                                             onChange={e => setForm({ ...form, onlineBookingEnabled: e.target.checked })} />
              <span>Permitir agendamento online</span></label>
            {error && <p className="error" role="alert">{error}</p>}
            <div className="sheet-actions">
              <button type="button" onClick={() => setForm(null)}>Cancelar</button>
              <button className="primary">{form.id ? 'Salvar alterações' : 'Adicionar serviço'}</button>
            </div>
          </form>
        </Sheet>
      )}
    </>
  );
}

// ---------- Pacotes ----------

type PackageForm = {
  id?: string; serviceId: string; name: string; sessionCount: string; price: string;
  showPrice: boolean; validityDays: string;
};
const EMPTY_PACKAGE: PackageForm = { serviceId: '', name: '', sessionCount: '10', price: '', showPrice: true, validityDays: '' };

function PackagesList({ owner }: { owner: boolean }) {
  const { toast, confirm } = useUi();
  const [items, setItems] = useState<SessionPackage[] | null>(null);
  const [services, setServices] = useState<Service[]>([]);
  const [form, setForm] = useState<PackageForm | null>(null);
  const [error, setError] = useState('');
  const load = useCallback(async () => {
    setItems(await api<SessionPackage[]>('/api/v1/packages'));
    setServices((await api<Service[]>('/api/v1/services')).filter(s => s.active));
  }, []);
  useEffect(() => { load().catch(e => setError((e as Error).message)); }, [load]);

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!form) return;
    setError('');
    try {
      const payload = {
        serviceId: form.serviceId, name: form.name, sessionCount: Number(form.sessionCount),
        price: parsePrice(form.price), showPrice: form.showPrice,
        validityDays: form.validityDays ? Number(form.validityDays) : null, active: true
      };
      if (form.id) await api(`/api/v1/packages/${form.id}`, 'PUT', payload);
      else await api('/api/v1/packages', 'POST', payload);
      toast(form.id ? 'Pacote salvo' : 'Pacote adicionado');
      setForm(null);
      await load();
    } catch (e) { setError((e as Error).message); }
  }

  async function deactivate(item: SessionPackage) {
    if (!await confirm(`Desativar o pacote "${item.name}"? Quem já comprou continua com o saldo.`,
      { confirmLabel: 'Desativar', danger: true })) return;
    await api(`/api/v1/packages/${item.id}`, 'DELETE');
    toast('Pacote desativado');
    await load();
  }

  const newPackage = () => { setError(''); setForm({ ...EMPTY_PACKAGE, serviceId: services[0]?.id ?? '' }); };

  return (
    <>
      {owner && (
        <div className="list-toolbar">
          <button className="primary" disabled={services.length === 0} onClick={newPackage}>
            <Icon name="plus" size={18} /> Novo pacote</button>
          {services.length === 0 && items !== null && <span className="muted small">Cadastre um serviço antes.</span>}
        </div>
      )}
      {!form && error && <p className="error" role="alert">{error}</p>}
      {items?.length === 0 && (
        <EmptyState icon="box" title="Nenhum pacote ainda" text="Ex.: 10 sessões de Pilates. O paciente compra e o saldo é controlado na agenda." />
      )}
      <ul className="cards">
        {items?.map(p => (
          <li key={p.id} className={p.active ? 'row-card' : 'row-card inactive'}>
            <span className="row-icon"><Icon name="box" size={22} /></span>
            <div className="grow">
              <strong>{p.name}</strong> <span className="muted">· {p.serviceName}</span>
              {!p.active && <span className="pill">inativo</span>}
              <div className="meta">
                <span>{p.sessionCount} sessões</span>
                <span>{p.price === null ? 'Sem preço' : money(p.price)}
                  {p.price !== null && p.sessionCount > 1 && ` (${money(p.price / p.sessionCount)}/sessão)`}</span>
                {p.validityDays && <span>validade {p.validityDays} dias</span>}
                {p.price !== null && !p.showPrice && <span className="pill">oculto no site</span>}
              </div>
            </div>
            {owner && p.active && (
              <Menu label={`Ações de ${p.name}`} items={[
                { label: 'Editar', icon: 'edit', onClick: () => { setError(''); setForm({
                  id: p.id, serviceId: p.serviceId, name: p.name, sessionCount: String(p.sessionCount),
                  price: priceText(p.price), showPrice: p.showPrice, validityDays: p.validityDays ? String(p.validityDays) : ''
                }); } },
                { label: 'Desativar', icon: 'trash', danger: true, onClick: () => deactivate(p) }
              ]} />
            )}
          </li>
        ))}
      </ul>

      {form && (
        <Sheet title={form.id ? 'Editar pacote' : 'Novo pacote'} onClose={() => setForm(null)}>
          <form className="form" onSubmit={save}>
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
            </div>
            <label>Validade em dias (opcional)<input type="number" min={1} max={730} value={form.validityDays}
                                                     onChange={e => setForm({ ...form, validityDays: e.target.value })} /></label>
            <label className="switch"><input type="checkbox" checked={form.showPrice}
                                             onChange={e => setForm({ ...form, showPrice: e.target.checked })} />
              <span>Mostrar preço na página pública</span></label>
            {error && <p className="error" role="alert">{error}</p>}
            <div className="sheet-actions">
              <button type="button" onClick={() => setForm(null)}>Cancelar</button>
              <button className="primary">{form.id ? 'Salvar alterações' : 'Adicionar pacote'}</button>
            </div>
          </form>
        </Sheet>
      )}
    </>
  );
}
