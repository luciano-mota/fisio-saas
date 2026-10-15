import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { api, ApiError, whatsappLink, type Appointment, type AppointmentStatus, type Patient, type PatientPackage,
  type Service } from './api';
import { addDays, dateOf, dayLabel, minutesBetween, nowLocal, timeOf, today, weekStart } from './dates';
import { brl, moneyInput, parseMoney, sessionPaymentState } from './finance';
import { confirmationMessage, renewalMessage, sessionsLabel } from './messages';
import { PaymentSheet } from './PaymentSheet';
import { dayChip, daySummary, longDayLabel, primaryAction, sessionsCount, summaryText } from './panel';
import { isoWeekday, MAX_OCCURRENCES, seriesDates, WEEKDAYS } from './recurrence';
import { navigate } from './router';
import { EmptyState, Icon, Menu, PageHeader, Sheet, useUi, type MenuEntry } from './ui';

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

/** Agenda do dia ou da semana, pensada para o celular. {@code action}: "nova" abre o formulário, "lembretes" os lembretes. */
export function AgendaTab({ owner, action }: { owner: boolean; action?: string }) {
  const { toast } = useUi();
  const [view, setView] = useState<View>('dia');
  const [date, setDate] = useState(today());
  const [items, setItems] = useState<Appointment[] | null>(null);
  const [showCanceled, setShowCanceled] = useState(false);
  const [patients, setPatients] = useState<Patient[]>([]);
  const [services, setServices] = useState<Service[]>([]);
  const [form, setForm] = useState<Form | null>(null);
  const [error, setError] = useState('');
  const [conflicts, setConflicts] = useState<string[]>([]);
  const [lowBalance, setLowBalance] = useState<PatientPackage[]>([]);
  const [reminders, setReminders] = useState(false);

  // Sempre carrega a semana inteira: a faixa de dias mostra quantas sessões há em cada um.
  const from = weekStart(date);
  const to = addDays(from, 6);

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

  const noRepeat = (day: string): Repeat => ({ repeat: false, weekdays: [isoWeekday(day)], limitMode: 'semanas', limit: '4' });
  const newSession = useCallback(() => {
    setError(''); setConflicts([]);
    setForm({ mode: 'sessao', patientId: '', serviceId: '', date, time: '08:00', duration: '', notes: '', ...noRepeat(date) });
  }, [date]);
  const newBlock = () => {
    setError(''); setConflicts([]);
    setForm({ mode: 'bloqueio', date, time: '12:00', endTime: '14:00', notes: '', ...noRepeat(date) });
  };

  // Atalhos vindos da tela Hoje (/painel/agenda/nova e /painel/agenda/lembretes).
  useEffect(() => {
    if (action === 'nova' && owner) newSession();
    if (action === 'lembretes') setReminders(true);
    if (action) navigate('/painel/agenda');
  }, [action, owner, newSession]);
  // Serviço padrão quando a lista chega depois de abrir o formulário.
  useEffect(() => {
    if (form?.mode === 'sessao' && !form.serviceId && services[0]) setForm({ ...form, serviceId: services[0].id });
  }, [form, services]);

  async function save(event: FormEvent | null, skipConflicts = false) {
    event?.preventDefault();
    if (!form) return;
    setError('');
    setConflicts([]);
    const startsAt = `${form.date}T${form.time}`;
    try {
      if (form.mode !== 'remarcar' && form.repeat) {
        const block = form.mode === 'bloqueio';
        await api('/api/v1/appointments/series', 'POST', {
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
      } else if (form.mode === 'sessao') {
        await api('/api/v1/appointments', 'POST', {
          patientId: form.patientId, serviceId: form.serviceId, startsAt,
          durationMinutes: form.duration ? Number(form.duration) : null, notes: form.notes || null
        });
      } else if (form.mode === 'bloqueio') {
        await api('/api/v1/blocks', 'POST', { startsAt, endsAt: `${form.date}T${form.endTime}`, notes: form.notes || null });
      } else {
        await api(`/api/v1/appointments/${form.item.id}`, 'PUT', {
          startsAt, durationMinutes: Number(form.duration), notes: form.notes || null
        });
      }
      toast(form.mode === 'remarcar' ? 'Horário alterado' : form.mode === 'bloqueio' ? 'Horário bloqueado' : 'Sessão agendada');
      setForm(null);
      if (dateOf(startsAt) !== date) setDate(dateOf(startsAt));
      await load();
    } catch (e) {
      setError((e as Error).message);
      if (e instanceof ApiError) setConflicts(e.conflicts);
    }
  }

  const week = useMemo(() => Array.from({ length: 7 }, (_, i) => addDays(from, i)), [from]);
  const ofDay = (day: string) => (items ?? []).filter(i => dateOf(i.startsAt) === day);
  const days = view === 'dia' ? [date] : week;
  const step = view === 'dia' ? 1 : 7;
  const now = nowLocal();
  const reschedule = (item: Appointment) => {
    setError(''); setConflicts([]);
    setForm({ mode: 'remarcar', item, date: dateOf(item.startsAt), time: timeOf(item.startsAt),
      duration: String(minutesBetween(item.startsAt, item.endsAt)), notes: item.notes ?? '' });
  };

  return (
    <section>
      <PageHeader title="Agenda" subtitle={view === 'dia' ? longDayLabel(date) : `${dayLabel(from)} a ${dayLabel(to)}`}>
        {owner && <button className="primary" disabled={services.length === 0} onClick={newSession}>
          <Icon name="plus" size={18} /> Sessão</button>}
        <Menu label="Mais opções da agenda" items={[
          owner && { label: 'Bloquear horário', icon: 'block', onClick: newBlock },
          { label: 'Lembretes de amanhã', icon: 'whatsapp', onClick: () => setReminders(true) },
          { label: showCanceled ? 'Esconder canceladas' : 'Mostrar canceladas', icon: 'x', onClick: () => setShowCanceled(v => !v) }
        ]} />
      </PageHeader>

      <div className="date-bar">
        <button className="icon-button" aria-label="Anterior" onClick={() => setDate(addDays(date, -step))}><Icon name="left" /></button>
        <button className="icon-button" aria-label="Próximo" onClick={() => setDate(addDays(date, step))}><Icon name="right" /></button>
        {date !== today() && <button className="small-button" onClick={() => setDate(today())}>Hoje</button>}
        <div className="segmented" role="group" aria-label="Visualização">
          <button className={view === 'dia' ? 'active' : ''} aria-pressed={view === 'dia'} onClick={() => setView('dia')}>Dia</button>
          <button className={view === 'semana' ? 'active' : ''} aria-pressed={view === 'semana'} onClick={() => setView('semana')}>Semana</button>
        </div>
      </div>

      <div className="week-strip" role="group" aria-label="Dias da semana">
        {week.map(day => {
          const chip = dayChip(day);
          const count = daySummary(ofDay(day)).sessions;
          return (
            <button key={day} className={[view === 'dia' && day === date ? 'on' : '', day === today() ? 'today' : ''].join(' ')}
                    aria-pressed={view === 'dia' && day === date} aria-label={`${dayLabel(day)}, ${sessionsCount(count)}`}
                    onClick={() => { setDate(day); setView('dia'); }}>
              <span>{chip.day}</span><strong>{chip.number}</strong>
              <i className="dots">{Array.from({ length: Math.min(count, 3) }, (_, k) => <b key={k} />)}</i>
            </button>
          );
        })}
      </div>

      {lowBalance.length > 0 && (
        <details className="banner">
          <summary><Icon name="box" size={18} />
            {lowBalance.length === 1 ? '1 pacote acabando' : `${lowBalance.length} pacotes acabando`} — hora de oferecer renovação</summary>
          <ul>
            {lowBalance.map(pkg => (
              <li key={pkg.id}>
                <a href={`/painel/pacientes/${pkg.patientId}`} onClick={e => { e.preventDefault(); navigate('/painel/pacientes/' + pkg.patientId); }}>
                  {pkg.patientName}</a>
                {' '}· {pkg.serviceName} · {pkg.remaining <= 0 ? 'acabou' : `${sessionsLabel(pkg.remaining)} restante${pkg.remaining === 1 ? '' : 's'}`}
                {pkg.patientWhatsapp && <> · <a href={whatsappLink(pkg.patientWhatsapp, renewalMessage(pkg.patientName, pkg))}
                                                target="_blank" rel="noreferrer">WhatsApp</a></>}
              </li>
            ))}
          </ul>
        </details>
      )}
      {owner && services.length === 0 && items !== null && (
        <p className="notice small">Cadastre um serviço em <a href="/painel/catalogo" onClick={e => { e.preventDefault(); navigate('/painel/catalogo'); }}>
          Serviços e pacotes</a> para poder agendar sessões.</p>
      )}
      {!form && error && <p className="error" role="alert">{error}</p>}

      {days.map(day => {
        const dayItems = ofDay(day);
        return (
          <div key={day} className="agenda-day">
            <div className="day-head">
              {view === 'semana'
                ? <button className="link-button strong" onClick={() => { setDate(day); setView('dia'); }}>{dayLabel(day)}</button>
                : <strong>{day === today() ? 'Hoje' : dayLabel(day)}</strong>}
              <span className="muted small">{summaryText(daySummary(dayItems, nowLocal()))}</span>
            </div>
            {items !== null && dayItems.length === 0 && (view === 'dia'
              ? <EmptyState icon="calendar" title="Dia livre" text="Nada marcado para este dia.">
                  {owner && services.length > 0 && <button className="primary" onClick={newSession}><Icon name="plus" size={18} /> Agendar sessão</button>}
                </EmptyState>
              : <p className="muted small empty-line">Nada marcado.</p>)}
            <ul className="timeline">
              {dayItems.map(item => (
                <AppointmentCard key={item.id} item={item} owner={owner} started={item.startsAt.slice(0, 16) <= now}
                                 onChanged={load} onError={setError} onReschedule={() => reschedule(item)} />
              ))}
            </ul>
          </div>
        );
      })}

      {owner && form && (
        <Sheet title={form.mode === 'sessao' ? 'Nova sessão' : form.mode === 'bloqueio' ? 'Bloquear horário'
          : form.item.kind === 'BLOCK' ? 'Alterar bloqueio' : `Remarcar ${form.item.patientName}`}
               onClose={() => { setForm(null); setError(''); setConflicts([]); }}>
          <AgendaForm form={form} setForm={setForm} patients={patients} services={services}
                      onSubmit={e => save(e)} onSkipConflicts={() => save(null, true)}
                      onCancel={() => { setForm(null); setError(''); setConflicts([]); }}
                      onEdit={() => { setError(''); setConflicts([]); }}
                      error={error} conflicts={conflicts} />
        </Sheet>
      )}
      {reminders && <Reminders onClose={() => setReminders(false)} />}
    </section>
  );
}

/**
 * Uma sessão (ou bloqueio) da agenda: horário, paciente, serviço, saldo do pacote e status, com UMA ação em destaque
 * (pedir confirmação antes do horário, realizada depois) e as demais no menu "⋯". Usado na Agenda e na tela Hoje.
 */
export function AppointmentCard({ item, owner, started, onChanged, onError, onReschedule }: {
  item: Appointment; owner: boolean; started: boolean; onChanged: () => Promise<unknown> | void;
  onError: (message: string) => void; onReschedule?: () => void;
}) {
  const { toast, confirm } = useUi();
  const [paying, setPaying] = useState(false);
  const [pricing, setPricing] = useState(false);

  async function run(action: () => Promise<unknown>, message: string) {
    onError('');
    try {
      await action();
      toast(message);
      await onChanged();
    } catch (e) { onError((e as Error).message); }
  }
  const setStatus = (status: AppointmentStatus, message: string) =>
    run(() => api(`/api/v1/appointments/${item.id}/status`, 'POST', { status }), message);
  async function cancel() {
    const block = item.kind === 'BLOCK';
    if (await confirm(block ? 'Remover este bloqueio de horário?' : `Cancelar a sessão de ${item.patientName}?`,
      { confirmLabel: block ? 'Remover' : 'Cancelar sessão', danger: true })) {
      await run(() => api(`/api/v1/appointments/${item.id}`, 'DELETE'), block ? 'Bloqueio removido' : 'Sessão cancelada');
    }
  }
  async function cancelUpcoming() {
    const what = item.kind === 'BLOCK' ? 'os próximos bloqueios desta série' : `as próximas sessões de ${item.patientName} desta série`;
    if (await confirm(`Cancelar ${what}, a partir de ${dayLabel(item.startsAt)}? O que já foi realizado não muda.`,
      { confirmLabel: 'Cancelar próximas', danger: true })) {
      await run(() => api(`/api/v1/appointments/series/${item.seriesId}?from=${dateOf(item.startsAt)}`, 'DELETE'), 'Próximas canceladas');
    }
  }

  const time = (
    <div className="appt-time"><strong>{timeOf(item.startsAt)}</strong><span>{timeOf(item.endsAt)}</span></div>
  );
  const canceled = item.status === 'CANCELED';

  if (item.kind === 'BLOCK') {
    return (
      <li className={canceled ? 'appt block inactive' : 'appt block'}>
        {time}
        <div className="appt-body">
          <div className="appt-title"><Icon name="block" size={16} /> Bloqueado{item.notes && `: ${item.notes}`}
            {canceled && <span className="pill">removido</span>}</div>
        </div>
        {owner && !canceled && (
          <div className="appt-actions">
            <Menu label="Ações do bloqueio" items={[
              onReschedule && { label: 'Alterar', icon: 'edit', onClick: onReschedule },
              { label: 'Remover', icon: 'trash', danger: true, onClick: cancel },
              item.seriesId && { label: 'Remover próximos', icon: 'repeat', danger: true, onClick: cancelUpcoming }
            ]} />
          </div>
        )}
      </li>
    );
  }

  const open = item.status === 'SCHEDULED' || item.status === 'CONFIRMED';
  const confirmLink = item.patientWhatsapp
    ? whatsappLink(item.patientWhatsapp, confirmationMessage(item.patientName ?? '', item.serviceName ?? '', item.startsAt, today()))
    : null;
  const primary = primaryAction(item, started, !!confirmLink);
  const single = !item.patientPackageId;
  const payState = single && !canceled ? sessionPaymentState(item.price, item.paidAmount) : null;
  const menu: MenuEntry[] = owner ? [
    item.status === 'SCHEDULED' && primary !== 'confirm' && { label: 'Marcar como confirmada', icon: 'check', onClick: () => setStatus('CONFIRMED', 'Sessão confirmada') },
    open && started && { label: 'Marcar falta', icon: 'x', onClick: () => setStatus('NO_SHOW', 'Falta registrada') },
    open && onReschedule && { label: 'Remarcar', icon: 'calendar', onClick: onReschedule },
    (item.status === 'DONE' || item.status === 'NO_SHOW') && { label: 'Desfazer', icon: 'repeat', onClick: () => setStatus('CONFIRMED', 'Status desfeito') },
    canceled && { label: 'Reativar', icon: 'repeat', onClick: () => setStatus('SCHEDULED', 'Sessão reativada') },
    single && !canceled && item.price !== null && item.paidAmount < item.price &&
      { label: 'Registrar pagamento', icon: 'money', onClick: () => setPaying(true) },
    single && !canceled && { label: 'Valor da sessão', icon: 'tag', onClick: () => setPricing(true) },
    item.patientId && { label: 'Ver paciente', icon: 'users', onClick: () => navigate('/painel/pacientes/' + item.patientId) },
    open && { label: 'Cancelar sessão', icon: 'trash', danger: true, onClick: cancel },
    open && item.seriesId && { label: 'Cancelar próximas', icon: 'repeat', danger: true, onClick: cancelUpcoming }
  ] : [];

  return (
    <li className={`appt status-${item.status.toLowerCase()}${canceled ? ' inactive' : ''}`}>
      {time}
      <div className="appt-body">
        <div className="appt-title">
          {item.patientId
            ? <a className="name-link" href={`/painel/pacientes/${item.patientId}`}
                 onClick={e => { e.preventDefault(); navigate('/painel/pacientes/' + item.patientId); }}>{item.patientName}</a>
            : item.patientName}
          {item.seriesId && <span className="muted" title="Sessão recorrente"><Icon name="repeat" size={14} /></span>}
          <span className={`pill status status-${item.status.toLowerCase()}`}>{STATUS_LABEL[item.status]}</span>
        </div>
        <div className="meta">
          <span>{item.serviceName}</span>
          {!canceled && (item.patientPackageId
            ? <span className={item.packageRemaining !== null && item.packageRemaining <= 2 ? 'pill warn' : 'pill'}>
                {item.packageName} · {item.packageRemaining} {item.packageRemaining === 1 ? 'restante' : 'restantes'}</span>
            : <span className="pill">avulsa{item.price !== null && ` · ${item.price === 0 ? 'cortesia' : brl(item.price)}`}</span>)}
          {payState && item.status === 'DONE' && <span className={payState === 'pago' ? 'pill ok' : 'pill warn'}>{payState}</span>}
          {item.originalStartsAt && <span>remarcada (era {dayLabel(item.originalStartsAt)} {timeOf(item.originalStartsAt)})</span>}
        </div>
        {item.notes && <p className="appt-notes">{item.notes}</p>}
      </div>
      <div className="appt-actions">
        {primary === 'ask-confirmation' && confirmLink && (
          <a className="button wa" target="_blank" rel="noreferrer" href={confirmLink}>
            <Icon name="whatsapp" size={18} /> Pedir confirmação</a>
        )}
        {owner && primary === 'confirm' && <button onClick={() => setStatus('CONFIRMED', 'Sessão confirmada')}><Icon name="check" size={18} /> Confirmar</button>}
        {owner && primary === 'done' && <button className="primary" onClick={() => setStatus('DONE', 'Sessão realizada')}><Icon name="check" size={18} /> Realizada</button>}
        <Menu label={`Ações da sessão de ${item.patientName}`} items={menu} />
      </div>
      {paying && item.patientId && (
        <PaymentSheet patientId={item.patientId} target={{ kind: 'SESSAO', id: item.id, amount: item.price,
                        label: `${item.serviceName} · ${dayLabel(item.startsAt)} (sessão desta agenda)` }} onClose={() => setPaying(false)}
                      onSaved={async () => { setPaying(false); toast('Pagamento registrado'); await onChanged(); }} />
      )}
      {pricing && (
        <PriceSheet item={item} onClose={() => setPricing(false)}
                    onSaved={async () => { setPricing(false); toast('Valor da sessão salvo'); await onChanged(); }} />
      )}
    </li>
  );
}

/** Valor desta sessão avulsa (desconto ou cortesia); não muda o preço do serviço. */
function PriceSheet({ item, onClose, onSaved }: { item: Appointment; onClose: () => void; onSaved: () => void }) {
  const [price, setPrice] = useState(moneyInput(item.price));
  const [error, setError] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    const value = parseMoney(price);
    if (value !== null && Number.isNaN(value)) { setError('Valor inválido. Ex.: 90,00'); return; }
    try {
      await api(`/api/v1/appointments/${item.id}/price`, 'PUT', { price: value });
      onSaved();
    } catch (e) { setError((e as Error).message); }
  }
  return (
    <Sheet title="Valor da sessão" onClose={onClose}>
      <form onInput={() => setError('')} className="form" onSubmit={submit}>
        <p className="muted small">{item.serviceName} de {item.patientName}. Use 0 para cortesia; em branco, a sessão não entra na cobrança.</p>
        <label>Valor<input inputMode="decimal" placeholder="Ex.: 90,00" value={price} onChange={e => setPrice(e.target.value)} /></label>
        {error && <p className="error-box" role="alert">{error}</p>}
        <div className="sheet-actions">
          <button type="button" onClick={onClose}>Cancelar</button>
          <button className="primary">Salvar</button>
        </div>
      </form>
    </Sheet>
  );
}

function AgendaForm({ form, setForm, patients, services, onSubmit, onSkipConflicts, onCancel, onEdit, error, conflicts }: {
  form: Form; setForm: (f: Form) => void; patients: Patient[]; services: Service[];
  onSubmit: (e: FormEvent) => void; onSkipConflicts: () => void; onCancel: () => void; onEdit: () => void; error: string;
  conflicts: string[];
}) {
  const [search, setSearch] = useState('');
  const set = (patch: Partial<Form>) => setForm({ ...form, ...patch } as Form);
  const fold = (t: string) => t.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  const shown = patients.filter(p => p.active && fold(p.fullName).includes(fold(search)));
  const service = form.mode === 'sessao' ? services.find(s => s.id === form.serviceId) : undefined;
  // O dia da semana pré-marcado na repetição acompanha a data enquanto a pessoa não escolher outros dias.
  const changeDate = (date: string) => {
    if (form.mode === 'remarcar' || !date) return set({ date });
    const untouched = form.weekdays.length === 1 && form.weekdays[0] === isoWeekday(form.date);
    set(untouched ? { date, weekdays: [isoWeekday(date)] } : { date });
  };

  return (
    <form className="form" onSubmit={onSubmit} onInput={onEdit}>
      {form.mode === 'sessao' && (
        <>
          {patients.length === 0 && (
            <p className="notice small">Cadastre o paciente primeiro em{' '}
              <a href="/painel/pacientes/novo" onClick={e => { e.preventDefault(); navigate('/painel/pacientes/novo'); }}>Pacientes</a>.</p>
          )}
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
              {services.length === 0 && <option value="">Escolha…</option>}
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
      <div className="sheet-actions">
        <button type="button" onClick={onCancel}>Cancelar</button>
        <button className="primary">{form.mode === 'remarcar' ? 'Salvar' : form.mode === 'bloqueio' ? 'Bloquear' : 'Agendar'}</button>
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
      <label className="switch"><input type="checkbox" checked={form.repeat}
                                       onChange={e => set({ repeat: e.target.checked })} /><span>Repetir</span></label>
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
    <Sheet title={`Lembretes de amanhã · ${dayLabel(tomorrow)}`} onClose={onClose}>
      <div className="form reminders">
        {error && <p className="error" role="alert">{error}</p>}
        {items === null && !error && <p className="muted small">Carregando…</p>}
        {items?.length === 0 && <EmptyState icon="calendar" title="Nenhuma sessão amanhã" />}
        {next && (
          <a className="button primary-link big" href={link(next)} target="_blank" rel="noreferrer" onClick={() => markSent(next.id)}>
            <Icon name="whatsapp" size={20} />
            Enviar próximo ({sent.filter(id => withPhone.some(a => a.id === id)).length + 1} de {withPhone.length}): {next.patientName}
          </a>
        )}
        {items && items.length > 0 && !next && withPhone.length > 0 && <p className="success small">Todos os lembretes foram abertos.</p>}
        <ul className="cards">
          {(items ?? []).map(a => (
            <li key={a.id} className="row-card">
              <div className="appt-time"><strong>{timeOf(a.startsAt)}</strong></div>
              <div className="grow"><strong>{a.patientName}</strong>
                <div className="meta"><span>{a.serviceName}</span>
                  <span className={`pill status status-${a.status.toLowerCase()}`}>{STATUS_LABEL[a.status]}</span></div></div>
              {a.patientWhatsapp
                ? (sent.includes(a.id)
                  ? <span className="success small">Aberto ✓</span>
                  : <a className="button wa" href={link(a)} target="_blank" rel="noreferrer" onClick={() => markSent(a.id)}>Enviar</a>)
                : <span className="muted small">Sem WhatsApp</span>}
            </li>
          ))}
        </ul>
      </div>
    </Sheet>
  );
}
