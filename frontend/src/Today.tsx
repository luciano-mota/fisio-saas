import { useCallback, useEffect, useState } from 'react';
import { api, whatsappLink, type Appointment, type BookingConfig, type ClinicProfile, type Patient, type PatientPackage, type Service,
  type SitePhoto } from './api';
import { AppointmentCard } from './Agenda';
import { addDays, dateOf, nowLocal, today } from './dates';
import { renewalMessage, sessionsLabel } from './messages';
import { daySummary, firstName, greeting, longDayLabel, summaryText } from './panel';
import { navigate } from './router';
import { EmptyState, Icon, PageHeader, type IconName } from './ui';

type Setup = { done: boolean; label: string; to: string };

/** Tela inicial: o dia de hoje, quem falta confirmar, pacotes acabando e, no começo, os primeiros passos. */
export function TodayTab({ owner, profile, pendingRequests }: { owner: boolean; profile: ClinicProfile | null; pendingRequests: number }) {
  const day = today();
  const [items, setItems] = useState<Appointment[] | null>(null);
  const [lowBalance, setLowBalance] = useState<PatientPackage[]>([]);
  const [setup, setSetup] = useState<Setup[]>([]);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setItems(await api<Appointment[]>(`/api/v1/appointments?from=${day}&to=${addDays(day, 1)}`));
    setLowBalance(await api<PatientPackage[]>('/api/v1/patient-packages/low-balance'));
  }, [day]);
  useEffect(() => { load().catch(e => setError((e as Error).message)); }, [load]);

  useEffect(() => {
    if (!owner || !profile) return;
    Promise.all([
      api<Service[]>('/api/v1/services'), api<SitePhoto[]>('/api/v1/clinic/photos'), api<Patient[]>('/api/v1/patients'),
      api<BookingConfig>('/api/v1/clinic/booking')
    ]).then(([services, photos, patients, booking]) => setSetup([
      { done: !!(profile.whatsapp && profile.professionalName), label: 'Preencher os dados da clínica e o WhatsApp', to: '/painel/clinica' },
      { done: services.some(s => s.active), label: 'Cadastrar os serviços que você oferece', to: '/painel/catalogo' },
      { done: !!profile.logoVersion, label: 'Enviar a logo e escolher as cores', to: '/painel/site' },
      { done: photos.length > 0, label: 'Colocar fotos no site', to: '/painel/site' },
      { done: profile.publicPageEnabled, label: 'Ativar a página pública', to: '/painel/clinica' },
      { done: booking.enabled, label: 'Cadastrar o horário de atendimento e ativar o agendamento pelo site', to: '/painel/clinica' },
      { done: patients.length > 0, label: 'Cadastrar seus pacientes', to: '/painel/pacientes/novo' }
    ])).catch(() => undefined);
  }, [owner, profile]);

  const todays = (items ?? []).filter(i => dateOf(i.startsAt) === day && i.status !== 'CANCELED');
  const tomorrowToConfirm = (items ?? []).filter(i => dateOf(i.startsAt) !== day && i.kind === 'SESSION' && i.status === 'SCHEDULED');
  const summary = daySummary(todays);
  const now = nowLocal();
  const pending = setup.filter(s => !s.done);

  return (
    <section>
      <PageHeader title={`${greeting(new Date().getHours())}${firstName(profile?.professionalName) ? `, ${firstName(profile?.professionalName)}` : ''}!`}
                  subtitle={longDayLabel(day)} />

      <div className="stats">
        <Stat icon="calendar" value={summary.sessions} label={summary.sessions === 1 ? 'sessão hoje' : 'sessões hoje'}
              onClick={() => navigate('/painel/agenda')} />
        <Stat icon="bell" value={summary.toConfirm + tomorrowToConfirm.length} label="a confirmar até amanhã"
              tone={summary.toConfirm + tomorrowToConfirm.length > 0 ? 'warn' : undefined}
              onClick={() => navigate('/painel/agenda/lembretes')} />
        <Stat icon="box" value={lowBalance.length} label={lowBalance.length === 1 ? 'pacote acabando' : 'pacotes acabando'}
              tone={lowBalance.length > 0 ? 'warn' : undefined}
              onClick={() => document.getElementById('pacotes-acabando')?.scrollIntoView({ behavior: 'smooth' })} />
      </div>

      {pendingRequests > 0 && (
        <a className="banner request-banner" href="/painel/solicitacoes"
           onClick={e => { e.preventDefault(); navigate('/painel/solicitacoes'); }}>
          <Icon name="bell" size={18} />
          <strong>{pendingRequests === 1 ? '1 pedido de horário pelo site' : `${pendingRequests} pedidos de horário pelo site`}</strong>
          <span>aguardando sua resposta</span><Icon name="right" size={18} />
        </a>
      )}

      {owner && (
        <div className="quick">
          <button className="primary" onClick={() => navigate('/painel/agenda/nova')}><Icon name="plus" size={18} /> Sessão</button>
          <button onClick={() => navigate('/painel/pacientes/novo')}><Icon name="users" size={18} /> Paciente</button>
          <button onClick={() => navigate('/painel/agenda/lembretes')}><Icon name="whatsapp" size={18} /> Lembretes de amanhã</button>
        </div>
      )}

      {owner && pending.length > 0 && (
        <div className="card setup">
          <div className="setup-head">
            <h3>Primeiros passos</h3>
            <span className="muted small">{setup.length - pending.length} de {setup.length}</span>
          </div>
          <div className="progress" aria-hidden="true"><span style={{ width: `${((setup.length - pending.length) / setup.length) * 100}%` }} /></div>
          <ul>
            {setup.map(s => (
              <li key={s.label} className={s.done ? 'done' : ''}>
                <span className="tick"><Icon name="check" size={14} /></span>
                {s.done ? <span>{s.label}</span>
                  : <a href={s.to} onClick={e => { e.preventDefault(); navigate(s.to); }}>{s.label}</a>}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="section-title">
        <h2>Hoje</h2>
        <span className="muted small">{summaryText(summary)}</span>
        <a className="see-all" href="/painel/agenda" onClick={e => { e.preventDefault(); navigate('/painel/agenda'); }}>
          Ver agenda <Icon name="right" size={16} /></a>
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      {items !== null && todays.length === 0 && (
        <EmptyState icon="calendar" title="Nenhuma sessão hoje" text="Aproveite para organizar a semana ou mandar os lembretes de amanhã." />
      )}
      <ul className="timeline">
        {todays.map(item => (
          <AppointmentCard key={item.id} item={item} owner={owner} started={item.startsAt.slice(0, 16) <= now}
                           onChanged={load} onError={setError} />
        ))}
      </ul>

      {lowBalance.length > 0 && (
        <>
          <div className="section-title" id="pacotes-acabando"><h2>Pacotes acabando</h2>
            <span className="muted small">Bom momento para oferecer a renovação</span></div>
          <ul className="cards">
            {lowBalance.map(pkg => (
              <li key={pkg.id} className="row-card">
                <span className="row-icon warn"><Icon name="box" size={22} /></span>
                <div className="grow">
                  <a className="name-link" href={`/painel/pacientes/${pkg.patientId}`}
                     onClick={e => { e.preventDefault(); navigate('/painel/pacientes/' + pkg.patientId); }}>{pkg.patientName}</a>
                  <div className="meta"><span>{pkg.serviceName} · {pkg.name}</span>
                    <span className="pill warn">{pkg.remaining <= 0 ? 'acabou' : `${sessionsLabel(pkg.remaining)} restante${pkg.remaining === 1 ? '' : 's'}`}</span></div>
                </div>
                {pkg.patientWhatsapp && (
                  <a className="button wa" href={whatsappLink(pkg.patientWhatsapp, renewalMessage(pkg.patientName, pkg))}
                     target="_blank" rel="noreferrer"><Icon name="whatsapp" size={18} /> Oferecer</a>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

function Stat({ icon, value, label, tone, onClick }: {
  icon: IconName; value: number; label: string; tone?: 'warn'; onClick: () => void;
}) {
  return (
    <button className={tone ? `stat ${tone}` : 'stat'} onClick={onClick}>
      <span className="stat-icon"><Icon name={icon} /></span>
      <strong>{value}</strong>
      <span>{label}</span>
    </button>
  );
}
