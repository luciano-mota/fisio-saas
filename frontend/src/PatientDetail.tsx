import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, money, whatsappLink, type Patient, type PatientPackage, type SessionPackage } from './api';
import { today } from './dates';
import { ageOn, formatCpf, formatPhone } from './format';
import { renewalMessage, sessionsLabel } from './messages';
import { PatientSheet, toForm, type PatientForm } from './Patients';
import { Avatar, EmptyState, Icon, Menu, Sheet, useUi } from './ui';

function dateBr(iso: string): string {
  const [y, m, d] = iso.split('-');
  return `${d}/${m}/${y}`;
}

/** Ficha do paciente: dados de cadastro e pacotes com saldo (compradas, realizadas, agendadas, restantes). */
export function PatientDetail({ patientId, owner, onBack }: { patientId: string; owner: boolean; onBack: () => void }) {
  const { toast, confirm } = useUi();
  const [patient, setPatient] = useState<Patient | null>(null);
  const [packages, setPackages] = useState<PatientPackage[]>([]);
  const [catalog, setCatalog] = useState<SessionPackage[]>([]);
  const [selling, setSelling] = useState(false);
  const [editing, setEditing] = useState<PatientForm | null>(null);
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
    if (!await confirm(`Encerrar o pacote "${pkg.name}"? Sessões já agendadas ficam avulsas; o histórico continua.`,
      { confirmLabel: 'Encerrar pacote', danger: true })) return;
    try {
      await api(`/api/v1/patient-packages/${pkg.id}`, 'DELETE');
      toast('Pacote encerrado');
      await load();
    } catch (e) { setError((e as Error).message); }
  }

  const back = <button className="link-button back" onClick={onBack}><Icon name="left" size={18} /> Pacientes</button>;
  if (!patient) return <section>{back}{error && <p className="error">{error}</p>}</section>;

  const contact = [patient.birthDate && `${ageOn(patient.birthDate)} anos`, patient.cpf && `CPF ${formatCpf(patient.cpf)}`]
    .filter(Boolean).join(' · ');

  return (
    <section>
      {back}
      <div className="card profile">
        <Avatar name={patient.fullName} />
        <div className="grow">
          <h1>{patient.fullName}{!patient.active && <span className="pill">desativado</span>}</h1>
          <p className="muted">{[patient.whatsapp && formatPhone(patient.whatsapp), contact].filter(Boolean).join(' · ') || 'Sem contato cadastrado'}</p>
          {patient.adminNotes && <p className="small">{patient.adminNotes}</p>}
        </div>
        <div className="profile-actions">
          {patient.whatsapp && (
            <a className="button wa" href={whatsappLink(patient.whatsapp, `Olá, ${patient.fullName.split(' ')[0]}!`)}
               target="_blank" rel="noreferrer"><Icon name="whatsapp" size={18} /> WhatsApp</a>
          )}
          {owner && patient.active && <button onClick={() => { setError(''); setEditing(toForm(patient)); }}><Icon name="edit" size={18} /> Editar</button>}
        </div>
      </div>

      <div className="section-title">
        <h2>Pacotes</h2>
        {owner && patient.active &&
          <button className="primary" onClick={() => { setError(''); setSelling(true); }}><Icon name="plus" size={18} /> Vender pacote</button>}
      </div>
      {error && !selling && !editing && <p className="error" role="alert">{error}</p>}

      {packages.length === 0 && <EmptyState icon="box" title="Nenhum pacote" text="Venda um pacote para controlar o saldo de sessões automaticamente." />}
      <ul className="cards">
        {packages.map(pkg => (
          <li key={pkg.id} className={pkg.active ? 'package-card' : 'package-card inactive'}>
            <div className="package-head">
              <div className="grow">
                <strong>{pkg.serviceName} · {pkg.name}</strong>
                {!pkg.active && <span className="pill">encerrado</span>}
                {pkg.active && pkg.expired && <span className="pill warn">vencido</span>}
              </div>
              {owner && pkg.active && (
                <Menu label={`Ações do pacote ${pkg.name}`} items={[
                  { label: 'Encerrar pacote', icon: 'trash', danger: true, onClick: () => deactivate(pkg) }
                ]} />
              )}
            </div>
            <div className="balance">
              <strong className={pkg.lowBalance ? 'warn' : ''}>{pkg.remaining}</strong>
              <span>{pkg.remaining === 1 ? 'restante' : 'restantes'} de {pkg.sessionCount}</span>
            </div>
            <div className="meter" role="img" aria-label={`${pkg.used} de ${pkg.sessionCount} sessões usadas`}>
              <span style={{ width: `${Math.min(100, (pkg.used / pkg.sessionCount) * 100)}%` }} />
            </div>
            <div className="meta">
              <span>{pkg.used} {pkg.used === 1 ? 'realizada' : 'realizadas'}</span>
              <span>{pkg.scheduled} {pkg.scheduled === 1 ? 'agendada' : 'agendadas'}</span>
              <span>comprado em {dateBr(pkg.purchasedOn)}</span>
              {pkg.expiresOn && <span>validade {dateBr(pkg.expiresOn)}</span>}
              {pkg.price !== null && <span>{money(pkg.price)}</span>}
            </div>
            {pkg.notes && <p className="small">{pkg.notes}</p>}
            {pkg.lowBalance && (
              <p className="notice small">
                {pkg.remaining <= 0 ? 'As sessões deste pacote acabaram.' : `Faltam ${sessionsLabel(pkg.remaining)}.`}
                {' '}Bom momento para oferecer a renovação.
                {patient.whatsapp && <> <a href={whatsappLink(patient.whatsapp, renewalMessage(patient.fullName, pkg))}
                                           target="_blank" rel="noreferrer">Avisar pelo WhatsApp</a></>}
              </p>
            )}
          </li>
        ))}
      </ul>

      {owner && selling && (
        <SaleSheet patientId={patient.id} catalog={catalog} onCancel={() => setSelling(false)}
                   onSold={async () => { setSelling(false); toast('Pacote vendido'); await load(); }} />
      )}
      {editing && (
        <PatientSheet form={editing} setForm={setEditing} error={error} setError={setError}
                      onSaved={async () => { toast('Paciente salvo'); setEditing(null); await load(); }} />
      )}
    </section>
  );
}

function SaleSheet({ patientId, catalog, onCancel, onSold }: {
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
    <Sheet title="Vender pacote" onClose={onCancel}>
      <form className="form" onSubmit={submit}>
        {catalog.length === 0 && <p className="notice small">Cadastre pacotes em Serviços e pacotes primeiro.</p>}
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
        <div className="sheet-actions">
          <button type="button" onClick={onCancel}>Cancelar</button>
          <button className="primary" disabled={!packageId}>Confirmar venda</button>
        </div>
      </form>
    </Sheet>
  );
}
