import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import { logoUrl, whatsappLink, type PublicClinic, type PublicPhoto, type PublicService } from './api';
import { themeVariables } from './color';
import { formatPhone } from './format';
import { SiteIcon } from './SiteIcons';
import { bookingMessage, educationItems, mapEmbedUrl, mapLinkUrl, photoUrl } from './siteContent';
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

/** A logo aparece só na barra do topo e no rodapé; sem logo, as iniciais. */
function Brand({ clinic, logo }: { clinic: PublicClinic; logo: string | null }) {
  return logo
    ? <img className="brand-logo" src={logo} alt={`Logo ${clinic.displayName}`} />
    : <span className="brand-monogram" aria-hidden="true">{initials(clinic.displayName)}</span>;
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
 * Site da clínica: serviços em lista (preço e pagamento ficam para o agendamento), fotos, formação, depoimentos,
 * perguntas e onde atende. O estilo (sereno, dinâmico, acolhedor, clínico) define fontes, formas e textos.
 * Cada seção só aparece se tiver conteúdo.
 */
export function ClinicSite({ clinic }: { clinic: PublicClinic }) {
  const site = clinic.site;
  const style: StyleId = resolveStyle(site.style, clinic.services, clinic.about);
  const fonts: FontsId = resolveFonts(site.fonts, style);
  useFonts(fonts);
  const logo = logoUrl(clinic.logoVersion, clinic.slug);
  useBranding(clinic, logo);

  const headline = site.headline || autoHeadline(style, clinic.services);
  const subtitle = autoSubtitle(clinic.services);
  const book = (serviceName?: string) =>
    clinic.whatsapp ? whatsappLink(clinic.whatsapp, bookingMessage(clinic.displayName, serviceName)) : null;
  const hello = book();
  const homeCare = !!site.serviceArea || clinic.services.some(s => serviceIcon(s.name, s.description) === 'home');
  const education = educationItems(site.education);
  const hasAbout = !!(clinic.about || education.length);
  const hasContact = !!(clinic.address || site.serviceArea || site.hours || hello);
  const vars = useMemo(() => ({
    ...themeVariables(clinic.theme),
    '--font-heading': `'${FONTS[fonts].heading}', Georgia, serif`,
    '--font-body': `'${FONTS[fonts].body}', system-ui, sans-serif`
  }) as CSSProperties, [clinic.theme, fonts]);

  return (
    <div className={`site style-${style}`} style={vars}>
      <a className="skip" href="#servicos">Pular para os serviços</a>
      <header className="site-nav">
        <div className="wrap nav-inner">
          <a className="site-brand" href="#topo" aria-label={`${clinic.displayName}, início`}>
            <Brand clinic={clinic} logo={logo} />
            <span>{clinic.displayName}</span>
          </a>
          <nav className="site-links" aria-label="Seções">
            {clinic.services.length > 0 && <a href="#servicos">Serviços</a>}
            {hasAbout && <a href="#sobre">Sobre</a>}
            {site.faqs.length > 0 && <a href="#duvidas">Dúvidas</a>}
            {hasContact && <a href="#contato">Contato</a>}
          </nav>
          {hello && <a className="btn btn-primary btn-sm" href={hello} target="_blank" rel="noreferrer">Agendar</a>}
        </div>
      </header>

      <section className={site.cover ? 'hero has-cover' : 'hero'} id="topo">
        <div className="hero-bg" aria-hidden="true" />
        <div className="wrap hero-inner">
          <div className="hero-text">
            <p className="eyebrow">{STYLES[style].eyebrow}</p>
            <h1>{headline}</h1>
            <p className="hero-sub">{subtitle}</p>
            <div className="hero-actions">
              {hello && (
                <a className="btn btn-primary btn-lg" href={hello} target="_blank" rel="noreferrer">
                  <SiteIcon name="whatsapp" size={20} /> Agendar pelo WhatsApp
                </a>
              )}
              {clinic.services.length > 0 && <a className="btn-text" href="#servicos">Ver serviços <SiteIcon name="arrow" size={18} /></a>}
            </div>
          </div>
          <HeroVisual clinic={clinic} />
        </div>
      </section>

      {(clinic.crefito || site.hours || homeCare) && (
        <section className="facts" aria-label="Informações rápidas">
          <ul className="wrap facts-list">
            {clinic.crefito && (
              <li><SiteIcon name="award" size={22} /><div><strong>Registro profissional</strong><span>CREFITO {clinic.crefito}</span></div></li>
            )}
            {homeCare && (
              <li><SiteIcon name="home" size={22} /><div><strong>Atendimento em casa</strong>
                <span>{site.serviceArea ?? 'Consulte sua região'}</span></div></li>
            )}
            {site.hours && (
              <li><SiteIcon name="calendar" size={22} /><div><strong>Horários</strong><span>{site.hours}</span></div></li>
            )}
          </ul>
        </section>
      )}

      <main>
        {clinic.services.length > 0 && (
          <section className="section" id="servicos" aria-labelledby="titulo-servicos">
            <div className="wrap">
              <div className="section-head">
                <p className="eyebrow">Atendimentos</p>
                <h2 id="titulo-servicos">Como posso ajudar</h2>
              </div>
              <ul className="services">
                {clinic.services.map(service => <ServiceItem key={service.id} service={service} link={service.onlineBooking ? book(service.name) : null} />)}
              </ul>
            </div>
          </section>
        )}

        {hasAbout && (
          <section className="section section-tint" id="sobre" aria-labelledby="titulo-sobre">
            <div className="wrap about">
              <div className="about-text">
                <p className="eyebrow">Quem cuida de você</p>
                <h2 id="titulo-sobre">{clinic.professionalName ?? clinic.displayName}</h2>
                {clinic.crefito && <p className="about-role">Fisioterapeuta · CREFITO {clinic.crefito}</p>}
                {clinic.about && <p className="about-body">{clinic.about}</p>}
              </div>
              {education.length > 0 && (
                <aside className="education" aria-label="Formação">
                  <h3>Formação</h3>
                  <ul>{education.map((item, i) => <li key={i}><SiteIcon name="check" size={18} />{item}</li>)}</ul>
                </aside>
              )}
            </div>
          </section>
        )}

        {site.gallery.length > 0 && (
          <section className="section" aria-labelledby="titulo-galeria">
            <div className="wrap">
              <div className="section-head">
                <p className="eyebrow">Por dentro</p>
                <h2 id="titulo-galeria">O espaço e o atendimento</h2>
              </div>
              <div className={`gallery count-${Math.min(site.gallery.length, 6)}`}>
                {site.gallery.map(photo => (
                  <figure key={photo.id}>
                    <Photo slug={clinic.slug} photo={photo} sizes="(min-width: 860px) 33vw, 50vw" />
                    {photo.caption && <figcaption>{photo.caption}</figcaption>}
                  </figure>
                ))}
              </div>
            </div>
          </section>
        )}

        {hello && (
          <section className="section section-tint" aria-labelledby="titulo-como">
            <div className="wrap">
              <div className="section-head">
                <p className="eyebrow">Simples assim</p>
                <h2 id="titulo-como">Como agendar</h2>
              </div>
              <ol className="steps">
                <li><span className="step-n">1</span><h3>Escolha o atendimento</h3><p>Veja acima o que combina com você. Na dúvida, comece pela avaliação.</p></li>
                <li><span className="step-n">2</span><h3>Chame no WhatsApp</h3><p>A mensagem já vai pronta. Combinamos o melhor dia e horário.</p></li>
                <li><span className="step-n">3</span><h3>Comece o tratamento</h3><p>Acompanhamento de perto, sessão a sessão, com um plano para você.</p></li>
              </ol>
            </div>
          </section>
        )}

        {site.testimonials.length > 0 && (
          <section className="section" aria-labelledby="titulo-depoimentos">
            <div className="wrap">
              <div className="section-head">
                <p className="eyebrow">Depoimentos</p>
                <h2 id="titulo-depoimentos">Quem já foi atendido</h2>
              </div>
              <div className="testimonials">
                {site.testimonials.map((t, i) => (
                  <figure key={i} className="testimonial">
                    <blockquote>{t.text}</blockquote>
                    <figcaption>{t.authorName}</figcaption>
                  </figure>
                ))}
              </div>
            </div>
          </section>
        )}

        {site.faqs.length > 0 && (
          <section className="section" id="duvidas" aria-labelledby="titulo-duvidas">
            <div className="wrap narrow">
              <div className="section-head">
                <p className="eyebrow">Dúvidas frequentes</p>
                <h2 id="titulo-duvidas">Perguntas e respostas</h2>
              </div>
              <div className="faq">
                {site.faqs.map((f, i) => (
                  <details key={i}>
                    <summary>{f.question}</summary>
                    <p>{f.answer}</p>
                  </details>
                ))}
              </div>
            </div>
          </section>
        )}

        {hasContact && (
          <section className="section section-tint" id="contato" aria-labelledby="titulo-contato">
            <div className={site.showMap && clinic.address ? 'wrap contact with-map' : 'wrap contact'}>
              <div>
                <p className="eyebrow">Onde atendo</p>
                <h2 id="titulo-contato">Vamos conversar</h2>
                <ul className="contact-list">
                  {clinic.address && (
                    <li><SiteIcon name="pin" size={22} /><div><strong>Endereço</strong>
                      <a href={mapLinkUrl(clinic.address)} target="_blank" rel="noreferrer">{clinic.address}</a></div></li>
                  )}
                  {site.serviceArea && (
                    <li><SiteIcon name="home" size={22} /><div><strong>Atendimento em casa</strong><span>{site.serviceArea}</span></div></li>
                  )}
                  {site.hours && (
                    <li><SiteIcon name="calendar" size={22} /><div><strong>Horários</strong><span>{site.hours}</span></div></li>
                  )}
                  {clinic.whatsapp && hello && (
                    <li><SiteIcon name="whatsapp" size={22} /><div><strong>WhatsApp</strong>
                      <a href={hello} target="_blank" rel="noreferrer">{formatPhone(clinic.whatsapp)}</a></div></li>
                  )}
                </ul>
                {hello && (
                  <a className="btn btn-primary btn-lg" href={hello} target="_blank" rel="noreferrer">
                    <SiteIcon name="whatsapp" size={20} /> Agendar pelo WhatsApp
                  </a>
                )}
              </div>
              {site.showMap && clinic.address && (
                <iframe className="map" title={`Mapa: ${clinic.address}`} src={mapEmbedUrl(clinic.address)}
                        loading="lazy" referrerPolicy="no-referrer-when-downgrade" />
              )}
            </div>
          </section>
        )}
      </main>

      <footer className="site-footer">
        <div className="wrap footer-inner">
          <div className="footer-brand">
            <Brand clinic={clinic} logo={logo} />
            <div>
              <strong>{clinic.displayName}</strong>
              {clinic.professionalName && <span>{clinic.professionalName}{clinic.crefito && ` · CREFITO ${clinic.crefito}`}</span>}
            </div>
          </div>
          <p className="footer-note">© {new Date().getFullYear()} {clinic.displayName}</p>
        </div>
      </footer>

      {hello && (
        <a className="float-wa" href={hello} target="_blank" rel="noreferrer" aria-label="Agendar pelo WhatsApp">
          <SiteIcon name="whatsapp" size={26} />
        </a>
      )}
    </div>
  );
}

function Photo({ slug, photo, sizes, eager }: { slug: string; photo: PublicPhoto; sizes: string; eager?: boolean }) {
  return (
    <img src={photoUrl(slug, photo.id)} width={photo.width} height={photo.height} sizes={sizes}
         alt={photo.caption ?? ''} loading={eager ? 'eager' : 'lazy'} decoding="async" />
  );
}

/** Foto de capa; sem ela, um cartão com a profissional e os principais atendimentos (sem repetir a logo). */
function HeroVisual({ clinic }: { clinic: PublicClinic }) {
  const cover = clinic.site.cover;
  const badge = clinic.professionalName && (
    <p className="hero-badge">
      <strong>{clinic.professionalName}</strong>
      <span>Fisioterapeuta{clinic.crefito && ` · CREFITO ${clinic.crefito}`}</span>
    </p>
  );
  if (cover) {
    return (
      <div className="hero-visual">
        <div className="hero-photo"><Photo slug={clinic.slug} photo={cover} sizes="(min-width: 860px) 45vw, 100vw" eager /></div>
        {badge}
      </div>
    );
  }
  const top = clinic.services.slice(0, 4);
  if (top.length === 0 && !badge) return null;
  return (
    <div className="hero-visual">
      <div className="hero-panel">
        {badge}
        {top.length > 0 && (
          <ul>
            {top.map(s => <li key={s.id}><span className="mini-icon"><SiteIcon name={serviceIcon(s.name, s.description)} size={20} /></span>{s.name}</li>)}
          </ul>
        )}
      </div>
    </div>
  );
}

/** Serviço em lista: o que é e quanto dura. Preço e forma de pagamento ficam para o agendamento. */
function ServiceItem({ service, link }: { service: PublicService; link: string | null }) {
  return (
    <li className="service">
      <span className="service-icon"><SiteIcon name={serviceIcon(service.name, service.description)} size={26} /></span>
      <div>
        <h3>{service.name}</h3>
        {service.description && <p>{service.description}</p>}
        <p className="service-meta">
          <span>{service.durationMinutes} min por sessão</span>
          {link && <a href={link} target="_blank" rel="noreferrer" aria-label={`Agendar ${service.name} pelo WhatsApp`}>Agendar <SiteIcon name="arrow" size={16} /></a>}
        </p>
      </div>
    </li>
  );
}
