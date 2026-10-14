import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, whatsappLink, type Patient } from './api';
import { ageOn, formatCpf, formatPhone, isValidCpf } from './format';
import { PatientDetail } from './PatientDetail';
import { navigate } from './router';

type PatientForm = {
  id?: string; fullName: string; cpf: string; whatsapp: string; birthDate: string; adminNotes: string;
};
const EMPTY: PatientForm = { fullName: '', cpf: '', whatsapp: '', birthDate: '', adminNotes: '' };

function toForm(p: Patient): PatientForm {
  return {
    id: p.id, fullName: p.fullName, cpf: formatCpf(p.cpf), whatsapp: formatPhone(p.whatsapp),
    birthDate: p.birthDate ?? '', adminNotes: p.adminNotes ?? ''
  };
}

/** Cadastro de pacientes: só dados administrativos (sem dados clínicos nesta fase). */
export function PatientsTab({ owner, patientId }: { owner: boolean; patientId?: string }) {
  const [items, setItems] = useState<Patient[]>([]);
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

  async function save(event: FormEvent) {
    event.preventDefault();
    if (!form) return;
    setError('');
    if (form.cpf.trim() && !isValidCpf(form.cpf)) { setError('CPF inválido. Confira os números.'); return; }
    try {
      const payload = {
        fullName: form.fullName, cpf: form.cpf || null, whatsapp: form.whatsapp || null,
        birthDate: form.birthDate || null, adminNotes: form.adminNotes || null
      };
      if (form.id) await api(`/api/v1/patients/${form.id}`, 'PUT', payload);
      else await api('/api/v1/patients', 'POST', payload);
      setForm(null);
      await load();
    } catch (e) { setError((e as Error).message); }
  }

  async function deactivate(p: Patient) {
    if (!confirm(`Desativar ${p.fullName}? O histórico continua guardado e você pode reativar depois.`)) return;
    await api(`/api/v1/patients/${p.id}`, 'DELETE');
    await load();
  }

  async function reactivate(p: Patient) {
    await api(`/api/v1/patients/${p.id}`, 'PUT', { ...p, active: true });
    await load();
  }

  if (patientId) return <PatientDetail patientId={patientId} owner={owner} onBack={() => navigate('/painel/pacientes')} />;

  return (
    <section>
      <h2>Pacientes</h2>
      <p className="muted">Só dados de cadastro. Anotações clínicas ainda não são guardadas aqui.</p>

      <div className="toolbar">
        <input type="search" placeholder="Buscar por nome" aria-label="Buscar paciente por nome"
               value={query} onChange={e => setQuery(e.target.value)} />
        {owner && !form && <button className="primary" onClick={() => { setError(''); setForm(EMPTY); }}>+ Paciente</button>}
      </div>
      <label className="check small"><input type="checkbox" checked={showInactive}
                                            onChange={e => setShowInactive(e.target.checked)} /> Mostrar desativados</label>

      {owner && form && (
        <form className="card form" onSubmit={save}>
          <h3>{form.id ? 'Editar paciente' : 'Novo paciente'}</h3>
          <label>Nome completo<input required maxLength={120} value={form.fullName} autoFocus
                                     onChange={e => setForm({ ...form, fullName: e.target.value })} /></label>
          <div className="row">
            <label>WhatsApp<input inputMode="tel" maxLength={20} placeholder="(98) 99999-0000" value={form.whatsapp}
                                  onChange={e => setForm({ ...form, whatsapp: e.target.value })} /></label>
            <label>Nascimento<input type="date" value={form.birthDate} max={new Date().toISOString().slice(0, 10)}
                                    onChange={e => setForm({ ...form, birthDate: e.target.value })} /></label>
          </div>
          <label>CPF (opcional, para recibo)<input inputMode="numeric" maxLength={14} placeholder="000.000.000-00"
                                                   value={form.cpf} onChange={e => setForm({ ...form, cpf: e.target.value })} /></label>
          <label>Observações administrativas
            <textarea maxLength={1000} placeholder="Ex.: prefere horários pela manhã; atendimento em casa"
                      value={form.adminNotes} onChange={e => setForm({ ...form, adminNotes: e.target.value })} />
          </label>
          {error && <p className="error" role="alert">{error}</p>}
          <div className="actions">
            <button className="primary">{form.id ? 'Salvar alterações' : 'Cadastrar paciente'}</button>
            <button type="button" onClick={() => { setForm(null); setError(''); }}>Cancelar</button>
          </div>
        </form>
      )}
      {!form && error && <p className="error" role="alert">{error}</p>}

      <ul className="list">
        {items.length === 0 && (
          <li className="muted">{query ? 'Nenhum paciente encontrado.' : 'Nenhum paciente cadastrado ainda.'}</li>
        )}
        {items.map(p => (
          <li key={p.id} className={p.active ? 'item' : 'item inactive'}>
            <div>
              <button className="link name" onClick={() => navigate('/painel/pacientes/' + p.id)}>{p.fullName}</button>
              {!p.active && <span className="tag">desativado</span>}
              <div className="muted small">
                {[p.whatsapp && formatPhone(p.whatsapp), p.birthDate && `${ageOn(p.birthDate)} anos`,
                  p.cpf && `CPF ${formatCpf(p.cpf)}`].filter(Boolean).join(' · ') || 'Sem contato cadastrado'}
              </div>
              {p.adminNotes && <div className="small">{p.adminNotes}</div>}
            </div>
            <div className="actions">
              {p.whatsapp && (
                <a className="button" href={whatsappLink(p.whatsapp, `Olá, ${p.fullName.split(' ')[0]}!`)}
                   target="_blank" rel="noreferrer">WhatsApp</a>
              )}
              {owner && p.active && <button onClick={() => { setError(''); setForm(toForm(p)); }}>Editar</button>}
              {owner && p.active && <button className="danger" onClick={() => deactivate(p)}>Desativar</button>}
              {owner && !p.active && <button onClick={() => reactivate(p)}>Reativar</button>}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
