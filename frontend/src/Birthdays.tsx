import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, whatsappLink, type Birthday, type BirthdayList } from './api';
import { birthdayDayLabel, birthdayMessage, EMOJIS, insertAt, rangeFor, seenKey, type BirthdayRange } from './birthdays';
import { navigate } from './router';
import { Avatar, EmptyState, Icon, Modal, Sheet, useUi } from './ui';

const RANGES: { id: BirthdayRange; label: string }[] = [
  { id: 'hoje', label: 'Hoje' }, { id: 'semana', label: 'Próximos 7 dias' }, { id: 'mes', label: 'Este mês' }
];

/** Sub-aba Pacientes → Aniversariantes: ativos e desativados, com mensagem de parabéns pelo WhatsApp. */
export function BirthdaysView({ owner, clinicName }: { owner: boolean; clinicName: string }) {
  const [range, setRange] = useState<BirthdayRange>('semana');
  const [data, setData] = useState<BirthdayList | null>(null);
  const [today, setToday] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [writing, setWriting] = useState<Birthday | null>(null);
  const [editingTemplate, setEditingTemplate] = useState(false);

  // Primeiro descobre "hoje" no fuso da clínica; depois busca o período escolhido.
  useEffect(() => {
    api<BirthdayList>('/api/v1/patients/birthdays').then(d => setToday(d.today)).catch(e => setError((e as Error).message));
  }, []);
  useEffect(() => {
    if (!today) return;
    const { from, to } = rangeFor(range, today);
    api<BirthdayList>(`/api/v1/patients/birthdays?from=${from}&to=${to}`).then(setData).catch(e => setError((e as Error).message));
  }, [range, today]);

  return (
    <div className="stack">
      <div className="bday-toolbar">
        <div className="chips" role="radiogroup" aria-label="Período">
          {RANGES.map(r => (
            <button key={r.id} type="button" role="radio" aria-checked={range === r.id}
                    className={range === r.id ? 'chip on' : 'chip'} onClick={() => setRange(r.id)}>{r.label}</button>
          ))}
        </div>
        {owner && data && <button className="link-button" onClick={() => setEditingTemplate(true)}>
          <Icon name="edit" size={16} /> Mensagem padrão</button>}
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      {data?.items.length === 0 && (
        <EmptyState icon="gift" title="Nenhum aniversariante neste período"
                    text="Cadastre a data de nascimento dos pacientes para lembrar dos aniversários." />
      )}
      <ul className="cards">
        {data?.items.map(b => (
          <li key={b.id + b.date} className={b.active ? 'row-card' : 'row-card inactive'}>
            <Avatar name={b.fullName} />
            <a className="grow row-link" href={`/painel/pacientes/${b.id}`}
               onClick={e => { e.preventDefault(); navigate('/painel/pacientes/' + b.id); }}>
              <strong>{b.fullName}</strong>{!b.active && <span className="pill">desativado</span>}
              <span className="meta">
                {birthdayDayLabel(b.date, data.today) === 'hoje' ? <b className="bday-today">🎂 Hoje</b> : birthdayDayLabel(b.date, data.today)}
                {' · '}completa {b.age} {b.age === 1 ? 'ano' : 'anos'}
              </span>
            </a>
            <button className="bday-send" onClick={() => setWriting(b)}><Icon name="whatsapp" size={18} /> Parabenizar</button>
          </li>
        ))}
      </ul>
      {writing && data && <BirthdayMessageSheet birthday={writing} template={data.template} clinicName={clinicName}
                                                onClose={() => setWriting(null)} />}
      {editingTemplate && data && (
        <TemplateSheet template={data.template} custom={data.customTemplate} onClose={() => setEditingTemplate(false)}
                       onSaved={t => { setData({ ...data, template: t.template, customTemplate: t.customTemplate }); setEditingTemplate(false); }} />
      )}
    </div>
  );
}

/** Caixa de texto com emojis: o emoji entra onde está o cursor. */
function MessageEditor({ value, onChange, label, maxLength = 1000 }: {
  value: string; onChange: (v: string) => void; label: string; maxLength?: number;
}) {
  const box = useRef<HTMLTextAreaElement>(null);
  const [emojis, setEmojis] = useState(false);
  function add(text: string) {
    const area = box.current;
    const result = insertAt(value, text, area?.selectionStart, area?.selectionEnd);
    onChange(result.text.slice(0, maxLength));
    requestAnimationFrame(() => { area?.focus(); area?.setSelectionRange(result.cursor, result.cursor); });
  }
  return (
    <div className="msg-editor">
      <label>{label}
        <textarea ref={box} rows={6} maxLength={maxLength} value={value} onChange={e => onChange(e.target.value)} /></label>
      <div className="msg-tools">
        <button type="button" className={emojis ? 'chip on' : 'chip'} aria-expanded={emojis} onClick={() => setEmojis(o => !o)}>
          😊 Emojis</button>
        <span className="muted small">{value.length}/{maxLength}</span>
      </div>
      {emojis && (
        <div className="emoji-grid" role="group" aria-label="Escolha um emoji">
          {EMOJIS.map(e => <button key={e} type="button" aria-label={`Inserir ${e}`} onClick={() => add(e)}>{e}</button>)}
        </div>
      )}
    </div>
  );
}

/** Escrever e mandar os parabéns pelo WhatsApp (abre o app com o texto pronto). */
export function BirthdayMessageSheet({ birthday, template, clinicName, onClose }: {
  birthday: Birthday; template: string; clinicName: string; onClose: () => void;
}) {
  const [text, setText] = useState(() => birthdayMessage(template, { fullName: birthday.fullName, age: birthday.age, clinicName }));
  return (
    <Sheet title={`Parabéns para ${birthday.fullName.split(' ')[0]}`} onClose={onClose}>
      <div className="form">
        <p className="muted small">Escreva do seu jeito, que tal oferecer uma sessão de presente ou um desconto? O WhatsApp abre
          com a mensagem pronta; é só tocar em enviar.</p>
        <MessageEditor label="Mensagem" value={text} onChange={setText} />
        {!birthday.whatsapp && <p className="notice small">Este paciente não tem WhatsApp cadastrado. Edite o cadastro para mandar a mensagem.</p>}
        <div className="sheet-actions">
          <button type="button" onClick={onClose}>Fechar</button>
          {birthday.whatsapp
            ? <a className="button primary" href={whatsappLink(birthday.whatsapp, text.trim())} target="_blank" rel="noreferrer"
                 onClick={() => setTimeout(onClose, 300)} aria-disabled={!text.trim()}>
                <Icon name="whatsapp" size={18} /> Enviar no WhatsApp</a>
            : <button className="primary" disabled>Enviar no WhatsApp</button>}
        </div>
      </div>
    </Sheet>
  );
}

/** Modelo de mensagem da clínica: {nome}, {idade} e {clinica} são trocados na hora de enviar. */
function TemplateSheet({ template, custom, onClose, onSaved }: {
  template: string; custom: boolean; onClose: () => void; onSaved: (t: { template: string; customTemplate: boolean }) => void;
}) {
  const { toast } = useUi();
  const [text, setText] = useState(template);
  const [error, setError] = useState('');
  async function save(event: FormEvent | null, value = text) {
    event?.preventDefault();
    setError('');
    try {
      const result = await api<{ template: string; customTemplate: boolean }>('/api/v1/patients/birthday-message', 'PUT', { message: value });
      toast(result.customTemplate ? 'Mensagem padrão salva' : 'Mensagem padrão restaurada');
      onSaved(result);
    } catch (e) { setError((e as Error).message); }
  }
  return (
    <Sheet title="Mensagem padrão de aniversário" onClose={onClose}>
      <form className="form" onSubmit={save} onInput={() => setError('')}>
        <MessageEditor label="Modelo" value={text} onChange={setText} />
        <p className="muted small">Use <code>{'{nome}'}</code> para o primeiro nome, <code>{'{idade}'}</code> para a idade
          e <code>{'{clinica}'}</code> para o nome da clínica.</p>
        {error && <p className="error-box" role="alert">{error}</p>}
        <div className="sheet-actions">
          {custom && <button type="button" className="link-button" onClick={() => save(null, '')}>Voltar ao padrão</button>}
          <button type="button" onClick={onClose}>Cancelar</button>
          <button className="primary" disabled={!text.trim()}>Salvar modelo</button>
        </div>
      </form>
    </Sheet>
  );
}

function readSeen(key: string): boolean {
  try { return localStorage.getItem(key) === '1'; } catch { return false; }
}
function markSeen(key: string) {
  try { localStorage.setItem(key, '1'); } catch { /* sem armazenamento: o aviso volta no próximo acesso */ }
}

/** Aviso animado ao abrir o painel quando há aniversariantes hoje (uma vez por dia para cada pessoa). */
export function BirthdayGreeting({ email, clinicName }: { email: string; clinicName: string }) {
  const [data, setData] = useState<BirthdayList | null>(null);
  const [open, setOpen] = useState(false);
  const [writing, setWriting] = useState<Birthday | null>(null);
  useEffect(() => {
    api<BirthdayList>('/api/v1/patients/birthdays').then(d => {
      setData(d);
      if (d.items.length > 0 && !readSeen(seenKey(email, d.today))) setOpen(true);
    }).catch(() => undefined);
  }, [email]);
  if (!data) return null;
  const close = () => { markSeen(seenKey(email, data.today)); setOpen(false); };
  const names = data.items;

  return (
    <>
      {open && (
        <Modal title={names.length === 1 ? 'Tem aniversariante hoje!' : `${names.length} aniversariantes hoje!`} onClose={close} className="bday-modal">
          <div className="bday-confetti" aria-hidden="true">
            {Array.from({ length: 18 }, (_, i) => <i key={i} style={{ ['--i' as string]: i }} />)}
          </div>
          <div className="bday-cake" aria-hidden="true">🎂</div>
          <p className="bday-lead">Que tal mandar os parabéns? Um presente, como uma sessão grátis ou um desconto, deixa o dia
            ainda mais especial e traz o paciente de volta.</p>
          <ul className="bday-names">
            {names.map(b => (
              <li key={b.id}>
                <span><strong>{b.fullName}</strong> <span className="muted small">· {b.age} anos{!b.active && ' · desativado'}</span></span>
                <button className="bday-send" onClick={() => { close(); setWriting(b); }}>
                  <Icon name="whatsapp" size={16} /> Parabenizar</button>
              </li>
            ))}
          </ul>
          <div className="actions end">
            <button type="button" onClick={() => { close(); navigate('/painel/pacientes/aniversariantes'); }}>Ver aniversariantes</button>
            <button type="button" className="primary" onClick={close}>Fechar</button>
          </div>
        </Modal>
      )}
      {writing && <BirthdayMessageSheet birthday={writing} template={data.template} clinicName={clinicName}
                                        onClose={() => setWriting(null)} />}
    </>
  );
}
