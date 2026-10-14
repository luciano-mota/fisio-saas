import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, money, whatsappLink, type Patient, type PatientPackage, type SessionPackage } from './api';
import { today } from './dates';
import { ageOn, formatCpf, formatPhone } from './format';
import { renewalMessage, sessionsLabel } from './messages';

function dateBr(iso: string): string {
  const [y, m, d] = iso.split('-');
  return `${d}/${m}/${y}`;
}

/** Ficha do paciente: dados de cadastro e pacotes com saldo (compradas, realizadas, agendadas, restantes). */
export function PatientDetail({ patientId, owner, onBack }: { patientId: string; owner: boolean; onBack: () => void }) {
  const [patient, setPatient] = useState<Patient | null>(null);
  const [packages, setPackages] = useState<PatientPackage[]>([]);
  const [catalog, setCatalog] = useState<SessionPackage[]>([]);
  const [selling, setSelling] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setPatient(await api<Patient>(`/api/v1/patients/${patientId}`));
    setPackages(await api<PatientPackage[]>(`/api/v1/patients/${patientId}/packages`));
  }, [patientId]);

  useEffect(() => {
    load().catch(e => setError((e as Error).message));
    if (owner) api<SessionPackage[]>('/api/v1/packages').then(list => setCatalog(list.filter(p => p.active))).catch(() => undefined);
  }, [load, owner]);

  async function deactivate(pkg: PatientPackage) {
    if (!confirm(`Encerrar o pacote "${pkg.name}"? Sessões já agendadas ficam avulsas; o histórico continua.`)) return;
    try {
      await api(`/api/v1/patient-packages/${pkg.id}`, 'DELETE');
      await load();
    } catch (e) { setError((e as Error).message); }
  }

  if (!patient) return <section><button className="link" onClick={onBack}>‹ Pacientes</button>{error && <p className="error">{error}</p>}</section>;

  return (
    <section>
      <button className="link back" onClick={onBack}>‹ Pacientes</button>
      <h2>{patient.fullName}{!patient.active && <span className="tag">desativado</span>}</h2>
      <p className="muted small">
        {[patient.whatsapp && formatPhone(patient.whatsapp), patient.birthDate && `${ageOn(patient.birthDate)} anos`,
          patient.cpf && `CPF ${formatCpf(patient.cpf)}`].filter(Boolean).join(' · ') || 'Sem contato cadastrado'}
      </p>
      {patient.adminNotes && <p className="small">{patient.adminNotes}</p>}

      <div className="agenda-head">
        <h3>Pacotes</h3>
        {owner && patient.active && !selling &&
          <button className="primary" onClick={() => { setError(''); setSelling(true); }}>Vender pacote</button>}
      </div>
      {owner && selling && (
        <SaleForm patientId={patient.id} catalog={catalog} onCancel={() => setSelling(false)}
                  onSold={async () => { setSelling(false); await load(); }} />
      )}
      {error && <p className="error" role="alert">{error}</p>}

      <ul className="list">
        {packages.length === 0 && <li className="muted">Nenhum pacote vendido para este paciente.</li>}
        {packages.map(pkg => (
          <li key={pkg.id} className={pkg.active ? 'item package' : 'item package inactive'}>
            <div className="grow">
              <strong>{pkg.serviceName} · {pkg.name}</strong>
              {!pkg.active && <span className="tag">encerrado</span>}
              {pkg.active && pkg.expired && <span className="status status-no_show">vencido</span>}
              <div className="meter" role="img" aria-label={`${pkg.used} de ${pkg.sessionCount} sessões usadas`}>
                <span style={{ width: `${Math.min(100, (pkg.used / pkg.sessionCount) * 100)}%` }} />
              </div>
              <div className="small">
                <strong>{pkg.remaining} {pkg.remaining === 1 ? 'restante' : 'restantes'}</strong> de {pkg.sessionCount} · {pkg.used} {pkg.used === 1 ? 'realizada' : 'realizadas'}
                {' '}· {pkg.scheduled} {pkg.scheduled === 1 ? 'agendada' : 'agendadas'}
              </div>
              <div className="muted small">
                Comprado em {dateBr(pkg.purchasedOn)}
                {pkg.expiresOn && ` · validade ${dateBr(pkg.expiresOn)}`}
                {pkg.price !== null && ` · ${money(pkg.price)}`}
                {pkg.notes && ` · ${pkg.notes}`}
              </div>
              {pkg.lowBalance && (
                <p className="notice small">
                  {pkg.remaining <= 0 ? 'As sessões deste pacote acabaram.' : `Faltam ${sessionsLabel(pkg.remaining)}.`}
                  {' '}Bom momento para oferecer a renovação.
                  {patient.whatsapp && <> <a href={whatsappLink(patient.whatsapp, renewalMessage(patient.fullName, pkg))}
                                             target="_blank" rel="noreferrer">Avisar pelo WhatsApp</a></>}
                </p>
              )}
            </div>
            {owner && pkg.active && <div className="actions"><button className="danger" onClick={() => deactivate(pkg)}>Encerrar</button></div>}
          </li>
        ))}
      </ul>
    </section>
  );
}

function SaleForm({ patientId, catalog, onCancel, onSold }: {
  patientId: string; catalog: SessionPackage[]; onCancel: () => void; onSold: () => void;
}) {
  const [packageId, setPackageId] = useState('');
  const [purchasedOn, setPurchasedOn] = useState(today());
  const [price, setPrice] = useState('');
  const [notes, setNotes] = useState('');
  const [error, setError] = useState('');

  function choose(id: string) {
    setPackageId(id);
    const item = catalog.find(p => p.id === id);
    setPrice(item?.price != null ? item.price.toFixed(2).replace('.', ',') : '');
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    const clean = price.trim().replace(/\./g, '').replace(',', '.');
    const value = clean ? Number(clean) : null;
    if (value !== null && (!Number.isFinite(value) || value < 0)) { setError('Valor inválido. Use, por exemplo, 800,00.'); return; }
    try {
      await api(`/api/v1/patients/${patientId}/packages`, 'POST', {
        packageId, purchasedOn, price: value === null ? null : Math.round(value * 100) / 100, notes: notes || null
      });
      onSold();
    } catch (e) { setError((e as Error).message); }
  }

  return (
    <form className="card form" onSubmit={submit}>
      <h3>Vender pacote</h3>
      {catalog.length === 0 && <p className="notice small">Cadastre pacotes na aba Pacotes primeiro.</p>}
      <label>Pacote
        <select required value={packageId} aria-label="Pacote" onChange={e => choose(e.target.value)}>
          <option value="">Escolha…</option>
          {catalog.map(p => (
            <option key={p.id} value={p.id}>{p.serviceName} · {p.name}{p.price !== null ? ` — ${money(p.price)}` : ''}</option>
          ))}
        </select>
      </label>
      <div className="row">
        <label>Data da compra<input type="date" required value={purchasedOn} onChange={e => setPurchasedOn(e.target.value)} /></label>
        <label>Valor combinado<input inputMode="decimal" placeholder="Ex.: 800,00" value={price}
                                     onChange={e => setPrice(e.target.value)} /></label>
      </div>
      <label>Observação (opcional)<input maxLength={500} placeholder="Ex.: pagou metade, resto no fim do mês" value={notes}
                                         onChange={e => setNotes(e.target.value)} /></label>
      <p className="muted small">Sessões já agendadas deste serviço passam a usar o pacote automaticamente.</p>
      {error && <p className="error" role="alert">{error}</p>}
      <div className="actions">
        <button className="primary" disabled={!packageId}>Confirmar venda</button>
        <button type="button" onClick={onCancel}>Voltar</button>
      </div>
    </form>
  );
}
