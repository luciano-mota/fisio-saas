import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { api, ApiError, whatsappLink, type Appointment, type AppointmentStatus, type Patient, type PatientPackage,
  type Service } from './api';
import { addDays, dateOf, dayLabel, minutesBetween, nowLocal, timeOf, today, weekStart } from './dates';
import { confirmationMessage, renewalMessage, sessionsLabel } from './messages';
import { isoWeekday, MAX_OCCURRENCES, seriesDates, WEEKDAYS } from './recurrence';
import { navigate } from './router';

export const STATUS_LABEL: Record<AppointmentStatus, string> = {
  SCHEDULED: 'Agendada', CONFIRMED: 'Confirmada', DONE: 'Realizada', NO_SHOW: 'Falta', CANCELED: 'Cancelada'
};

type View = 'dia' | 'semana';
/** Repetição opcional (ex.: seg/qua/sex por 5 semanas). */
type Repeat = { repeat: boolean; weekdays: number[]; limitMode: 'semanas' | 'vezes'; limit: string };
type Form =
  | ({ mode: 'sessao'; patientId: string; serviceId: string; date: string; time: string; duration: string; notes: string } & Repeat)
  | ({ mode: 'bloqueio'; date: string; time: string; endTime: string; notes: string } & Repeat)
  | { mode: 'remarcar'; item: Appointment; date: string; time: string; duration: string; notes: string };

/** Agenda do dia ou da semana, pensada para o celular. */
export function AgendaTab({ owner }: { owner: boolean }) {
  const [view, setView] = useState<View>('dia');
  const [date, setDate] = useState(today());
  const [items, setItems] = useState<Appointment[]>([]);
  const [showCanceled, setShowCanceled] = useState(false);
  const [patients, setPatients] = useState<Patient[]>([]);
  const [services, setServices] = useState<Service[]>([]);
  const [form, setForm] = useState<Form | null>(null);
  const [error, setError] = useState('');
  const [conflicts, setConflicts] = useState<string[]>([]);
  const [lowBalance, setLowBalance] = useState<PatientPackage[]>([]);
  const [reminders, setReminders] = useState(false);

  const from = view === 'dia' ? date : weekStart(date);
  const to = view === 'dia' ? date : addDays(from, 6);

  const load = useCallback(async () => {
    const params = new URLSearchParams({ from, to, includeCanceled: String(showCanceled) });
    setItems(await api<Appointment[]>('/api/v1/appointments?' + params));
    setLowBalance(await api<PatientPackage[]>('/api/v1/patient-packages/low-balance'));
  }, [from, to, showCanceled]);

  useEffect(() => { load().catch(e => setError((e as Error).message)); }, [load]);
  useEffect(() => {
    if (!owner) return;
    api<Patient[]>('/api/v1/patients').then(setPatients).catch(() => undefined);
    api<Service[]>('/api/v1/services').then(list => setServices(list.filter(s => s.active))).catch(() => undefined);
  }, [owner]);

  async function run(action: () => Promise<unknown>) {
    setError('');
    setConflicts([]);
    try {
      await action();
      await load();
      return true;
    } catch (e) {
      setError((e as Error).message);
      if (e instanceof ApiError) setConflicts(e.conflicts);
      return false;
    }
  }

  const changeStatus = (item: Appointment, status: AppointmentStatus) =>
    run(() => api(`/api/v1/appointments/${item.id}/status`, 'POST', { status }));

  async function cancelUpcoming(item: Appointment) {
    const what = item.kind === 'BLOCK' ? 'os próximos bloqueios desta série' : `as próximas sessões de ${item.patientName} desta série`;
    if (confirm(`Cancelar ${what}, a partir de ${dayLabel(item.startsAt)}? O que já foi realizado não muda.`)) {
      await run(() => api(`/api/v1/appointments/series/${item.seriesId}?from=${dateOf(item.startsAt)}`, 'DELETE'));
    }
  }

  async function cancel(item: Appointment) {
    const what = item.kind === 'BLOCK' ? 'Remover este bloqueio de horário?' : `Cancelar a sessão de ${item.patientName}?`;
    if (confirm(what)) await run(() => api(`/api/v1/appointments/${item.id}`, 'DELETE'));
  }

  async function save(event: FormEvent | null, skipConflicts = false) {
    event?.preventDefault();
    if (!form) return;
    const startsAt = `${form.date}T${form.time}`;
    const ok = await run(() => {
      if (form.mode !== 'remarcar' && form.repeat) {
        const block = form.mode === 'bloqueio';
        return api('/api/v1/appointments/series', 'POST', {
          kind: block ? 'BLOCK' : 'SESSION',
          patientId: block ? null : form.patientId, serviceId: block ? null : form.serviceId,
          startDate: form.date, startTime: form.time,
          durationMinutes: block ? minutesBetween(startsAt, `${form.date}T${form.endTime}`)
            : form.duration ? Number(form.duration) : null,
          weekdays: form.weekdays,
          weeks: form.limitMode === 'semanas' ? Number(form.limit) : null,
          occurrences: form.limitMode === 'vezes' ? Number(form.limit) : null,
          notes: form.notes || null, skipConflicts
        });
      }
      if (form.mode === 'sessao') {
        return api('/api/v1/appointments', 'POST', {
          patientId: form.patientId, serviceId: form.serviceId, startsAt,
          durationMinutes: form.duration ? Number(form.duration) : null, notes: form.notes || null
        });
      }
      if (form.mode === 'bloqueio') {
        return api('/api/v1/blocks', 'POST', { startsAt, endsAt: `${form.date}T${form.endTime}`, notes: form.notes || null });
      }
      return api(`/api/v1/appointments/${form.item.id}`, 'PUT', {
        startsAt, durationMinutes: Number(form.duration), notes: form.notes || null
      });
    });
    if (ok) {
      setForm(null);
      if (dateOf(startsAt) !== date && view === 'dia') setDate(dateOf(startsAt));
    }
  }

  const days = useMemo(() => {
    const list = view === 'dia' ? [date] : Array.from({ length: 7 }, (_, i) => addDays(from, i));
    return list.map(day => ({ day, items: items.filter(i => dateOf(i.startsAt) === day) }));
  }, [view, date, from, items]);

  const step = view === 'dia' ? 1 : 7;
  const noRepeat = (day: string): Repeat => ({ repeat: false, weekdays: [isoWeekday(day)], limitMode: 'semanas', limit: '4' });
  const now = nowLocal();

  return (
    <section>
      <div className="agenda-head">
        <h2>Agenda</h2>
        <div className="segmented" role="group" aria-label="Visualização">
          <button className={view === 'dia' ? 'active' : ''} onClick={() => setView('dia')}>Dia</button>
          <button className={view === 'semana' ? 'active' : ''} onClick={() => setView('semana')}>Semana</button>
        </div>
      </div>

      <div className="date-nav">
        <button aria-label="Anterior" onClick={() => setDate(addDays(date, -step))}>‹</button>
        <strong>{view === 'dia' ? dayLabel(date) : `${dayLabel(from)} a ${dayLabel(to)}`}</strong>
        <button aria-label="Próximo" onClick={() => setDate(addDays(date, step))}>›</button>
        {date !== today() && <button onClick={() => setDate(today())}>Hoje</button>}
      </div>

      {lowBalance.length > 0 && (
        <details className="notice small low-balance">
          <summary>{lowBalance.length === 1 ? '1 pacote acabando' : `${lowBalance.length} pacotes acabando`} — hora de oferecer renovação</summary>
          <ul>
            {lowBalance.map(pkg => (
              <li key={pkg.id}>
                <button className="link" onClick={() => navigate('/painel/pacientes/' + pkg.patientId)}>{pkg.patientName}</button>
                {' '}· {pkg.serviceName} · {pkg.remaining <= 0 ? 'acabou' : `${sessionsLabel(pkg.remaining)} restante${pkg.remaining === 1 ? '' : 's'}`}
                {pkg.patientWhatsapp && <> · <a href={whatsappLink(pkg.patientWhatsapp, renewalMessage(pkg.patientName, pkg))}
                                                target="_blank" rel="noreferrer">WhatsApp</a></>}
              </li>
            ))}
          </ul>
        </details>
      )}

      {reminders && <Reminders onClose={() => setReminders(false)} />}

      {!form && !owner && !reminders && (
        <div className="actions"><button onClick={() => setReminders(true)}>Lembretes de amanhã</button></div>
      )}
      {owner && !form && (
        <div className="actions">
          <button className="primary" disabled={services.length === 0}
                  onClick={() => setForm({ mode: 'sessao', patientId: '', serviceId: services[0]?.id ?? '', date,
                    time: '08:00', duration: '', notes: '', ...noRepeat(date) })}>+ Sessão</button>
          <button onClick={() => setForm({ mode: 'bloqueio', date, time: '12:00', endTime: '14:00', notes: '', ...noRepeat(date) })}>
            Bloquear horário</button>
          {!reminders && <button onClick={() => setReminders(true)}>Lembretes de amanhã</button>}
        </div>
      )}
      {owner && services.length === 0 && <p className="notice small">Cadastre um serviço para poder agendar sessões.</p>}
      {owner && form && <AgendaForm form={form} setForm={setForm} patients={patients} services={services}
                                    onSubmit={e => save(e)} onSkipConflicts={() => save(null, true)}
                                    onCancel={() => { setForm(null); setError(''); setConflicts([]); }}
                                    error={error} conflicts={conflicts} />}
      {!form && error && <p className="error" role="alert">{error}</p>}

      <label className="check small agenda-filter"><input type="checkbox" checked={showCanceled}
                                            onChange={e => setShowCanceled(e.target.checked)} /> Mostrar canceladas</label>

      {days.map(({ day, items: dayItems }) => (
        <div key={day} className="agenda-day">
          {view === 'semana' && (
            <h3><button className="link" onClick={() => { setDate(day); setView('dia'); }}>{dayLabel(day)}</button></h3>
          )}
          <ul className="list">
            {dayItems.length === 0 && <li className="muted small">Nada marcado.</li>}
            {dayItems.map(item => (
              <AgendaItem key={item.id} item={item} owner={owner} started={item.startsAt.slice(0, 16) <= now}
                          onStatus={s => changeStatus(item, s)} onCancel={() => cancel(item)}
                          onCancelUpcoming={() => cancelUpcoming(item)}
                          onReschedule={() => setForm({
                            mode: 'remarcar', item, date: dateOf(item.startsAt), time: timeOf(item.startsAt),
                            duration: String(minutesBetween(item.startsAt, item.endsAt)), notes: item.notes ?? ''
                          })} />
            ))}
          </ul>
        </div>
      ))}
    </section>
  );
}

function AgendaItem({ item, owner, started, onStatus, onCancel, onCancelUpcoming, onReschedule }: {
  item: Appointment; owner: boolean; started: boolean;
  onStatus: (s: AppointmentStatus) => void; onCancel: () => void; onCancelUpcoming: () => void; onReschedule: () => void;
}) {
  const time = `${timeOf(item.startsAt)}–${timeOf(item.endsAt)}`;
  if (item.kind === 'BLOCK') {
    return (
      <li className={item.status === 'CANCELED' ? 'item block inactive' : 'item block'}>
        <div><strong>{time}</strong> · Bloqueado{item.notes && `: ${item.notes}`}
          {item.status === 'CANCELED' && <span className="tag">removido</span>}</div>
        {owner && item.status !== 'CANCELED' && (
          <div className="actions"><button onClick={onReschedule}>Alterar</button>
            <button className="danger" onClick={onCancel}>Remover</button>
            {item.seriesId && <button className="danger" onClick={onCancelUpcoming}>Remover próximos</button>}</div>
        )}
      </li>
    );
  }
  const open = item.status === 'SCHEDULED' || item.status === 'CONFIRMED';
  return (
    <li className={item.status === 'CANCELED' ? 'item inactive' : 'item'}>
      <div>
        <strong>{time}</strong> · {item.patientName}{item.seriesId && <span title="Sessão recorrente"> ↻</span>}
        <span className={`status status-${item.status.toLowerCase()}`}>{STATUS_LABEL[item.status]}</span>
        <div className="muted small">
          {item.serviceName}
          {item.originalStartsAt && ` · remarcada (era ${dayLabel(item.originalStartsAt)} ${timeOf(item.originalStartsAt)})`}
        </div>
        {item.status !== 'CANCELED' && (
          <div className="small">
            {item.patientPackageId
              ? <>Pacote {item.packageName} · <span className={item.packageRemaining !== null && item.packageRemaining <= 2 ? 'warn' : ''}>
                  {item.packageRemaining} {item.packageRemaining === 1 ? 'restante' : 'restantes'}</span></>
              : <span className="muted">Sessão avulsa</span>}
          </div>
        )}
        {item.notes && <div className="small">{item.notes}</div>}
      </div>
      {(owner || (item.status === 'SCHEDULED' && !started && item.patientWhatsapp)) && (
        <div className="actions">
          {item.status === 'SCHEDULED' && !started && item.patientWhatsapp && (
            <a className="button wa" target="_blank" rel="noreferrer"
               href={whatsappLink(item.patientWhatsapp, confirmationMessage(item.patientName ?? '', item.serviceName ?? '', item.startsAt, today()))}>
              Pedir confirmação</a>
          )}
          {owner && <>
            {item.status === 'SCHEDULED' && <button onClick={() => onStatus('CONFIRMED')}>Confirmar</button>}
            {open && started && <button onClick={() => onStatus('DONE')}>Realizada</button>}
            {open && started && <button onClick={() => onStatus('NO_SHOW')}>Falta</button>}
            {open && <button onClick={onReschedule}>Remarcar</button>}
            {open && <button className="danger" onClick={onCancel}>Cancelar</button>}
            {open && item.seriesId && <button className="danger" onClick={onCancelUpcoming}>Cancelar próximas</button>}
            {(item.status === 'DONE' || item.status === 'NO_SHOW') &&
              <button onClick={() => onStatus('CONFIRMED')}>Desfazer</button>}
            {item.status === 'CANCELED' && <button onClick={() => onStatus('SCHEDULED')}>Reativar</button>}
          </>}
        </div>
      )}
    </li>
  );
}

function AgendaForm({ form, setForm, patients, services, onSubmit, onSkipConflicts, onCancel, error, conflicts }: {
  form: Form; setForm: (f: Form) => void; patients: Patient[]; services: Service[];
  onSubmit: (e: FormEvent) => void; onSkipConflicts: () => void; onCancel: () => void; error: string; conflicts: string[];
}) {
  const [search, setSearch] = useState('');
  const set = (patch: Partial<Form>) => setForm({ ...form, ...patch } as Form);
  const fold = (t: string) => t.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  const shown = patients.filter(p => fold(p.fullName).includes(fold(search)));
  const service = form.mode === 'sessao' ? services.find(s => s.id === form.serviceId) : undefined;
  // O dia da semana pré-marcado na repetição acompanha a data enquanto a pessoa não escolher outros dias.
  const changeDate = (date: string) => {
    if (form.mode === 'remarcar' || !date) return set({ date });
    const untouched = form.weekdays.length === 1 && form.weekdays[0] === isoWeekday(form.date);
    set(untouched ? { date, weekdays: [isoWeekday(date)] } : { date });
  };

  return (
    <form className="card form" onSubmit={onSubmit}>
      <h3>{form.mode === 'sessao' ? 'Nova sessão' : form.mode === 'bloqueio' ? 'Bloquear horário'
        : form.item.kind === 'BLOCK' ? 'Alterar bloqueio' : `Remarcar ${form.item.patientName}`}</h3>

      {form.mode === 'sessao' && (
        <>
          {patients.length === 0 && <p className="notice small">Cadastre o paciente na aba Pacientes primeiro.</p>}
          <label>Paciente
            <input type="search" placeholder="Digite para filtrar" value={search} aria-label="Filtrar pacientes"
                   onChange={e => setSearch(e.target.value)} />
            <select required value={form.patientId} onChange={e => set({ patientId: e.target.value })}
                    aria-label="Paciente">
              <option value="">Escolha…</option>
              {shown.map(p => <option key={p.id} value={p.id}>{p.fullName}</option>)}
            </select>
          </label>
          <label>Serviço
            <select required value={form.serviceId} onChange={e => set({ serviceId: e.target.value })}>
              {services.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
        </>
      )}

      <div className="row">
        <label>Data<input type="date" required value={form.date} onChange={e => changeDate(e.target.value)} /></label>
        <label>{form.mode === 'bloqueio' ? 'Das' : 'Horário'}
          <input type="time" required value={form.time} onChange={e => set({ time: e.target.value })} /></label>
        {form.mode === 'bloqueio' ? (
          <label>Até<input type="time" required value={form.endTime} onChange={e => set({ endTime: e.target.value })} /></label>
        ) : (
          <label>Duração (min)
            <input type="number" min={5} max={form.mode === 'remarcar' ? 720 : 480} required={form.mode === 'remarcar'}
                   placeholder={service ? String(service.durationMinutes) : ''} value={form.duration}
                   onChange={e => set({ duration: e.target.value })} /></label>
        )}
      </div>
      <label>{form.mode === 'bloqueio' || (form.mode === 'remarcar' && form.item.kind === 'BLOCK') ? 'Motivo (opcional)' : 'Observação (opcional)'}
        <input maxLength={500} value={form.notes} placeholder={form.mode === 'bloqueio' ? 'Ex.: almoço, folga' : ''}
               onChange={e => set({ notes: e.target.value })} /></label>

      {form.mode !== 'remarcar' && <RepeatFields form={form} set={set} />}

      {error && <p className="error" role="alert">{error}</p>}
      {conflicts.length > 0 && (
        <div className="notice small">
          <ul className="conflicts">{conflicts.map(c => <li key={c}>{c}</li>)}</ul>
          <button type="button" onClick={onSkipConflicts}>Agendar pulando esses dias</button>
        </div>
      )}
      <div className="actions">
        <button className="primary">{form.mode === 'remarcar' ? 'Salvar' : form.mode === 'bloqueio' ? 'Bloquear' : 'Agendar'}</button>
        <button type="button" onClick={onCancel}>Voltar</button>
      </div>
    </form>
  );
}

function RepeatFields({ form, set }: { form: Form & Repeat; set: (patch: Partial<Form>) => void }) {
  const dates = form.repeat
    ? seriesDates(form.date, form.weekdays, form.limitMode === 'semanas' ? Number(form.limit) : undefined,
      form.limitMode === 'vezes' ? Number(form.limit) : undefined)
    : [];
  const toggle = (iso: number) => set({
    weekdays: form.weekdays.includes(iso) ? form.weekdays.filter(d => d !== iso) : [...form.weekdays, iso].sort()
  });
  const noun = form.mode === 'bloqueio' ? 'bloqueios' : 'sessões';

  return (
    <fieldset className="repeat">
      <label className="check"><input type="checkbox" checked={form.repeat}
                                      onChange={e => set({ repeat: e.target.checked })} /> Repetir</label>
      {form.repeat && (
        <>
          <div className="weekdays" role="group" aria-label="Dias da semana">
            {WEEKDAYS.map(d => (
              <button type="button" key={d.iso} aria-pressed={form.weekdays.includes(d.iso)}
                      className={form.weekdays.includes(d.iso) ? 'chip on' : 'chip'} onClick={() => toggle(d.iso)}>
                {d.label}</button>
            ))}
          </div>
          <div className="repeat-limit">
            <span>Por</span>
            <input type="number" min={1} max={form.limitMode === 'semanas' ? 52 : MAX_OCCURRENCES} required
                   value={form.limit} onChange={e => set({ limit: e.target.value })} aria-label="Quantidade" />
            <select value={form.limitMode} aria-label="Unidade"
                    onChange={e => set({ limitMode: e.target.value as Repeat['limitMode'] })}>
              <option value="semanas">semanas</option>
              <option value="vezes">{noun}</option>
            </select>
          </div>
          <p className="muted small" aria-live="polite">
            {dates.length === 0 ? 'Escolha os dias da semana.'
              : dates.length > MAX_OCCURRENCES ? `Passa de ${MAX_OCCURRENCES} ${noun}; diminua o período.`
                : `${dates.length} ${noun}, de ${dayLabel(dates[0])} a ${dayLabel(dates[dates.length - 1])}.`}
          </p>
        </>
      )}
    </fieldset>
  );
}

/** Lembretes de amanhã: abre o WhatsApp de cada paciente, um por um, com a mensagem pronta. */
function Reminders({ onClose }: { onClose: () => void }) {
  const tomorrow = addDays(today(), 1);
  const [items, setItems] = useState<Appointment[] | null>(null);
  const [sent, setSent] = useState<string[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    api<Appointment[]>(`/api/v1/appointments?from=${tomorrow}&to=${tomorrow}`)
      .then(list => setItems(list.filter(a => a.kind === 'SESSION' && (a.status === 'SCHEDULED' || a.status === 'CONFIRMED'))))
      .catch(e => setError((e as Error).message));
  }, [tomorrow]);

  const link = (a: Appointment) =>
    whatsappLink(a.patientWhatsapp ?? '', confirmationMessage(a.patientName ?? '', a.serviceName ?? '', a.startsAt, today()));
  const withPhone = (items ?? []).filter(a => a.patientWhatsapp);
  const next = withPhone.find(a => !sent.includes(a.id));
  const markSent = (id: string) => setSent(list => (list.includes(id) ? list : [...list, id]));

  return (
    <div className="card form reminders">
      <h3>Lembretes de amanhã · {dayLabel(tomorrow)}</h3>
      {error && <p className="error" role="alert">{error}</p>}
      {items === null && !error && <p className="muted small">Carregando…</p>}
      {items?.length === 0 && <p className="muted small">Nenhuma sessão marcada para amanhã.</p>}
      {next && (
        <a className="button primary-link" href={link(next)} target="_blank" rel="noreferrer" onClick={() => markSent(next.id)}>
          Enviar próximo ({sent.filter(id => withPhone.some(a => a.id === id)).length + 1} de {withPhone.length}): {next.patientName}
        </a>
      )}
      {items && items.length > 0 && !next && withPhone.length > 0 && <p className="success small">Todos os lembretes foram abertos.</p>}
      <ul className="list">
        {(items ?? []).map(a => (
          <li key={a.id} className="item">
            <div><strong>{timeOf(a.startsAt)}</strong> · {a.patientName}
              <span className={`status status-${a.status.toLowerCase()}`}>{STATUS_LABEL[a.status]}</span>
              <div className="muted small">{a.serviceName}</div></div>
            {a.patientWhatsapp
              ? (sent.includes(a.id)
                ? <span className="success small">Aberto ✓</span>
                : <a className="button wa" href={link(a)} target="_blank" rel="noreferrer" onClick={() => markSent(a.id)}>Enviar</a>)
              : <span className="muted small">Sem WhatsApp</span>}
          </li>
        ))}
      </ul>
      <div className="actions"><button type="button" onClick={onClose}>Fechar</button></div>
    </div>
  );
}
