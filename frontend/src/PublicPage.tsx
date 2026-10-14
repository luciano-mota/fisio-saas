import { useEffect, useState, type CSSProperties } from 'react';
import { money, whatsappLink, type PublicClinic, type PublicPackage, type PublicService } from './api';
import { themeVariables } from './color';

/**
 * Vitrine pública da clínica, no estilo do tema dela.
 * Nesta versão o botão leva ao WhatsApp com a escolha preenchida; o agendamento online com
 * horários livres e Pix com comprovante vem nos próximos cartões.
 */
export function PublicPage({ slug }: { slug: string }) {
  const [clinic, setClinic] = useState<PublicClinic | null | undefined>(undefined);

  useEffect(() => {
    fetch(`/api/v1/public/clinics/${slug}`)
      .then(res => (res.ok ? res.json() : null))
      .then((data: PublicClinic | null) => {
        setClinic(data);
        if (data) document.title = data.displayName;
      })
      .catch(() => setClinic(null));
  }, [slug]);

  if (clinic === undefined) return <div className="center muted">Carregando…</div>;
  if (clinic === null) return <div className="center"><h1>Página não encontrada</h1></div>;

  return (
    <div className="public" style={themeVariables(clinic.theme) as CSSProperties}>
      <header className="public-hero">
        <h1>{clinic.displayName}</h1>
        {clinic.professionalName && (
          <p>{clinic.professionalName}{clinic.crefito && ` · CREFITO ${clinic.crefito}`}</p>
        )}
        {clinic.about && <p className="about">{clinic.about}</p>}
      </header>

      <main className="public-content">
        <h2>Serviços</h2>
        {clinic.services.length === 0 && <p className="muted">Em breve.</p>}
        <div className="grid">
          {clinic.services.map(service => <ServiceCard key={service.id} clinic={clinic} service={service} />)}
        </div>
        {clinic.address && <p className="muted address">📍 {clinic.address}</p>}
      </main>
    </div>
  );
}

/** "10 sessões" fica como está; "Pacote mensal" vira "Pacote mensal (8 sessões)". */
function packageLabel(p: PublicPackage): string {
  return p.name.includes(String(p.sessionCount)) ? p.name : `${p.name} (${p.sessionCount} sessões)`;
}

function ServiceCard({ clinic, service }: { clinic: PublicClinic; service: PublicService }) {
  const [choice, setChoice] = useState<string>('single');
  const selected: PublicPackage | undefined = service.packages.find(p => p.id === choice);
  const price = selected ? selected.price : service.price;
  const label = selected ? packageLabel(selected) : 'sessão avulsa';

  const message = `Olá! Vi sua página e quero agendar ${service.name} – ${label}. Quais horários você tem?`;

  return (
    <article className="service-card">
      <h3>{service.name}</h3>
      {service.description && <p className="muted">{service.description}</p>}
      <p className="muted small">{service.durationMinutes} min por sessão</p>

      {service.packages.length > 0 && (
        <label>Quantidade
          <select value={choice} onChange={e => setChoice(e.target.value)}>
            <option value="single">Sessão avulsa</option>
            {service.packages.map(p => <option key={p.id} value={p.id}>{packageLabel(p)}</option>)}
          </select>
        </label>
      )}

      <p className={price === null ? 'price on-request' : 'price'}>{money(price)}</p>
      {selected && selected.price !== null && selected.sessionCount > 1 && (
        <p className="muted small">{money(selected.price / selected.sessionCount)} por sessão</p>
      )}

      {service.onlineBooking && clinic.whatsapp ? (
        <a className="cta" href={whatsappLink(clinic.whatsapp, message)} target="_blank" rel="noreferrer">
          Agendar pelo WhatsApp
        </a>
      ) : (
        <p className="muted small">Agendamento direto com a clínica.</p>
      )}
    </article>
  );
}
