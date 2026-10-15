import { useState, type FormEvent } from 'react';
import { api, type ClinicProfile } from './api';
import { navigate } from './router';
import { BookingSettings } from './BookingSettings';
import { Icon, PageHeader, useUi } from './ui';

/** Dados da clínica, endereço da página pública e regra de falta dos pacotes. */
export function ClinicTab({ owner, profile, onSaved }: {
  owner: boolean; profile: ClinicProfile; onSaved: (p: ClinicProfile) => void;
}) {
  const { toast } = useUi();
  const [form, setForm] = useState<ClinicProfile>(profile);
  const [error, setError] = useState('');
  const text = (key: keyof ClinicProfile) => (form[key] ?? '') as string;
  const set = (key: keyof ClinicProfile, value: string | boolean) => setForm({ ...form, [key]: value });

  async function save(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      const saved = await api<ClinicProfile>('/api/v1/clinic', 'PUT', form);
      setForm(saved);
      onSaved(saved);
      toast('Salvo! A página pública já usa as novas informações.');
    } catch (e) { setError((e as Error).message); }
  }

  const publicUrl = `${window.location.origin}/c/${form.slug}`;

  return (
    <section>
      <PageHeader title="Clínica"
        subtitle={<>Logo, cores, fotos e textos do site ficam em{' '}
          <a href="/painel/site" onClick={e => { e.preventDefault(); navigate('/painel/site'); }}>Site</a>.</>} />
      <form onSubmit={save} className="stack">
        <fieldset disabled={!owner} className="stack">
          <div className="card form">
            <h3>Dados da clínica</h3>
            <label>Nome da clínica<input required maxLength={120} value={text('displayName')}
                                         onChange={e => set('displayName', e.target.value)} /></label>
            <div className="row">
              <label>Profissional responsável<input maxLength={120} value={text('professionalName')}
                                                    onChange={e => set('professionalName', e.target.value)} /></label>
              <label>CREFITO<input maxLength={30} value={text('crefito')} onChange={e => set('crefito', e.target.value)} /></label>
            </div>
            <div className="row">
              <label>WhatsApp<input inputMode="tel" maxLength={20} placeholder="(98) 99999-0000" value={text('whatsapp')}
                                    onChange={e => set('whatsapp', e.target.value)} /></label>
              <label>CPF/CNPJ (para recibos, não aparece no site)<input maxLength={18} value={text('document')}
                                                                       onChange={e => set('document', e.target.value)} /></label>
            </div>
            <label>Endereço<input maxLength={255} value={text('address')} onChange={e => set('address', e.target.value)} /></label>
          </div>

          <div className="card form">
            <h3>Página pública</h3>
            <label>Endereço da página
              <div className="slug"><span className="muted">{window.location.origin}/c/</span>
                <input required pattern="[a-z0-9]([a-z0-9\-]{1,61}[a-z0-9])?" value={form.slug}
                       onChange={e => set('slug', e.target.value.toLowerCase())} /></div>
            </label>
            <label className="switch"><input type="checkbox" checked={form.publicPageEnabled}
                                             onChange={e => set('publicPageEnabled', e.target.checked)} />
              <span>Página pública ativada</span></label>
            {profile.publicPageEnabled && (
              <a className="button ghost" href={publicUrl} target="_blank" rel="noreferrer">
                <Icon name="external" size={18} /> Abrir minha página</a>
            )}
          </div>

          <div className="card form">
            <h3>Pacotes</h3>
            <label className="switch"><input type="checkbox" checked={form.noShowConsumesSession}
                                             onChange={e => set('noShowConsumesSession', e.target.checked)} />
              <span>Falta sem aviso consome sessão do pacote</span></label>
            <p className="muted small">Vale para as faltas marcadas daqui em diante; faltas já registradas não mudam.</p>
          </div>

          <div className="card form">
            <h3>Pagamentos no cartão</h3>
            <label className="switch"><input type="checkbox" checked={form.passCardFee}
                                             onChange={e => set('passCardFee', e.target.checked)} />
              <span>Repassar a taxa da maquininha ao paciente</span></label>
            <p className="muted small">Ligado: o sistema mostra quanto cobrar na maquininha para você receber o valor cheio
              (ex.: R$ 600 em 3x com taxa de 4,99% → cobrar R$ 631,51). Desligado: a taxa sai do seu valor.
              Taxas e prazos ficam em{' '}
              <a href="/painel/financeiro/maquininhas" onClick={e => { e.preventDefault(); navigate('/painel/financeiro/maquininhas'); }}>
                Financeiro → Maquininhas</a>.</p>
          </div>
        </fieldset>
        {error && <p className="error" role="alert">{error}</p>}
        {owner && <div className="save-bar"><button className="primary">Salvar</button></div>}
      </form>
      <div className="stack booking-block"><BookingSettings owner={owner} /></div>
    </section>
  );
}
