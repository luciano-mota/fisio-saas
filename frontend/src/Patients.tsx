import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, whatsappLink, type Patient } from './api';
import { ageOn, formatCpf, formatPhone, isValidCpf, maskCpf, maskPhone } from './format';
import { BirthdaysView } from './Birthdays';
import { PatientDetail } from './PatientDetail';
import { navigate } from './router';
import { Avatar, EmptyState, Icon, Menu, PageHeader, Sheet, useUi } from './ui';

export type PatientForm = {
  id?: string; fullName: string; cpf: string; whatsapp: string; birthDate: string; adminNotes: string;
};
const EMPTY: PatientForm = { fullName: '', cpf: '', whatsapp: '', birthDate: '', adminNotes: '' };

export function toForm(p: Patient): PatientForm {
  return {
    id: p.id, fullName: p.fullName, cpf: formatCpf(p.cpf), whatsapp: formatPhone(p.whatsapp),
    birthDate: p.birthDate ?? '', adminNotes: p.adminNotes ?? ''
  };
}

/** Cadastro de pacientes: só dados administrativos (sem dados clínicos nesta fase). {@code patientId} "novo" abre o cadastro. */
export function PatientsTab({ owner, patientId, clinicName }: { owner: boolean; patientId?: string; clinicName: string }) {
  const { toast, confirm } = useUi();
  const [items, setItems] = useState<Patient[] | null>(null);
  const [query, setQuery] = useState('');
  const [showInactive, setShowInactive] = useState(false);
  const [form, setForm] = useState<PatientForm | null>(null);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    const params = new URLSearchParams({ q: query, includeInactive: String(showInactive) });
    setItems(await api<Patient[]>('/api/v1/patients?' + params));
  }, [query, showInactive]);

  // Busca enquanto digita, com pequena espera para não disparar a cada tecla.
  useEffect(() => {
    const timer = setTimeout(() => { load().catch(e => setError((e as Error).message)); }, 250);
    return () => clearTimeout(timer);
  }, [load]);

  // Atalho /painel/pacientes/novo (tela Hoje e agenda).
  useEffect(() => {
    if (patientId !== 'novo') return;
    if (owner) { setError(''); setForm(EMPTY); }
    navigate('/painel/pacientes');
  }, [patientId, owner]);

  async function deactivate(p: Patient) {
    if (!await confirm(`Desativar ${p.fullName}? O histórico continua guardado e você pode reativar depois.`,
      { confirmLabel: 'Desativar', danger: true })) return;
    await api(`/api/v1/patients/${p.id}`, 'DELETE');
    toast('Paciente desativado');
    await load();
  }

  async function reactivate(p: Patient) {
    await api(`/api/v1/patients/${p.id}`, 'PUT', { ...p, active: true });
    toast('Paciente reativado');
    await load();
  }

  const tabs = (
    <div className="segmented wide" role="tablist" aria-label="Pacientes">
      <button role="tab" aria-selected={patientId !== 'aniversariantes'} className={patientId !== 'aniversariantes' ? 'active' : ''}
              onClick={() => navigate('/painel/pacientes')}>Pacientes</button>
      <button role="tab" aria-selected={patientId === 'aniversariantes'} className={patientId === 'aniversariantes' ? 'active' : ''}
              onClick={() => navigate('/painel/pacientes/aniversariantes')}>🎂 Aniversariantes</button>
    </div>
  );

  if (patientId === 'aniversariantes') {
    return (
      <section>
        <PageHeader title="Pacientes" subtitle="Aniversariantes, inclusive desativados: uma boa hora para chamar de volta." />
        {tabs}
        <BirthdaysView owner={owner} clinicName={clinicName} />
      </section>
    );
  }

  if (patientId && patientId !== 'novo') {
    return <PatientDetail patientId={patientId} owner={owner} onBack={() => navigate('/painel/pacientes')} />;
  }

  return (
    <section>
      <PageHeader title="Pacientes" subtitle="Só dados de cadastro. Anotações clínicas ainda não são guardadas aqui.">
        {owner && <button className="primary" onClick={() => { setError(''); setForm(EMPTY); }}>
          <Icon name="plus" size={18} /> Paciente</button>}
      </PageHeader>
      {tabs}

      <div className="search">
        <Icon name="search" size={18} />
        <input type="search" placeholder="Buscar por nome" aria-label="Buscar paciente por nome"
               value={query} onChange={e => setQuery(e.target.value)} />
      </div>
      <label className="switch small"><input type="checkbox" checked={showInactive}
                                             onChange={e => setShowInactive(e.target.checked)} /><span>Mostrar desativados</span></label>
      {!form && error && <p className="error" role="alert">{error}</p>}

      {items?.length === 0 && (query
        ? <EmptyState icon="search" title="Nenhum paciente encontrado" text="Confira o nome ou busque só pelo primeiro nome." />
        : <EmptyState icon="users" title="Nenhum paciente ainda" text="Cadastre nome e WhatsApp para agendar e mandar lembretes.">
            {owner && <button className="primary" onClick={() => setForm(EMPTY)}>Cadastrar o primeiro paciente</button>}
          </EmptyState>)}

      <ul className="cards">
        {items?.map(p => (
          <li key={p.id} className={p.active ? 'row-card clickable' : 'row-card clickable inactive'}>
            <Avatar name={p.fullName} />
            <a className="grow row-link" href={`/painel/pacientes/${p.id}`}
               onClick={e => { e.preventDefault(); navigate('/painel/pacientes/' + p.id); }}>
              <strong>{p.fullName}</strong>{!p.active && <span className="pill">desativado</span>}
              <span className="meta">
                {[p.whatsapp && formatPhone(p.whatsapp), p.birthDate && `${ageOn(p.birthDate)} anos`]
                  .filter(Boolean).join(' · ') || 'Sem contato cadastrado'}
              </span>
            </a>
            {p.whatsapp && (
              <a className="icon-button wa" aria-label={`WhatsApp de ${p.fullName}`}
                 href={whatsappLink(p.whatsapp, `Olá, ${p.fullName.split(' ')[0]}!`)} target="_blank" rel="noreferrer">
                <Icon name="whatsapp" /></a>
            )}
            {owner && (
              <Menu label={`Ações de ${p.fullName}`} items={[
                p.active && { label: 'Editar', icon: 'edit', onClick: () => { setError(''); setForm(toForm(p)); } },
                p.active && { label: 'Desativar', icon: 'trash', danger: true, onClick: () => deactivate(p) },
                !p.active && { label: 'Reativar', icon: 'repeat', onClick: () => reactivate(p) }
              ]} />
            )}
          </li>
        ))}
      </ul>

      {form && (
        <PatientSheet form={form} setForm={setForm} error={error} setError={setError}
                      onSaved={async () => { toast(form.id ? 'Paciente salvo' : 'Paciente cadastrado'); setForm(null); await load(); }} />
      )}
    </section>
  );
}

/** Cadastro/edição do paciente na gaveta (usado na lista e na ficha). */
export function PatientSheet({ form, setForm, error, setError, onSaved }: {
  form: PatientForm; setForm: (f: PatientForm | null) => void; error: string; setError: (e: string) => void;
  onSaved: () => void;
}) {
  /** Mexeu no formulário: a mensagem de erro antiga some. */
  const edit = (patch: Partial<PatientForm>) => { setForm({ ...form, ...patch }); if (error) setError(''); };

  async function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    if (!form.cpf.trim()) { setError('Informe o CPF do paciente.'); return; }
    if (!isValidCpf(form.cpf)) { setError('CPF inválido. Confira os números.'); return; }
    try {
      const payload = {
        fullName: form.fullName, cpf: form.cpf || null, whatsapp: form.whatsapp || null,
        birthDate: form.birthDate || null, adminNotes: form.adminNotes || null
      };
      if (form.id) await api(`/api/v1/patients/${form.id}`, 'PUT', payload);
      else await api('/api/v1/patients', 'POST', payload);
      onSaved();
    } catch (e) { setError((e as Error).message); }
  }

  return (
    <Sheet title={form.id ? 'Editar paciente' : 'Novo paciente'} onClose={() => { setForm(null); setError(''); }}>
      <form className="form" onSubmit={save}>
        <label>Nome completo<input required maxLength={120} value={form.fullName}
                                   onChange={e => edit({ fullName: e.target.value })} /></label>
        <div className="row">
          <label>WhatsApp<input inputMode="tel" maxLength={20} placeholder="(98) 99999-0000" value={form.whatsapp}
                                onChange={e => edit({ whatsapp: maskPhone(e.target.value) })} /></label>
          <label>Nascimento<input type="date" value={form.birthDate} max={new Date().toISOString().slice(0, 10)}
                                  onChange={e => edit({ birthDate: e.target.value })} /></label>
        </div>
        <label>CPF<input required inputMode="numeric" maxLength={14} placeholder="000.000.000-00"
                         value={form.cpf} onChange={e => edit({ cpf: maskCpf(e.target.value) })} /></label>
        <p className="muted small">O CPF evita paciente duplicado (inclusive nos pedidos pelo site) e vai no recibo do IR.</p>
        <label>Observações administrativas
          <textarea maxLength={1000} placeholder="Ex.: prefere horários pela manhã; atendimento em casa"
                    value={form.adminNotes} onChange={e => edit({ adminNotes: e.target.value })} />
        </label>
        {error && <p className="error" role="alert">{error}</p>}
        <div className="sheet-actions">
          <button type="button" onClick={() => { setForm(null); setError(''); }}>Cancelar</button>
          <button className="primary">{form.id ? 'Salvar alterações' : 'Cadastrar paciente'}</button>
        </div>
      </form>
    </Sheet>
  );
}
