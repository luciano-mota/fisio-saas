import { useEffect, useState, type ReactNode } from 'react';
import { PRODUCT_NAME } from './product';
import { navigate } from './router';
import { Icon, type IconName } from './ui';
import './landing.css';

/** Logo oficial (símbolo + nome). {@code dark}: versão para fundo escuro. */
export function Logo({ height = 34, dark = false }: { height?: number; dark?: boolean }) {
  return <img className="pe-logo" src={dark ? '/brand/physioevo-logo-white.svg' : '/brand/physioevo-logo.svg'} alt={PRODUCT_NAME}
              height={height} />;
}

function Link({ to, className, children }: { to: string; className?: string; children: ReactNode }) {
  return <a href={to} className={className} onClick={e => { e.preventDefault(); navigate(to); window.scrollTo(0, 0); }}>{children}</a>;
}

/** Contato da plataforma (e-mail configurado no servidor). */
function usePlatform() {
  const [p, setP] = useState<{ signupEnabled: boolean; contactEmail: string | null }>({ signupEnabled: true, contactEmail: null });
  useEffect(() => {
    fetch('/api/v1/public/platform').then(r => (r.ok ? r.json() : null)).then(v => v && setP(v)).catch(() => undefined);
  }, []);
  return p;
}

const FEATURES: { icon: IconName; title: string; text: string }[] = [
  { icon: 'calendar', title: 'Agenda no celular', text: 'Sessões avulsas e recorrentes, remarcação em dois toques e confirmação pronta no WhatsApp.' },
  { icon: 'box', title: 'Pacotes com saldo', text: 'Venda pacotes e veja quantas sessões faltam. O sistema avisa a hora de renovar.' },
  { icon: 'globe', title: 'Seu site com agendamento', text: 'Página com a sua marca, horários livres e pedido com CPF — sem paciente duplicado.' },
  { icon: 'money', title: 'Pix sem taxa', text: 'QR Code com o valor certo e comprovante enviado pelo paciente. Cai direto na sua conta.' },
  { icon: 'tag', title: 'Maquininha sem susto', text: 'Cadastre as taxas e veja quanto cai na conta, quando cai e quem ainda está devendo.' },
  { icon: 'check', title: 'Recibo para o IR', text: 'CPF, CREFITO e valor por extenso, pronto para imprimir ou mandar em PDF.' },
  { icon: 'users', title: 'Equipe com acesso', text: 'Dê acesso à recepção só para consulta. Você continua no controle de tudo.' },
  { icon: 'bell', title: 'Pedidos e lembretes', text: 'Pedidos do site, sessões a confirmar e valores em aberto numa tela só, a de hoje.' }
];

const FAQ: { q: string; a: string }[] = [
  { q: 'O site tem custo extra?', a: 'Não. O site vem de bônus para quem assina o PhysioEvo: sem hospedagem, sem programador e sem mensalidade a mais. Você escolhe o modelo, as cores, as fotos e os serviços, e muda quando quiser.' },
  { q: 'Preciso instalar alguma coisa?', a: 'Não. O PhysioEvo funciona no navegador do celular e do computador. No celular dá para adicionar à tela inicial como um aplicativo.' },
  { q: 'Serve para quem atende em casa (domiciliar)?', a: 'Sim — foi pensado para isso. A agenda mostra o dia no celular, o WhatsApp abre com a mensagem pronta e o pagamento pode ser por Pix, dinheiro ou maquininha.' },
  { q: 'O Pix tem taxa?', a: 'Não. O código Pix é gerado com a sua chave e o dinheiro vai direto para a sua conta. O PhysioEvo não passa no meio do pagamento.' },
  { q: 'Guarda prontuário ou evolução clínica?', a: 'Ainda não. Por enquanto o sistema guarda só dados administrativos (agenda, pacotes, pagamentos). Prontuário só depois de cumprir as regras do COFFITO e da LGPD.' },
  { q: 'Meus dados ficam seguros?', a: 'Cada clínica só enxerga os próprios dados, a conexão é criptografada e a senha nunca fica guardada em texto. Você pode pedir uma cópia ou a exclusão dos dados quando quiser.' },
  { q: 'Quanto custa?', a: 'O teste é grátis por 30 dias, sem cartão. O valor do plano será informado com antecedência antes de qualquer cobrança — e você pode sair quando quiser.' }
];

/** Ilustração própria: fisioterapeuta e paciente em alongamento, em casa (formas da marca). */
function CareIllustration() {
  return (
    <svg className="pe-illustration" viewBox="0 0 420 320" role="img" aria-label="Fisioterapeuta acompanhando paciente em alongamento em casa">
      <defs>
        <linearGradient id="ilg" x1="0" y1="1" x2="1" y2="0"><stop offset="0" stopColor="#0A5C63" /><stop offset="1" stopColor="#14B89A" /></linearGradient>
      </defs>
      <circle cx="210" cy="170" r="140" fill="#E3F6F1" />
      <path d="M92 150 L210 62 L328 150" fill="none" stroke="#0B2B33" strokeWidth="10" strokeLinecap="round" strokeLinejoin="round" opacity=".14" />
      <rect x="96" y="262" width="230" height="16" rx="8" fill="url(#ilg)" opacity=".9" />
      {/* paciente sentada alongando os braços */}
      <circle cx="170" cy="138" r="17" fill="#FF7A59" />
      <path d="M170 160 C 168 190, 166 214, 170 240" stroke="#0A5C63" strokeWidth="22" strokeLinecap="round" fill="none" />
      <path d="M168 176 L 140 128 M172 176 L 196 122" stroke="#0A5C63" strokeWidth="11" strokeLinecap="round" />
      <path d="M170 240 L 222 248 L 252 256" stroke="#0B2B33" strokeWidth="13" strokeLinecap="round" strokeLinejoin="round" fill="none" />
      {/* fisioterapeuta em pé */}
      <circle cx="268" cy="104" r="17" fill="#0B2B33" />
      <path d="M268 126 L 268 196" stroke="#14B89A" strokeWidth="24" strokeLinecap="round" />
      <path d="M266 146 L 222 140 M270 150 L 236 176" stroke="#14B89A" strokeWidth="11" strokeLinecap="round" />
      <path d="M262 196 L 256 252 M274 196 L 284 252" stroke="#0B2B33" strokeWidth="12" strokeLinecap="round" />
      {/* vértebras subindo (marca) */}
      <g fill="#14B89A"><circle cx="318" cy="244" r="6" /><circle cx="334" cy="228" r="7" /><circle cx="352" cy="210" r="8" /></g>
      <path d="M362 200 L 376 186" stroke="#14B89A" strokeWidth="8" strokeLinecap="round" />
      <path d="M362 182 H 379 V 199" fill="none" stroke="#FF7A59" strokeWidth="8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

/** Celular com o painel (mostra o dia de trabalho). */
function PhoneMock() {
  return (
    <div className="pe-phone" aria-hidden="true">
      <div className="pe-phone-screen">
        <div className="pe-phone-top"><img src="/brand/physioevo-mark.svg" alt="" width={22} height={22} /> Hoje · 4 sessões</div>
        {[['08:00', 'Maria S.', 'Pilates · 3 restantes', ''], ['09:00', 'João P.', 'RPG · avulsa · a pagar', 'warn'],
          ['10:30', 'Ana L.', 'Drenagem · confirmada', ''], ['14:00', 'Pedido pelo site', 'Pix enviado · conferir', 'new']].map(([t, n, d, s]) => (
          <div key={t} className={`pe-item ${s}`}><strong>{t}</strong><span><b>{n}</b>{d}</span></div>
        ))}
        <div className="pe-total"><span>Recebido em outubro</span><strong>R$ 3.240,00</strong></div>
      </div>
      <div className="pe-float pe-float-a"><Icon name="money" size={18} /> Pix recebido · R$ 90,00</div>
      <div className="pe-float pe-float-b"><Icon name="bell" size={18} /> Novo pedido pelo site</div>
    </div>
  );
}

/** Mini-site da clínica (vitrine com agendamento). */
function SiteMock() {
  return (
    <div className="pe-browser" aria-hidden="true">
      <div className="pe-browser-bar"><i /><i /><i /><span>physioevo.com.br/c/fisio-ana</span></div>
      <div className="pe-site">
        <div className="pe-site-hero"><small>Fisioterapia em casa</small><strong>Dra. Ana Lima</strong>
          <span>Pilates · RPG · Drenagem</span><em>Agendar horário</em></div>
        <div className="pe-site-slots">
          <p>Qual horário?</p>
          <div>{['08:00', '09:30', '10:00', '14:00', '15:30', '17:00'].map((t, i) => <b key={t} className={i === 3 ? 'on' : ''}>{t}</b>)}</div>
          <p className="pe-site-cpf">CPF · 000.000.000-00</p>
        </div>
      </div>
    </div>
  );
}

/** Pix com QR Code (valor certo, comprovante). */
/** Desenho de um QR Code (ilustrativo, não é lido por câmera): três cantos de posição e módulos pseudoaleatórios. */
function QrArt() {
  const n = 25;
  const finder = (x: number, y: number) => [[0, 0], [n - 7, 0], [0, n - 7]].some(([fx, fy]) => x >= fx - 1 && x <= fx + 7 && y >= fy - 1 && y <= fy + 7);
  let seed = 7;
  const rects: string[] = [];
  for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) {
    seed = (seed * 1103515245 + 12345) % 2147483648;
    if (!finder(x, y) && seed % 100 < 47) rects.push(`M${x} ${y}h1v1h-1z`);
  }
  const eye = (x: number, y: number) => `M${x} ${y}h7v7h-7zM${x + 1} ${y + 1}v5h5v-5zM${x + 2} ${y + 2}h3v3h-3z`;
  return (
    <svg className="pe-qr" viewBox={`-2 -2 ${n + 4} ${n + 4}`} role="presentation">
      <rect x="-2" y="-2" width={n + 4} height={n + 4} fill="#fff" />
      <path d={rects.join('')} fill="#0B2B33" />
      <path d={eye(0, 0) + eye(n - 7, 0) + eye(0, n - 7)} fill="#0B2B33" fillRule="evenodd" />
    </svg>
  );
}

function PixMock() {
  return (
    <div className="pe-pix" aria-hidden="true">
      <div className="pe-pix-head"><span>Falta só o Pix</span><strong>R$ 90,00</strong></div>
      <QrArt />
      <div className="pe-pix-code">00020126360014br.gov.bcb.pix…</div>
      <div className="pe-pix-btn">Já paguei — enviar comprovante</div>
    </div>
  );
}

/** Página do produto PhysioEvo (quem ainda não entrou). */
export function Landing({ signupEnabled }: { signupEnabled: boolean }) {
  const { contactEmail } = usePlatform();
  const [menu, setMenu] = useState(false);
  const cta = signupEnabled
    ? <Link to="/cadastro" className="pe-btn pe-btn-primary">Começar grátis</Link>
    : <Link to="/login" className="pe-btn pe-btn-primary">Entrar</Link>;
  const anchors = [['#site-gratis', 'Site grátis'], ['#recursos', 'Recursos'], ['#como-funciona', 'Como funciona'], ['#planos', 'Planos'], ['#duvidas', 'Dúvidas']];

  return (
    <div className="pe">
      <header className="pe-nav">
        <div className="pe-wrap pe-nav-inner">
          <Link to="/" className="pe-nav-brand"><Logo height={34} /></Link>
          <nav className={menu ? 'pe-nav-links open' : 'pe-nav-links'} aria-label="Seções">
            {anchors.map(([href, label]) => <a key={href} href={href} onClick={() => setMenu(false)}>{label}</a>)}
            <Link to="/login" className="pe-nav-login">Entrar</Link>
            {signupEnabled && <Link to="/cadastro" className="pe-btn pe-btn-primary pe-btn-sm">Teste grátis</Link>}
          </nav>
          <button className="pe-menu" aria-label="Abrir menu" aria-expanded={menu} onClick={() => setMenu(m => !m)}>
            <Icon name={menu ? 'x' : 'more'} /></button>
        </div>
      </header>

      <main>
        <section className="pe-hero">
          <div className="pe-wrap pe-hero-grid">
            <div className="pe-hero-copy">
              <a href="#site-gratis" className="pe-gift-pill"><Icon name="gift" size={16} /> Bônus: seu site profissional grátis</a>
              <h1>A evolução da sua clínica começa <span>no seu celular</span>.</h1>
              <p className="pe-lead">Agenda, pacotes de sessões, Pix sem taxa, maquininha e recibo para o IR num lugar só, feito
                para fisioterapeutas que atendem no consultório ou em casa. <b>E de bônus, quem assina ganha um site próprio, grátis e
                configurável, com agendamento online.</b></p>
              <div className="pe-cta">{cta}<a href="#como-funciona" className="pe-btn pe-btn-ghost">Ver como funciona</a></div>
              <ul className="pe-trust">
                <li><Icon name="check" size={16} /> 30 dias grátis</li>
                <li><Icon name="check" size={16} /> Site incluso</li>
                <li><Icon name="check" size={16} /> Sem cartão de crédito</li>
                <li><Icon name="check" size={16} /> Sem instalar nada</li>
              </ul>
            </div>
            <PhoneMock />
          </div>
        </section>

        <section className="pe-section pe-pains">
          <div className="pe-wrap">
            <h2>Chega de caderno, planilha e conversa perdida no WhatsApp</h2>
            <div className="pe-pain-grid">
              {[['Quem pagou o pacote?', 'O saldo de sessões e o que está em aberto aparecem sozinhos, paciente por paciente.'],
                ['Esqueci de confirmar…', 'A confirmação sai com um toque, com a mensagem pronta no WhatsApp do paciente.'],
                ['"Tem horário amanhã?"', 'O paciente escolhe no seu site, entre os horários livres. Você só aceita.']].map(([q, a]) => (
                <div key={q} className="pe-pain"><strong>{q}</strong><p>{a}</p></div>
              ))}
            </div>
          </div>
        </section>

        <section className="pe-section pe-bonus" id="site-gratis">
          <div className="pe-wrap">
            <div className="pe-bonus-head">
              <span className="pe-gift-pill"><Icon name="gift" size={16} /> Bônus para assinantes</span>
              <h2>Assine o {PRODUCT_NAME} e ganhe o seu próprio site, grátis</h2>
              <p className="pe-text">Um site profissional para divulgar seu trabalho, pronto em minutos e sem pagar nada a mais por ele:
                sem hospedagem, sem programador e sem mensalidade extra. Você configura tudo pelo celular, quando quiser.</p>
            </div>
            <div className="pe-spot-grid">
              <div>
                <ul className="pe-checks">
                  <li><b>Configurável do seu jeito:</b> 4 modelos, cores tiradas da sua logo, foto de capa, fotos do espaço,
                    depoimentos e perguntas frequentes.</li>
                  <li><b>Serviços e pacotes</b> com preço ou “valor sob consulta”, como você preferir.</li>
                  <li><b>Agendamento online:</b> o paciente vê só os horários livres e pede com CPF. Você aceita no painel e a
                    sessão entra na agenda.</li>
                  <li><b>Botão do WhatsApp</b> e Pix na hora de agendar, se quiser.</li>
                  <li><b>Endereço próprio</b> para mandar aos pacientes e colocar na bio do Instagram.</li>
                </ul>
                <div className="pe-bonus-value">
                  <span>Em muitos serviços, site com agendamento é cobrado à parte.</span>
                  <strong>No {PRODUCT_NAME}: R$ 0 a mais, já vem na assinatura.</strong>
                </div>
              </div>
              <SiteMock />
            </div>
          </div>
        </section>

        <section className="pe-section" id="recursos">
          <div className="pe-wrap">
            <p className="pe-eyebrow pe-center">Recursos</p>
            <h2>Tudo o que a rotina de fisioterapia pede</h2>
            <div className="pe-features">
              {FEATURES.map(f => (
                <article key={f.title} className="pe-feature">
                  <span className="pe-feature-icon"><Icon name={f.icon} size={22} /></span>
                  <h3>{f.title}</h3><p>{f.text}</p>
                </article>
              ))}
            </div>
          </div>
        </section>

        <section className="pe-section pe-spot alt">
          <div className="pe-wrap pe-spot-grid reverse">
            <PixMock />
            <div>
              <p className="pe-eyebrow">Financeiro sem planilha</p>
              <h2>Pix sem taxa, maquininha com conta certa</h2>
              <ul className="pe-checks">
                <li>Pix com o valor exato e o código do pedido; o paciente envia o comprovante e você confere no banco.</li>
                <li>Cadastre as taxas da maquininha e veja o líquido e as datas de depósito de cada parcela.</li>
                <li>Quem está devendo, com lembrete gentil e o Pix copia e cola já na mensagem.</li>
                <li>Recibo para o IR em PDF, de um pagamento ou do ano inteiro.</li>
              </ul>
            </div>
          </div>
        </section>

        <section className="pe-section pe-care">
          <div className="pe-wrap pe-spot-grid">
            <div>
              <p className="pe-eyebrow">Feito para a sua realidade</p>
              <h2>Atende em casa? O PhysioEvo vai junto</h2>
              <p className="pe-text">Abra o dia no celular, chame o paciente no WhatsApp com um toque, registre a sessão e receba
                ali mesmo. Sem computador, sem papel — e sem pagar por mensagens.</p>
              {cta}
            </div>
            <CareIllustration />
          </div>
        </section>

        <section className="pe-section alt" id="como-funciona">
          <div className="pe-wrap">
            <p className="pe-eyebrow pe-center">Como funciona</p>
            <h2>Comece em 3 passos e já saia com o seu site</h2>
            <ol className="pe-steps">
              <li><b>Crie a conta</b><span>Nome da clínica, e-mail e senha. Leva um minuto.</span></li>
              <li><b>Cadastre serviços e pacotes</b><span>Com preço (ou “valor sob consulta”) e duração.</span></li>
              <li><b>Ganhe o seu site</b><span>Escolha o modelo, as cores e os horários e mande o link para os pacientes.</span></li>
            </ol>
          </div>
        </section>

        <section className="pe-section" id="planos">
          <div className="pe-wrap">
            <p className="pe-eyebrow pe-center">Planos</p>
            <h2>Comece sem pagar nada</h2>
            <div className="pe-plans">
              <div className="pe-plan featured">
                <span className="pe-badge">Lançamento</span>
                <h3>Profissional</h3>
                <p className="pe-price"><strong>30 dias grátis</strong><small>sem cartão de crédito</small></p>
                <p className="pe-plan-gift"><Icon name="gift" size={18} /> Site profissional grátis incluso</p>
                <ul>{['Agenda, pacotes e pacientes', 'Site configurável com agendamento online', 'Pix sem taxa e maquininha',
                  'Quem deve, a receber e recibos', 'Acesso para a equipe'].map(i => <li key={i}><Icon name="check" size={16} />{i}</li>)}</ul>
                {cta}
                <p className="pe-plan-note">O valor do plano será avisado antes de qualquer cobrança. Seus dados nunca são apagados.</p>
              </div>
            </div>
          </div>
        </section>

        <section className="pe-section pe-trustband">
          <div className="pe-wrap pe-trust-grid">
            {[['block', 'Cada clínica isolada', 'Seus dados nunca aparecem para outra clínica.'],
              ['check', 'LGPD desde o início', 'Consentimento no agendamento e dados mínimos.'],
              ['alert', 'Sem prontuário (por enquanto)', 'Só dados administrativos até cumprir as regras do COFFITO.'],
              ['phone', 'Funciona no celular', 'Adicione à tela inicial como um aplicativo.']].map(([icon, t, d]) => (
              <div key={t}><span className="pe-feature-icon"><Icon name={icon as IconName} size={20} /></span><b>{t}</b><p>{d}</p></div>
            ))}
          </div>
        </section>

        <section className="pe-section" id="duvidas">
          <div className="pe-wrap pe-faq">
            <p className="pe-eyebrow pe-center">Dúvidas</p>
            <h2>Perguntas frequentes</h2>
            {FAQ.map(f => <details key={f.q}><summary>{f.q}</summary><p>{f.a}</p></details>)}
          </div>
        </section>

        <section className="pe-final">
          <div className="pe-wrap">
            <h2>Sua clínica pronta para evoluir hoje</h2>
            <p>Assine, ganhe o seu site grátis e mande o link para os pacientes ainda hoje.</p>
            {signupEnabled ? <Link to="/cadastro" className="pe-btn pe-btn-light">Criar conta grátis</Link>
              : <Link to="/login" className="pe-btn pe-btn-light">Entrar</Link>}
          </div>
        </section>
      </main>

      <Footer contactEmail={contactEmail} />
    </div>
  );
}

function Footer({ contactEmail }: { contactEmail: string | null }) {
  return (
    <footer className="pe-footer">
      <div className="pe-wrap pe-footer-grid">
        <div><Logo height={30} dark /><p>Gestão e site com agendamento para fisioterapeutas.</p></div>
        <nav aria-label="Produto"><b>Produto</b><a href="/#recursos">Recursos</a><a href="/#planos">Planos</a>
          <Link to="/cadastro">Criar conta</Link><Link to="/login">Entrar</Link></nav>
        <nav aria-label="Legal"><b>Legal</b><Link to="/termos">Termos de uso</Link><Link to="/privacidade">Privacidade</Link>
          {contactEmail && <a href={`mailto:${contactEmail}`}>{contactEmail}</a>}</nav>
      </div>
      <div className="pe-wrap pe-copy">© {new Date().getFullYear()} {PRODUCT_NAME}. Todos os direitos reservados.</div>
    </footer>
  );
}

/** Moldura para páginas de texto (termos, privacidade). */
export function TextPage({ title, children }: { title: string; children: ReactNode }) {
  const { contactEmail } = usePlatform();
  return (
    <div className="pe">
      <header className="pe-nav">
        <div className="pe-wrap pe-nav-inner">
          <Link to="/" className="pe-nav-brand"><Logo height={32} /></Link>
          <nav className="pe-nav-links static"><Link to="/login" className="pe-nav-login">Entrar</Link></nav>
        </div>
      </header>
      <main className="pe-wrap legal"><h1>{title}</h1>{children}</main>
      <Footer contactEmail={contactEmail} />
    </div>
  );
}
