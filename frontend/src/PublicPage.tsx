import { useEffect, useState, type CSSProperties } from 'react';
import { logoUrl, money, whatsappLink, type PublicClinic, type PublicPackage, type PublicService } from './api';
import { themeVariables } from './color';
import { formatPhone } from './format';

/** Iniciais para quando a clínica ainda não enviou a logo ("Clínica Bem Estar" → "CB"). */
export function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter(w => w.length > 2 || /^[A-ZÁ-Ú]/.test(w));
  return (words.length ? words : [name]).slice(0, 2).map(w => w[0]).join('').toUpperCase();
}

/** Ícone da aba do navegador e cor da barra do celular seguem a identidade da clínica. */
function useBranding(clinic: PublicClinic | null | undefined, logo: string | null) {
  useEffect(() => {
    if (!clinic) return;
    document.title = clinic.displayName;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', clinic.theme.primary);
    if (!logo) return;
    let icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
    if (!icon) {
      icon = document.createElement('link');
      icon.rel = 'icon';
      document.head.appendChild(icon);
    }
    icon.href = logo;
  }, [clinic, logo]);
}

function Brand({ clinic, logo, size }: { clinic: PublicClinic; logo: string | null; size: 'small' | 'large' }) {
  return logo
    ? <img className={`brand-logo ${size}`} src={logo} alt={`Logo ${clinic.displayName}`} />
    : <span className={`brand-monogram ${size}`} aria-hidden="true">{initials(clinic.displayName)}</span>;
}

/**
 * Site público da clínica, montado a partir da logo e das cores dela: barra fixa com a logo pequena e o
 * WhatsApp sempre à mão, logo em destaque no topo, serviços em vitrine e rodapé com contato.
 */
export function PublicPage({ slug }: { slug: string }) {
  const [clinic, setClinic] = useState<PublicClinic | null | undefined>(undefined);

  useEffect(() => {
    fetch(`/api/v1/public/clinics/${slug}`)
      .then(res => (res.ok ? res.json() : null))
      .then((data: PublicClinic | null) => setClinic(data))
      .catch(() => setClinic(null));
  }, [slug]);

  const logo = clinic ? logoUrl(clinic.logoVersion, clinic.slug) : null;
  useBranding(clinic, logo);

  if (clinic === undefined) return <div className="center muted">Carregando…</div>;
  if (clinic === null) return <div className="center"><h1>Página não encontrada</h1></div>;

  const hello = clinic.whatsapp
    ? whatsappLink(clinic.whatsapp, `Olá! Vi a página da ${clinic.displayName} e gostaria de agendar uma sessão.`)
    : null;

  return (
    <div className="public" style={themeVariables(clinic.theme) as CSSProperties}>
      <header className="site-bar">
        <a className="site-brand" href="#topo">
          <Brand clinic={clinic} logo={logo} size="small" />
          <span>{clinic.displayName}</span>
        </a>
        {hello && <a className="site-bar-cta" href={hello} target="_blank" rel="noreferrer">WhatsApp</a>}
      </header>

      <section className="public-hero" id="topo">
        <div className="hero-brand"><Brand clinic={clinic} logo={logo} size="large" /></div>
        <h1>{clinic.displayName}</h1>
        {clinic.professionalName && (
          <p className="hero-pro">{clinic.professionalName}{clinic.crefito && ` · CREFITO ${clinic.crefito}`}</p>
        )}
        {clinic.about && <p className="about">{clinic.about}</p>}
        <div className="hero-actions">
          {hello && <a className="cta hero-cta" href={hello} target="_blank" rel="noreferrer">Agendar pelo WhatsApp</a>}
          {clinic.services.length > 0 && <a className="hero-link" href="#servicos">Ver serviços</a>}
        </div>
      </section>

      <main className="public-content" id="servicos">
        <h2>Serviços</h2>
        {clinic.services.length === 0 && <p className="muted">Em breve.</p>}
        <div className="grid">
          {clinic.services.map(service => <ServiceCard key={service.id} clinic={clinic} service={service} />)}
        </div>
      </main>

      <footer className="site-footer">
        <Brand clinic={clinic} logo={logo} size="small" />
        <div>
          <strong>{clinic.displayName}</strong>
          {clinic.professionalName && <div>{clinic.professionalName}{clinic.crefito && ` · CREFITO ${clinic.crefito}`}</div>}
          {clinic.address && <div>📍 {clinic.address}</div>}
          {clinic.whatsapp && hello && (
            <div>WhatsApp: <a href={hello} target="_blank" rel="noreferrer">{formatPhone(clinic.whatsapp)}</a></div>
          )}
        </div>
      </footer>
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
