import { useCallback, useEffect, useState } from 'react';
import { api, money, whatsappLink, type BookingRequest } from './api';
import { acceptedMessage, declinedMessage, whenLabel } from './booking';
import { formatCpf, formatPhone } from './format';
import { navigate } from './router';
import { EmptyState, Icon, PageHeader, Sheet, useUi } from './ui';

const STATUS: Record<BookingRequest['status'], string> = {
  PENDING: 'Aguardando', ACCEPTED: 'Aceita', DECLINED: 'Recusada', EXPIRED: 'Expirada', CANCELED: 'Cancelada'
};

/**
 * Pedidos de agendamento feitos pelo site. O CPF acha o paciente já cadastrado; sem cadastro, ele é criado ao
 * aceitar. Depois de responder, o painel oferece a mensagem pronta para o WhatsApp do paciente.
 */
export function RequestsTab({ owner, onChanged }: { owner: boolean; onChanged: () => void }) {
  const [items, setItems] = useState<BookingRequest[] | null>(null);
  const [all, setAll] = useState(false);
  const [error, setError] = useState('');
  const [answered, setAnswered] = useState<{ item: BookingRequest; accepted: boolean; reason?: string } | null>(null);
  const [declining, setDeclining] = useState<BookingRequest | null>(null);

  const load = useCallback(() => api<BookingRequest[]>(`/api/v1/booking-requests${all ? '?status=ALL' : ''}`).then(setItems), [all]);
  useEffect(() => { load().catch(e => setError((e as Error).message)); }, [load]);
  const done = async () => { await load(); onChanged(); };

  return (
    <section>
      <PageHeader title="Solicitações" subtitle="Pedidos de horário feitos pelo seu site. A sessão só entra na agenda quando você aceita." />
      <label className="switch small"><input type="checkbox" checked={all} onChange={e => setAll(e.target.checked)} />
        <span>Mostrar respondidas (últimos 60 dias)</span></label>
      {error && <p className="error-box" role="alert">{error}</p>}
      {items?.length === 0 && (
        <EmptyState icon="bell" title="Nenhuma solicitação aguardando"
                    text="Ative o agendamento pelo site em Clínica → Horário de atendimento para receber pedidos.">
          <button onClick={() => navigate('/painel/clinica')}>Configurar horário de atendimento</button>
        </EmptyState>
      )}
      <ul className="cards">
        {items?.map(item => (
          <RequestCard key={item.id} item={item} owner={owner} onError={setError}
                       onAccepted={async () => { setAnswered({ item, accepted: true }); await done(); }}
                       onDecline={() => setDeclining(item)} />
        ))}
      </ul>

      {declining && (
        <DeclineSheet item={declining} onClose={() => setDeclining(null)}
                      onDone={async reason => { setAnswered({ item: declining, accepted: false, reason }); setDeclining(null); await done(); }} />
      )}
      {answered && (
        <Sheet title={answered.accepted ? 'Solicitação aceita' : 'Solicitação recusada'} onClose={() => setAnswered(null)}>
          <div className="form">
            <p>{answered.accepted
              ? `A sessão de ${answered.item.fullName} foi para a agenda em ${whenLabel(answered.item.startsAt)}.`
              : `O pedido de ${answered.item.fullName} foi recusado.`} Avise pelo WhatsApp:</p>
            <a className="button wa big" target="_blank" rel="noreferrer" onClick={() => setAnswered(null)}
               href={whatsappLink(answered.item.whatsapp, answered.accepted
                 ? acceptedMessage(answered.item.fullName, answered.item.serviceName, answered.item.startsAt)
                 : declinedMessage(answered.item.fullName, answered.item.serviceName, answered.item.startsAt, answered.reason))}>
              <Icon name="whatsapp" size={20} /> Enviar mensagem para {answered.item.fullName.split(' ')[0]}</a>
            {answered.accepted && <button onClick={() => navigate('/painel/agenda')}>Ver na agenda</button>}
          </div>
        </Sheet>
      )}
    </section>
  );
}

function RequestCard({ item, owner, onAccepted, onDecline, onError }: {
  item: BookingRequest; owner: boolean; onAccepted: () => void; onDecline: () => void; onError: (m: string) => void;
}) {
  const { confirm } = useUi();
  const [update, setUpdate] = useState(false);
  const [link, setLink] = useState<string>('');
  const [busy, setBusy] = useState(false);
  const open = item.status === 'PENDING' || item.status === 'EXPIRED';
  const p = item.patient;
  const nameDiffers = p && p.fullName.trim().toLowerCase() !== item.fullName.trim().toLowerCase();
  const phoneDiffers = p && p.whatsapp !== item.whatsapp;

  async function accept() {
    const who = p ? `o cadastro de ${p.fullName}` : link ? 'o paciente escolhido' : 'um cadastro novo';
    if (!await confirm(`Aceitar e colocar ${item.serviceName} de ${item.fullName} na agenda em ${whenLabel(item.startsAt)}, usando ${who}?`,
      { title: 'Aceitar solicitação', confirmLabel: 'Aceitar' })) return;
    setBusy(true);
    onError('');
    try {
      await api(`/api/v1/booking-requests/${item.id}/accept`, 'POST', { linkPatientId: link || null, updatePatient: update });
      onAccepted();
    } catch (e) { onError((e as Error).message); } finally { setBusy(false); }
  }

  return (
    <li className={open ? 'request-card' : 'request-card inactive'}>
      <div className="request-head">
        <div className="req-time"><strong>{item.startsAt.slice(11, 16)}</strong><span>{whenLabel(item.startsAt).split(' às')[0]}</span></div>
        <div className="grow">
          <strong>{item.fullName}</strong>
          <span className={`pill status-req-${item.status.toLowerCase()}`}>{STATUS[item.status]}</span>
          <div className="meta">
            <span>{item.serviceName}{item.servicePrice !== null && ` · ${money(item.servicePrice)}`}</span>
            <span>CPF {formatCpf(item.cpf)}</span>
            <a href={whatsappLink(item.whatsapp, `Olá, ${item.fullName.split(' ')[0]}!`)} target="_blank" rel="noreferrer">
              <Icon name="whatsapp" size={14} /> {formatPhone(item.whatsapp)}</a>
            {item.birthDate && <span>nasc. {item.birthDate.split('-').reverse().join('/')}</span>}
          </div>
          {item.notes && <p className="appt-notes">“{item.notes}”</p>}
          {item.declineReason && <p className="appt-notes">Motivo: {item.declineReason}</p>}
          {item.status === 'EXPIRED' && <p className="small warn">O prazo para responder passou; ainda dá para aceitar se o horário estiver livre.</p>}
        </div>
      </div>

      {open && (
        <div className="match">
          {p ? (
            <>
              <p><Icon name="check" size={16} /> <strong>Paciente já cadastrado</strong> (mesmo CPF): {p.fullName}
                {p.whatsapp && ` · ${formatPhone(p.whatsapp)}`}{!p.active && ' · desativado (será reativado)'}</p>
              {(nameDiffers || phoneDiffers) && (
                <label className="switch small"><input type="checkbox" checked={update} onChange={e => setUpdate(e.target.checked)} />
                  <span>Atualizar o cadastro com {[nameDiffers && 'o nome', phoneDiffers && 'o WhatsApp'].filter(Boolean).join(' e ')} do pedido</span></label>
              )}
            </>
          ) : item.suggestions.length > 0 ? (
            <fieldset className="stack">
              <p><Icon name="alert" size={16} /> <strong>Possível mesmo paciente</strong> (mesmo WhatsApp, cadastro sem CPF):</p>
              {item.suggestions.map(s => (
                <label key={s.id} className="check"><input type="radio" name={`link-${item.id}`} checked={link === s.id}
                                                         onChange={() => setLink(s.id)} />
                  É {s.fullName} — completar o cadastro com o CPF</label>
              ))}
              <label className="check"><input type="radio" name={`link-${item.id}`} checked={link === ''} onChange={() => setLink('')} />
                Não, é outra pessoa — cadastrar novo paciente</label>
            </fieldset>
          ) : (
            <p className="muted small"><Icon name="plus" size={14} /> Paciente novo: o cadastro (nome, CPF e WhatsApp) é criado ao aceitar.</p>
          )}
        </div>
      )}

      {open && owner && (
        <div className="actions end">
          <button disabled={busy} onClick={onDecline}>Recusar</button>
          <button className="primary" disabled={busy} onClick={accept}><Icon name="check" size={18} /> Aceitar</button>
        </div>
      )}
    </li>
  );
}

function DeclineSheet({ item, onClose, onDone }: { item: BookingRequest; onClose: () => void; onDone: (reason: string) => void }) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  return (
    <Sheet title={`Recusar pedido de ${item.fullName.split(' ')[0]}`} onClose={onClose}>
      <form className="form" onSubmit={async e => {
        e.preventDefault();
        try {
          await api(`/api/v1/booking-requests/${item.id}/decline`, 'POST', { reason });
          onDone(reason);
        } catch (err) { setError((err as Error).message); }
      }}>
        <p className="muted small">{item.serviceName} em {whenLabel(item.startsAt)}. O pedido fica guardado como recusado.</p>
        <label>Motivo (opcional, vai na mensagem)<input maxLength={300} value={reason} placeholder="Ex.: agenda cheia nesse dia"
                                                       onChange={e => setReason(e.target.value)} /></label>
        {error && <p className="error-box" role="alert">{error}</p>}
        <div className="sheet-actions">
          <button type="button" onClick={onClose}>Voltar</button>
          <button className="primary danger-fill">Recusar pedido</button>
        </div>
      </form>
    </Sheet>
  );
}
