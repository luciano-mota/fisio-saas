import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import { logoUrl, money, whatsappLink, type PublicClinic, type PublicPackage, type PublicService } from './api';
import { themeVariables } from './color';
import { formatPhone } from './format';
import { SiteIcon } from './SiteIcons';
import { autoHeadline, autoSubtitle, FONTS, fontsUrl, resolveFonts, resolveStyle, serviceIcon, STYLES,
  type FontsId, type StyleId } from './siteStyle';
import './site.css';

/** Iniciais para quando a clínica ainda não enviou a logo ("Clínica Bem Estar" → "CB"). */
export function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter(w => w.length > 2 || /^[A-ZÁ-Ú]/.test(w));
  return (words.length ? words : [name]).slice(0, 2).map(w => w[0]).join('').toUpperCase();
}

/** Carrega o par de fontes (Google Fonts, gratuito) só quando o site é aberto. */
export function useFonts(id: FontsId) {
  useEffect(() => {
    const href = fontsUrl(id);
    if (document.querySelector(`link[data-site-fonts="${id}"]`)) return;
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = href;
    link.dataset.siteFonts = id;
    document.head.appendChild(link);
  }, [id]);
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

export function PublicPage({ slug }: { slug: string }) {
  const [clinic, setClinic] = useState<PublicClinic | null | undefined>(undefined);

  useEffect(() => {
    fetch(`/api/v1/public/clinics/${slug}`)
      .then(res => (res.ok ? res.json() : null))
      .then((data: PublicClinic | null) => setClinic(data))
      .catch(() => setClinic(null));
  }, [slug]);

  if (clinic === undefined) return <div className="center muted">Carregando…</div>;
  if (clinic === null) return <div className="center"><h1>Página não encontrada</h1></div>;
  return <ClinicSite clinic={clinic} />;
}

/**
 * Site da clínica montado a partir da logo, das cores e dos serviços: o estilo (sereno, dinâmico, acolhedor,
 * clínico) define fontes, formas e textos; a clínica pode escolher outro no painel.
 */
export function ClinicSite({ clinic }: { clinic: PublicClinic }) {
  const style: StyleId = resolveStyle(clinic.site?.style, clinic.services, clinic.about);
  const fonts: FontsId = resolveFonts(clinic.site?.fonts, style);
  useFonts(fonts);
  const logo = logoUrl(clinic.logoVersion, clinic.slug);
  useBranding(clinic, logo);

  const headline = clinic.site?.headline || autoHeadline(style, clinic.services);
  const subtitle = autoSubtitle(clinic.services);
  const hello = clinic.whatsapp
    ? whatsappLink(clinic.whatsapp, `Olá! Vi o site da ${clinic.displayName} e gostaria de agendar uma avaliação.`)
    : null;
  const homeCare = clinic.services.some(s => serviceIcon(s.name, s.description) === 'home');
  const hasPackages = clinic.services.some(s => s.packages.length > 0);
  const mapUrl = clinic.address
    ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(clinic.address)}` : null;

  const vars = useMemo(() => ({
    ...themeVariables(clinic.theme),
    '--font-heading': `'${FONTS[fonts].heading}', Georgia, serif`,
    '--font-body': `'${FONTS[fonts].body}', system-ui, sans-serif`
  }) as CSSProperties, [clinic.theme, fonts]);

  return (
    <div className={`site style-${style}`} style={vars}>
      <a className="skip" href="#servicos">Pular para os serviços</a>
      <header className="site-nav">
        <a className="site-brand" href="#topo" aria-label={`${clinic.displayName}, início`}>
          <Brand clinic={clinic} logo={logo} size="small" />
          <span>{clinic.displayName}</span>
        </a>
        <nav className="site-links" aria-label="Seções">
          <a href="#servicos">Serviços</a>
          {(clinic.about || clinic.professionalName) && <a href="#sobre">Sobre</a>}
          <a href="#contato">Contato</a>
        </nav>
        {hello && <a className="btn btn-accent btn-sm" href={hello} target="_blank" rel="noreferrer">Agendar</a>}
      </header>

      <section className="hero" id="topo">
        <div className="hero-bg" aria-hidden="true" />
        <div className="hero-inner">
          <div className="hero-text">
            <p className="eyebrow">{STYLES[style].eyebrow}</p>
            <h1>{headline}</h1>
            <p className="hero-sub">{subtitle}</p>
            <div className="hero-actions">
              {hello && (
                <a className="btn btn-accent btn-lg" href={hello} target="_blank" rel="noreferrer">
                  <SiteIcon name="whatsapp" size={20} /> Agendar pelo WhatsApp
                </a>
              )}
              {clinic.services.length > 0 && <a className="btn btn-ghost btn-lg" href="#servicos">Ver serviços</a>}
            </div>
            <ul className="trust" aria-label="Diferenciais">
              {clinic.crefito && <li><SiteIcon name="award" size={18} /> CREFITO {clinic.crefito}</li>}
              {homeCare && <li><SiteIcon name="home" size={18} /> Atendimento em casa</li>}
              {hasPackages && <li><SiteIcon name="calendar" size={18} /> Pacotes de sessões</li>}
              {hello && <li><SiteIcon name="chat" size={18} /> Resposta pelo WhatsApp</li>}
            </ul>
          </div>
          <div className="hero-visual">
            <div className="hero-card">
              <Brand clinic={clinic} logo={logo} size="large" />
              {clinic.professionalName && (
                <p className="hero-card-name">{clinic.professionalName}<span>Fisioterapeuta</span></p>
              )}
            </div>
          </div>
        </div>
      </section>

      <main>
        <section className="section" id="servicos" aria-labelledby="titulo-servicos">
          <div className="section-head">
            <p className="eyebrow">O que oferecemos</p>
            <h2 id="titulo-servicos">Serviços</h2>
            <p className="section-lead">Escolha o atendimento e a quantidade de sessões. É só chamar no WhatsApp para marcar.</p>
          </div>
          {clinic.services.length === 0 && <p className="muted center-text">Em breve.</p>}
          <div className="cards">
            {clinic.services.map(service => <ServiceCard key={service.id} clinic={clinic} service={service} />)}
          </div>
        </section>

        <section className="section section-tint" aria-labelledby="titulo-como">
          <div className="section-head">
            <p className="eyebrow">Simples assim</p>
            <h2 id="titulo-como">Como funciona</h2>
          </div>
          <ol className="steps">
            <li><span className="step-n">1</span><h3>Escolha o serviço</h3><p>Veja as opções e os pacotes que combinam com você.</p></li>
            <li><span className="step-n">2</span><h3>Chame no WhatsApp</h3><p>A mensagem já vai pronta; combinamos o melhor horário.</p></li>
            <li><span className="step-n">3</span><h3>Comece o tratamento</h3><p>Acompanhamento de perto, sessão a sessão.</p></li>
          </ol>
        </section>

        {(clinic.about || clinic.professionalName) && (
          <section className="section about" id="sobre" aria-labelledby="titulo-sobre">
            <div className="about-mark"><Brand clinic={clinic} logo={logo} size="large" /></div>
            <div>
              <p className="eyebrow">Sobre</p>
              <h2 id="titulo-sobre">{clinic.professionalName ?? clinic.displayName}</h2>
              {clinic.crefito && <p className="muted">Fisioterapeuta · CREFITO {clinic.crefito}</p>}
              {clinic.about && <p className="about-text">{clinic.about}</p>}
            </div>
          </section>
        )}

        {hello && (
          <section className="cta-band" aria-label="Agendar">
            <h2>Pronto para dar o primeiro passo?</h2>
            <p>Fale agora e agende sua avaliação.</p>
            <a className="btn btn-light btn-lg" href={hello} target="_blank" rel="noreferrer">
              <SiteIcon name="whatsapp" size={20} /> Chamar no WhatsApp
            </a>
          </section>
        )}
      </main>

      <footer className="site-footer" id="contato">
        <div className="footer-brand">
          <Brand clinic={clinic} logo={logo} size="small" />
          <strong>{clinic.displayName}</strong>
        </div>
        <ul className="footer-contact">
          {clinic.whatsapp && hello && (
            <li><SiteIcon name="whatsapp" size={18} /> <a href={hello} target="_blank" rel="noreferrer">{formatPhone(clinic.whatsapp)}</a></li>
          )}
          {clinic.address && (
            <li><SiteIcon name="pin" size={18} /> {mapUrl
              ? <a href={mapUrl} target="_blank" rel="noreferrer">{clinic.address}</a> : clinic.address}</li>
          )}
          {clinic.crefito && <li><SiteIcon name="award" size={18} /> CREFITO {clinic.crefito}</li>}
        </ul>
        <p className="footer-note">© {new Date().getFullYear()} {clinic.displayName}</p>
      </footer>

      {hello && (
        <a className="float-wa" href={hello} target="_blank" rel="noreferrer" aria-label="Agendar pelo WhatsApp">
          <SiteIcon name="whatsapp" size={26} />
        </a>
      )}
    </div>
  );
}

/** "10 sessões" fica como está; "Pacote mensal" vira "Pacote mensal (8 sessões)". */
function packageLabel(p: PublicPackage): string {
  return p.name.includes(String(p.sessionCount)) ? p.name : `${p.name} (${p.sessionCount} sessões)`;
}

/** Menor preço por sessão entre avulsa e pacotes com preço visível ("a partir de"). */
function lowestPerSession(service: PublicService): number | null {
  const values = [service.price, ...service.packages.map(p => (p.price === null ? null : p.price / p.sessionCount))]
    .filter((v): v is number => v !== null);
  return values.length ? Math.min(...values) : null;
}

function ServiceCard({ clinic, service }: { clinic: PublicClinic; service: PublicService }) {
  const [choice, setChoice] = useState<string>('single');
  const selected: PublicPackage | undefined = service.packages.find(p => p.id === choice);
  const price = selected ? selected.price : service.price;
  const label = selected ? packageLabel(selected) : 'sessão avulsa';
  const from = lowestPerSession(service);
  const message = `Olá! Vi seu site e quero agendar ${service.name} – ${label}. Quais horários você tem?`;

  return (
    <article className="card">
      <div className="card-top">
        <span className="card-icon"><SiteIcon name={serviceIcon(service.name, service.description)} size={26} /></span>
        <span className="card-meta">{service.durationMinutes} min</span>
      </div>
      <h3>{service.name}</h3>
      {service.description && <p className="card-desc">{service.description}</p>}

      {service.packages.length > 0 && (
        <label className="card-select">Quantidade
          <select value={choice} onChange={e => setChoice(e.target.value)}>
            <option value="single">Sessão avulsa</option>
            {service.packages.map(p => <option key={p.id} value={p.id}>{packageLabel(p)}</option>)}
          </select>
        </label>
      )}

      <div className="card-price">
        <span className={price === null ? 'price on-request' : 'price'}>{money(price)}</span>
        {selected && selected.price !== null && selected.sessionCount > 1 && (
          <span className="per">{money(selected.price / selected.sessionCount)} por sessão</span>
        )}
        {!selected && price === null && from !== null && <span className="per">a partir de {money(from)} por sessão</span>}
      </div>

      {service.onlineBooking && clinic.whatsapp ? (
        <a className="btn btn-primary card-cta" href={whatsappLink(clinic.whatsapp, message)} target="_blank" rel="noreferrer">
          Agendar <SiteIcon name="arrow" size={18} />
        </a>
      ) : (
        <p className="muted small">Agendamento direto com a clínica.</p>
      )}
    </article>
  );
}
