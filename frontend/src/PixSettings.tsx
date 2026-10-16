import { useEffect, useState, type FormEvent } from 'react';
import { api, type PixKeyType, type PixSettings as Settings } from './api';
import { maskCpf, maskPhone } from './format';
import { useUi } from './ui';

export const TYPES: { id: PixKeyType; label: string; placeholder: string; inputMode?: 'numeric' | 'tel' | 'email' }[] = [
  { id: 'TELEFONE', label: 'Celular', placeholder: '(98) 99999-0000', inputMode: 'tel' },
  { id: 'CPF', label: 'CPF', placeholder: '000.000.000-00', inputMode: 'numeric' },
  { id: 'CNPJ', label: 'CNPJ', placeholder: '00.000.000/0000-00', inputMode: 'numeric' },
  { id: 'EMAIL', label: 'E-mail', placeholder: 'voce@email.com', inputMode: 'email' },
  { id: 'ALEATORIA', label: 'Aleatória', placeholder: '123e4567-e89b-12d3-a456-426614174000' }
];

/**
 * Chave Pix da clínica. Com ela, o site oferece "Pagar agora via Pix" (com o valor certo e o código do pedido) e o
 * financeiro manda o Pix junto do lembrete de quem está devendo. Sem banco no meio e sem taxa.
 */
export function PixSettings({ owner, defaultName }: { owner: boolean; defaultName: string }) {
  const { toast, confirm } = useUi();
  const [form, setForm] = useState<Settings | null>(null);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    api<Settings>('/api/v1/clinic/pix').then(s => { setForm(s); setSaved(!!s.keyType); }).catch(e => setError((e as Error).message));
  }, []);
  if (!form) return error ? <p className="error">{error}</p> : null;

  const type = TYPES.find(t => t.id === (form.keyType ?? 'TELEFONE'))!;
  async function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      const result = await api<Settings>('/api/v1/clinic/pix', 'PUT', { ...form, keyType: form!.keyType ?? 'TELEFONE' });
      setForm(result);
      setSaved(true);
      toast('Chave Pix salva');
    } catch (e) { setError((e as Error).message); }
  }
  async function remove() {
    if (!await confirm('Remover a chave Pix? O site deixa de oferecer "Pagar agora via Pix".', { confirmLabel: 'Remover', danger: true })) return;
    try {
      setForm(await api<Settings>('/api/v1/clinic/pix', 'PUT', { keyType: null, key: null, name: null, city: null }));
      setSaved(false);
      toast('Chave Pix removida');
    } catch (e) { setError((e as Error).message); }
  }

  return (
    <form onInput={() => setError('')} className="card form" onSubmit={save}>
      <h3>Pix</h3>
      <p className="muted small">
        Com a chave cadastrada, o paciente pode pagar pelo site na hora de agendar (QR Code com o valor certo) e
        enviar o comprovante; você confere no app do banco antes de confirmar. O dinheiro cai direto na sua conta, sem taxa.
      </p>
      <fieldset disabled={!owner} className="stack">
        <div className="chips" role="radiogroup" aria-label="Tipo de chave">
          {TYPES.map(t => (
            <button key={t.id} type="button" role="radio" aria-checked={type.id === t.id} className={type.id === t.id ? 'chip on' : 'chip'}
                    onClick={() => setForm({ ...form, keyType: t.id, key: form.keyType === t.id ? form.key : '' })}>{t.label}</button>
          ))}
        </div>
        <label>Chave Pix<input required inputMode={type.inputMode} placeholder={type.placeholder} value={form.key ?? ''}
                               onChange={e => setForm({ ...form, keyType: type.id, key: type.id === 'TELEFONE' ? maskPhone(e.target.value)
                                 : type.id === 'CPF' ? maskCpf(e.target.value) : e.target.value })} /></label>
        <div className="row">
          <label>Nome de quem recebe<input required maxLength={60} placeholder={defaultName} value={form.name ?? ''}
                                           onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>Cidade<input required maxLength={40} placeholder="São Luís" value={form.city ?? ''}
                              onChange={e => setForm({ ...form, city: e.target.value })} /></label>
        </div>
        <p className="muted small">Nome e cidade vão no código do Pix (sem acento, como o banco mostra).</p>
      </fieldset>
      {error && <p className="error-box" role="alert">{error}</p>}
      {owner && (
        <div className="actions end">
          {saved && <button type="button" className="link-button danger" onClick={remove}>Remover chave</button>}
          <button className="primary">Salvar Pix</button>
        </div>
      )}
    </form>
  );
}
