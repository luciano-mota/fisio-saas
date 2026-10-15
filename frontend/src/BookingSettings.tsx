import { useEffect, useState, type FormEvent } from 'react';
import { api, type BookingConfig, type OpeningHours } from './api';
import { hoursSummary } from './booking';
import { Icon, useUi } from './ui';

const DAYS = ['', 'Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado', 'Domingo'];
const PRESET: OpeningHours[] = [1, 2, 3, 4, 5].flatMap(weekday => [
  { weekday, start: '08:00', end: '12:00' }, { weekday, start: '14:00', end: '18:00' }
]);

/** Horário de atendimento e regras do agendamento pelo site (salvo à parte do restante da clínica). */
export function BookingSettings({ owner }: { owner: boolean }) {
  const { toast } = useUi();
  const [config, setConfig] = useState<BookingConfig | null>(null);
  const [error, setError] = useState('');
  useEffect(() => { api<BookingConfig>('/api/v1/clinic/booking').then(setConfig).catch(e => setError((e as Error).message)); }, []);
  if (!config) return error ? <p className="error">{error}</p> : null;

  const set = (patch: Partial<BookingConfig>) => setConfig({ ...config, ...patch });
  const setHours = (hours: OpeningHours[]) => set({ hours });
  const ofDay = (d: number) => config.hours.map((h, i) => ({ h, i })).filter(x => x.h.weekday === d);

  async function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      setConfig(await api<BookingConfig>('/api/v1/clinic/booking', 'PUT', config));
      toast(config!.enabled ? 'Agendamento pelo site ativado' : 'Horário de atendimento salvo');
    } catch (e) { setError((e as Error).message); }
  }

  const summary = hoursSummary(config.hours);
  return (
    <form className="card form" onSubmit={save}>
      <h3>Horário de atendimento e agendamento pelo site</h3>
      <fieldset disabled={!owner} className="stack">
        <p className="muted small">
          O paciente escolhe um horário livre no site e informa CPF e WhatsApp. O pedido chega em “Solicitações” e
          a sessão só entra na agenda quando você aceita.
        </p>
        <div className="hours">
          {[1, 2, 3, 4, 5, 6, 7].map(d => (
            <div key={d} className="hours-day">
              <strong>{DAYS[d]}</strong>
              <div className="hours-list">
                {ofDay(d).length === 0 && <span className="muted small">Não atende</span>}
                {ofDay(d).map(({ h, i }) => (
                  <span key={i} className="hours-item">
                    <input type="time" aria-label={`${DAYS[d]}: início`} value={h.start} required
                           onChange={e => setHours(config.hours.map((x, j) => (j === i ? { ...x, start: e.target.value } : x)))} />
                    <span>às</span>
                    <input type="time" aria-label={`${DAYS[d]}: fim`} value={h.end} required
                           onChange={e => setHours(config.hours.map((x, j) => (j === i ? { ...x, end: e.target.value } : x)))} />
                    <button type="button" className="icon-button" aria-label={`Tirar intervalo de ${DAYS[d]}`}
                            onClick={() => setHours(config.hours.filter((_, j) => j !== i))}><Icon name="x" size={18} /></button>
                  </span>
                ))}
              </div>
              <button type="button" className="small-button" onClick={() => {
                const last = ofDay(d).at(-1)?.h;
                setHours([...config.hours, last ? { weekday: d, start: last.end, end: last.end < '18:00' ? '18:00' : '20:00' }
                  : { weekday: d, start: '08:00', end: '12:00' }]);
              }}><Icon name="plus" size={16} /> Intervalo</button>
            </div>
          ))}
        </div>
        <div className="actions">
          <button type="button" className="small-button" onClick={() => setHours(PRESET)}>Usar seg a sex, 8h–12h e 14h–18h</button>
          {config.hours.length > 0 && <button type="button" className="small-button" onClick={() => setHours([])}>Limpar</button>}
        </div>
        {summary.length > 0 && <p className="small"><strong>No site:</strong> {summary.join(' · ')}</p>}

        <label className="switch"><input type="checkbox" checked={config.enabled} onChange={e => set({ enabled: e.target.checked })} />
          <span>Permitir agendamento pelo site</span></label>
        <div className="row">
          <label>Antecedência mínima
            <select value={config.minNoticeHours} onChange={e => set({ minNoticeHours: Number(e.target.value) })}>
              {[0, 1, 2, 4, 12, 24, 48].map(n => <option key={n} value={n}>{n === 0 ? 'Nenhuma' : `${n} h`}</option>)}
            </select></label>
          <label>Agendar até
            <select value={config.maxDays} onChange={e => set({ maxDays: Number(e.target.value) })}>
              {[7, 14, 30, 60, 90].map(n => <option key={n} value={n}>{n} dias à frente</option>)}
            </select></label>
          <label>Segurar o horário por
            <select value={config.holdHours} onChange={e => set({ holdHours: Number(e.target.value) })}>
              {[2, 6, 12, 24, 48].map(n => <option key={n} value={n}>{n} h sem resposta</option>)}
            </select></label>
          <label>Horários de
            <select value={config.stepMinutes} onChange={e => set({ stepMinutes: Number(e.target.value) })}>
              {[15, 20, 30, 60].map(n => <option key={n} value={n}>{n} em {n} min</option>)}
            </select></label>
        </div>
      </fieldset>
      {error && <p className="error-box" role="alert">{error}</p>}
      {owner && <div className="actions"><button className="primary">Salvar horário e agendamento</button></div>}
    </form>
  );
}
