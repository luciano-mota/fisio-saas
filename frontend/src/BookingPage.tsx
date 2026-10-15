import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { api, ApiError, money, uploadReceipt, whatsappLink, type BookingSent, type PublicClinic, type PublicService } from './api';
import { nextDays, requestSentMessage, whenLabel } from './booking';
import { dayLabel, today } from './dates';
import { isValidCpf } from './format';
import { dayChip } from './panel';
import { preparePhoto } from './photoImage';
import { Brand, useSiteLook } from './PublicPage';
import { navigate } from './router';
import { SiteIcon } from './SiteIcons';

const DAYS_AHEAD = 21;
type Step = 'servico' | 'horario' | 'dados' | 'pix' | 'enviado';
type Form = {
  fullName: string; cpf: string; whatsapp: string; birthDate: string; notes: string; consent: boolean; website: string;
  payMethod: 'PIX' | 'CLINICA';
};
const MAX_PDF = 3 * 1024 * 1024;

/** Preço do serviço no agendamento: só aparece se a clínica deixou visível. Com chave Pix, dá para pagar já. */
function priceText(service: PublicService, pix: boolean): string {
  if (service.price === null) return 'Valor sob consulta';
  return `${money(service.price)} · ${pix ? 'Pix agora ou na clínica' : 'pagamento na clínica'}`;
}

/** Foto do comprovante vira JPEG leve no navegador; PDF vai como está (até 3 MB). */
async function prepareReceipt(file: File): Promise<{ blob: Blob; name: string }> {
  const pdf = file.type === 'application/pdf' || /\.pdf$/i.test(file.name);
  if (pdf) {
    if (file.size > MAX_PDF) throw new Error('PDF muito grande (máximo 3 MB). Envie um print da tela.');
    return { blob: file, name: 'comprovante.pdf' };
  }
  return { blob: await preparePhoto(file), name: 'comprovante.jpg' };
}

/**
 * Agendamento pelo site: serviço → dia e horário livre → nome, CPF e WhatsApp → pedido enviado. O CPF é obrigatório
 * para a clínica achar o cadastro sem duplicar paciente. A sessão só entra na agenda quando a clínica aceita.
 */
export function BookingPage({ clinic }: { clinic: PublicClinic }) {
  const { style, template, mood, logo, vars } = useSiteLook(clinic);
  const services = clinic.services.filter(s => s.onlineBooking);
  const preselected = new URLSearchParams(window.location.search).get('servico');
  const [service, setService] = useState<PublicService | null>(services.find(s => s.id === preselected) ?? null);
  const [step, setStep] = useState<Step>(service ? 'horario' : 'servico');
  const [slots, setSlots] = useState<Record<string, string[]> | null>(null);
  const [day, setDay] = useState('');
  const [time, setTime] = useState('');
  const [form, setForm] = useState<Form>({
    fullName: '', cpf: '', whatsapp: '', birthDate: '', notes: '', consent: false, website: '', payMethod: 'PIX'
  });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState<BookingSent | null>(null);
  const [receipt, setReceipt] = useState<'idle' | 'sending' | 'sent'>('idle');
  const [copied, setCopied] = useState(false);
  const pix = clinic.booking.pix;
  const pixFor = (s: PublicService | null) => pix && s !== null && s.price !== null && s.price > 0;
  const days = nextDays(today(), DAYS_AHEAD);
  const back = () => { navigate(`/c/${clinic.slug}`); window.scrollTo(0, 0); };

  const loadSlots = useCallback(async (s: PublicService) => {
    setSlots(null);
    const from = days[0];
    const to = days[days.length - 1];
    const res = await fetch(`/api/v1/public/clinics/${clinic.slug}/slots?serviceId=${s.id}&from=${from}&to=${to}`);
    const data = res.ok ? (await res.json()) as Record<string, string[]> : {};
    setSlots(data);
    const first = Object.keys(data)[0] ?? '';
    setDay(d => (d && data[d] ? d : first));
  }, [clinic.slug]);
  useEffect(() => { if (service) loadSlots(service).catch(() => setSlots({})); }, [service, loadSlots]);
  useEffect(() => { window.scrollTo(0, 0); }, [step]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    if (!isValidCpf(form.cpf)) { setError('CPF inválido. Confira os números.'); return; }
    setBusy(true);
    try {
      const result = await api<BookingSent>(`/api/v1/public/clinics/${clinic.slug}/booking-requests`, 'POST', {
        serviceId: service!.id, startsAt: `${day}T${time}`, fullName: form.fullName, cpf: form.cpf, whatsapp: form.whatsapp,
        birthDate: form.birthDate || null, notes: form.notes || null, consent: form.consent, website: form.website,
        payMethod: pixFor(service) ? form.payMethod : null
      });
      setSent(result);
      setStep(result.pixCode ? 'pix' : 'enviado');
    } catch (e) {
      setError((e as Error).message);
      if (e instanceof ApiError && e.status === 409) {
        setTime('');
        setStep('horario');
        await loadSlots(service!);
      }
    } finally { setBusy(false); }
  }

  async function copyCode() {
    try {
      await navigator.clipboard.writeText(sent!.pixCode!);
      setCopied(true);
      setTimeout(() => setCopied(false), 2500);
    } catch {
      (document.getElementById('pix-code') as HTMLTextAreaElement | null)?.select();
    }
  }

  async function sendReceipt(file: File | undefined) {
    if (!file || !sent?.requestId || !sent.receiptToken) return;
    setError('');
    setReceipt('sending');
    try {
      const { blob, name } = await prepareReceipt(file);
      await uploadReceipt(clinic.slug, sent.requestId, sent.receiptToken, blob, name);
      setReceipt('sent');
    } catch (e) {
      setError((e as Error).message);
      setReceipt('idle');
    }
  }

  const stepIndex = { servico: 1, horario: 2, dados: 3, pix: 4, enviado: 4 }[step];
  const steps = pixFor(service) && form.payMethod === 'PIX' ? ['Atendimento', 'Dia e horário', 'Seus dados', 'Pagamento']
    : ['Atendimento', 'Dia e horário', 'Seus dados'];
  const chosen = service && day && time ? `${service.name} · ${whenLabel(`${day}T${time}`)}` : null;

  return (
    <div className={`site tpl-${template} mood-${mood} style-${style}`} style={vars}>
      <header className="site-nav">
        <div className="wrap nav-inner">
          <a className="site-brand" href={`/c/${clinic.slug}`} onClick={e => { e.preventDefault(); back(); }}>
            <Brand clinic={clinic} logo={logo} />
            <span>{clinic.displayName}</span>
          </a>
          <a className="btn-text" href={`/c/${clinic.slug}`} onClick={e => { e.preventDefault(); back(); }}>Voltar ao site</a>
        </div>
      </header>

      <main className="section booking-page">
        <div className="wrap narrow">
          <p className="eyebrow">Agendamento online</p>
          <h1 className="booking-title">
            {step === 'enviado' || receipt === 'sent' ? 'Pedido enviado!' : step === 'pix' ? 'Falta só o Pix' : 'Escolha seu horário'}</h1>
          {step !== 'enviado' && receipt !== 'sent' && (
            <ol className="booking-steps" aria-label="Etapas">
              {steps.map((label, i) => (
                <li key={label} className={i + 1 < stepIndex ? 'done' : i + 1 === stepIndex ? 'on' : ''}
                    aria-current={i + 1 === stepIndex ? 'step' : undefined}><span>{i + 1}</span>{label}</li>
              ))}
            </ol>
          )}
          {error && <p className="booking-error" role="alert">{error}</p>}

          {step === 'servico' && (
            <div className="booking-card">
              <h2>Qual atendimento?</h2>
              {services.length === 0 && <p>Nenhum atendimento disponível para agendar pelo site. Fale com a clínica.</p>}
              <div className="booking-options">
                {services.map(s => (
                  <button key={s.id} type="button" className={service?.id === s.id ? 'booking-option on' : 'booking-option'}
                          onClick={() => { setService(s); setTime(''); setStep('horario'); }}>
                    <strong>{s.name}</strong>
                    <span>{s.durationMinutes} min · {priceText(s, pix)}</span>
                  </button>
                ))}
              </div>
            </div>
          )}

          {step === 'horario' && service && (
            <div className="booking-card">
              <div className="booking-chosen">
                <span><strong>{service.name}</strong> · {service.durationMinutes} min · {priceText(service, pix)}</span>
                <button type="button" className="btn-text" onClick={() => setStep('servico')}>Trocar</button>
              </div>
              <h2>Qual dia?</h2>
              {slots === null && <p className="booking-muted">Carregando horários…</p>}
              {slots && Object.keys(slots).length === 0 && (
                <p className="booking-muted">Sem horários livres nas próximas semanas.{clinic.whatsapp && <> Chame a clínica no{' '}
                  <a href={whatsappLink(clinic.whatsapp, `Olá! Queria agendar ${service.name}. Tem horário?`)} target="_blank" rel="noreferrer">WhatsApp</a>.</>}</p>
              )}
              {slots && Object.keys(slots).length > 0 && (
                <>
                  <div className="booking-days" role="group" aria-label="Dias">
                    {days.map(d => {
                      const chip = dayChip(d);
                      const free = slots[d]?.length ?? 0;
                      return (
                        <button key={d} type="button" disabled={!free} aria-pressed={day === d}
                                aria-label={`${dayLabel(d)}${free ? `, ${free} horários` : ', sem horários'}`}
                                className={day === d ? 'on' : ''} onClick={() => { setDay(d); setTime(''); }}>
                          <span>{chip.day}</span><strong>{chip.number}</strong>
                        </button>
                      );
                    })}
                  </div>
                  <h2>Qual horário? <small>{day && dayLabel(day)}</small></h2>
                  <div className="booking-times" role="group" aria-label="Horários">
                    {(slots[day] ?? []).map(t => (
                      <button key={t} type="button" aria-pressed={time === t} className={time === t ? 'on' : ''}
                              onClick={() => setTime(t)}>{t}</button>
                    ))}
                  </div>
                  <div className="booking-actions">
                    <button type="button" className="btn btn-primary btn-lg" disabled={!time} onClick={() => setStep('dados')}>
                      Continuar <SiteIcon name="arrow" size={18} /></button>
                  </div>
                </>
              )}
            </div>
          )}

          {step === 'dados' && service && (
            <form className="booking-card booking-form" onSubmit={submit}>
              <div className="booking-chosen">
                <span><SiteIcon name="calendar" size={18} /> <strong>{chosen}</strong></span>
                <button type="button" className="btn-text" onClick={() => setStep('horario')}>Trocar</button>
              </div>
              <h2>Seus dados</h2>
              <label>Nome completo<input required maxLength={120} autoComplete="name" value={form.fullName}
                                         onChange={e => setForm({ ...form, fullName: e.target.value })} /></label>
              <div className="booking-row">
                <label>CPF<input required inputMode="numeric" maxLength={14} placeholder="000.000.000-00" value={form.cpf}
                                 onChange={e => setForm({ ...form, cpf: e.target.value })} /></label>
                <label>WhatsApp<input required inputMode="tel" maxLength={20} autoComplete="tel" placeholder="(98) 99999-0000"
                                      value={form.whatsapp} onChange={e => setForm({ ...form, whatsapp: e.target.value })} /></label>
              </div>
              <label>Data de nascimento <small>(opcional)</small><input type="date" value={form.birthDate} max={today()}
                                                                         onChange={e => setForm({ ...form, birthDate: e.target.value })} /></label>
              <label>Observação <small>(opcional)</small><textarea maxLength={500} rows={3} value={form.notes}
                                                                   placeholder="Ex.: é a primeira vez; dor no joelho direito"
                                                                   onChange={e => setForm({ ...form, notes: e.target.value })} /></label>
              {/* Campo escondido: pessoas não veem; robôs preenchem e o pedido é descartado. */}
              <input className="hp" tabIndex={-1} autoComplete="off" aria-hidden="true" value={form.website}
                     onChange={e => setForm({ ...form, website: e.target.value })} name="website" />
              <label className="booking-consent">
                <input type="checkbox" required checked={form.consent} onChange={e => setForm({ ...form, consent: e.target.checked })} />
                <span>Autorizo {clinic.displayName} a usar meu nome, CPF e WhatsApp para identificar meu cadastro e confirmar
                  o agendamento. O CPF também serve para emitir recibo.</span>
              </label>
              {pixFor(service) ? (
                <fieldset className="booking-pay">
                  <legend>Como prefere pagar? <small>{money(service.price)}</small></legend>
                  <label className={form.payMethod === 'PIX' ? 'on' : ''}>
                    <input type="radio" name="pay" checked={form.payMethod === 'PIX'} onChange={() => setForm({ ...form, payMethod: 'PIX' })} />
                    <span><strong>Pagar agora via Pix</strong><small>QR Code ou copia e cola; depois envie o comprovante</small></span>
                  </label>
                  <label className={form.payMethod === 'CLINICA' ? 'on' : ''}>
                    <input type="radio" name="pay" checked={form.payMethod === 'CLINICA'} onChange={() => setForm({ ...form, payMethod: 'CLINICA' })} />
                    <span><strong>Pagar na clínica</strong><small>No dia do atendimento</small></span>
                  </label>
                </fieldset>
              ) : (
                <p className="booking-muted">{priceText(service, pix)}. O horário fica reservado até a clínica confirmar pelo WhatsApp.</p>
              )}
              <div className="booking-actions">
                <button className="btn btn-primary btn-lg" disabled={busy}>
                  {busy ? 'Enviando…' : pixFor(service) && form.payMethod === 'PIX' ? 'Pedir horário e pagar' : 'Pedir este horário'}</button>
              </div>
            </form>
          )}

          {step === 'pix' && service && sent?.pixCode && receipt !== 'sent' && (
            <div className="booking-card booking-pix">
              <div className="booking-chosen">
                <span><SiteIcon name="calendar" size={18} /> <strong>{chosen}</strong></span>
                <span className="booking-code">Pedido {sent.code}</span>
              </div>
              <p className="booking-amount"><small>Valor</small><strong>{money(sent.amount)}</strong></p>
              <img className="booking-qr" alt="QR Code do Pix" width={220} height={220}
                   src={`data:image/svg+xml;utf8,${encodeURIComponent(sent.qrSvg ?? '')}`} />
              <ol className="booking-howto">
                <li>Abra o app do seu banco e escolha <strong>Pix → Ler QR Code</strong> ou <strong>Pix copia e cola</strong>.</li>
                <li>Confira o valor e o nome de quem recebe, e pague.</li>
                <li>Volte aqui e envie o comprovante.</li>
              </ol>
              <label className="booking-copy">Pix copia e cola
                <textarea id="pix-code" readOnly rows={3} value={sent.pixCode} onFocus={e => e.target.select()} />
              </label>
              <div className="booking-actions">
                <button type="button" className="btn btn-outline btn-lg" onClick={copyCode}>
                  {copied ? <><SiteIcon name="check" size={18} /> Código copiado</> : 'Copiar código Pix'}</button>
                <label className={`btn btn-primary btn-lg booking-upload${receipt === 'sending' ? ' busy' : ''}`}>
                  {receipt === 'sending' ? 'Enviando comprovante…' : 'Já paguei — enviar comprovante'}
                  <input type="file" accept="image/*,application/pdf" disabled={receipt === 'sending'}
                         onChange={e => { sendReceipt(e.target.files?.[0]); e.target.value = ''; }} />
                </label>
              </div>
              <p className="booking-muted">Seu horário fica reservado até a clínica conferir o pagamento e confirmar pelo WhatsApp.
                {clinic.whatsapp && <> Se preferir, <a target="_blank" rel="noreferrer"
                  href={whatsappLink(clinic.whatsapp, `Olá! Paguei o Pix do pedido ${sent.code} (${chosen}). Segue o comprovante.`)}>
                  mande o comprovante pelo WhatsApp</a>.</>}</p>
            </div>
          )}

          {(step === 'enviado' || receipt === 'sent') && service && (
            <div className="booking-card booking-done">
              <span className="booking-check"><SiteIcon name="check" size={32} /></span>
              <p>Recebemos seu pedido de <strong>{chosen}</strong>{receipt === 'sent' && <> e o comprovante do Pix</>}.</p>
              <p className="booking-muted">{receipt === 'sent' ? 'A clínica vai conferir o pagamento e confirmar' : 'A clínica vai confirmar'} pelo WhatsApp {form.whatsapp}. Se não puder nesse horário, ela te chama para combinar outro.</p>
              <div className="booking-actions">
                {clinic.whatsapp && (
                  <a className="btn btn-primary btn-lg" target="_blank" rel="noreferrer"
                     href={whatsappLink(clinic.whatsapp, requestSentMessage(clinic.displayName, form.fullName, service.name, `${day}T${time}`))}>
                    <SiteIcon name="whatsapp" size={20} /> Avisar a clínica no WhatsApp</a>
                )}
                <button type="button" className="btn-text" onClick={back}>Voltar ao site</button>
              </div>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}
