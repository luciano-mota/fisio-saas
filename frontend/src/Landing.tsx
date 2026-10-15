import type { ReactNode } from 'react';
import { PRODUCT_NAME } from './product';
import { navigate } from './router';
import { Icon, type IconName } from './ui';
import './landing.css';

const FEATURES: { icon: IconName; title: string; text: string }[] = [
  { icon: 'calendar', title: 'Agenda no celular', text: 'Sessões avulsas ou recorrentes, remarcação em dois toques e pedido de confirmação pronto no WhatsApp.' },
  { icon: 'box', title: 'Pacotes com saldo', text: 'Venda pacotes de sessões e veja quantas faltam. O sistema avisa a hora de oferecer a renovação.' },
  { icon: 'globe', title: 'Seu site com agendamento', text: 'Página com a sua cara, horários livres e pedido com CPF — sem paciente duplicado no cadastro.' },
  { icon: 'money', title: 'Pix sem taxa', text: 'QR Code com o valor certo e comprovante enviado pelo paciente. O dinheiro cai direto na sua conta.' },
  { icon: 'tag', title: 'Maquininha sem susto', text: 'Cadastre as taxas e veja quanto cai na conta, quando cai e quem ainda está devendo.' },
  { icon: 'check', title: 'Recibo para o IR', text: 'Recibo com CPF, CREFITO e valor por extenso, pronto para imprimir ou mandar em PDF.' }
];

function Link({ to, className, children }: { to: string; className?: string; children: ReactNode }) {
  return <a href={to} className={className} onClick={e => { e.preventDefault(); navigate(to); window.scrollTo(0, 0); }}>{children}</a>;
}

/** Página do produto (quem ainda não entrou). */
export function Landing({ signupEnabled }: { signupEnabled: boolean }) {
  return (
    <div className="landing">
      <header className="landing-nav">
        <div className="landing-wrap">
          <strong className="landing-brand"><span className="brand-mark"><Icon name="calendar" size={18} /></span>{PRODUCT_NAME}</strong>
          <nav>
            <Link to="/login" className="landing-link">Entrar</Link>
            {signupEnabled && <Link to="/cadastro" className="button primary">Criar conta</Link>}
          </nav>
        </div>
      </header>

      <main>
        <section className="landing-hero">
          <div className="landing-wrap hero-grid">
            <div>
              <p className="landing-eyebrow">Para fisioterapeutas autônomos</p>
              <h1>Agenda, pacotes, cobrança e o seu site — tudo no celular.</h1>
              <p className="landing-lead">Saia do caderno e das mensagens perdidas no WhatsApp. Feito para quem atende sozinho,
                em consultório ou em casa.</p>
              <div className="landing-cta">
                {signupEnabled && <Link to="/cadastro" className="button primary big">Criar conta grátis</Link>}
                <Link to="/login" className="button big">Já tenho conta</Link>
              </div>
              <p className="landing-note">Grátis durante o período de testes. Sem cartão de crédito.</p>
            </div>
            <div className="landing-phone" aria-hidden="true">
              <div className="phone-top">Hoje · 4 sessões</div>
              {[['08:00', 'Maria S.', 'Pilates · 3 restantes', 'ok'], ['09:00', 'João P.', 'RPG · avulsa · a pagar', 'warn'],
                ['10:30', 'Ana L.', 'Drenagem · confirmada', 'ok'], ['14:00', 'Pedido pelo site', 'Pix enviado · conferir', 'new']].map(([t, n, d, s]) => (
                <div key={t} className={`phone-item ${s}`}><strong>{t}</strong><span><b>{n}</b>{d}</span></div>
              ))}
              <div className="phone-sum"><span>Recebido em outubro</span><strong>R$ 3.240,00</strong></div>
            </div>
          </div>
        </section>

        <section className="landing-section">
          <div className="landing-wrap">
            <h2>O que você resolve aqui</h2>
            <div className="feature-grid">
              {FEATURES.map(f => (
                <article key={f.title} className="feature">
                  <span className="feature-icon"><Icon name={f.icon} size={22} /></span>
                  <h3>{f.title}</h3>
                  <p>{f.text}</p>
                </article>
              ))}
            </div>
          </div>
        </section>

        <section className="landing-section alt">
          <div className="landing-wrap">
            <h2>Comece em 3 passos</h2>
            <ol className="landing-steps">
              <li><strong>Crie a conta</strong><span>Nome da clínica, e-mail e senha. Leva um minuto.</span></li>
              <li><strong>Cadastre serviços e pacotes</strong><span>Com preço (ou “valor sob consulta”) e duração.</span></li>
              <li><strong>Ligue o seu site</strong><span>Escolha as cores e o horário de atendimento e mande o link para os pacientes.</span></li>
            </ol>
            <p className="landing-privacy"><Icon name="alert" size={16} /> Sem prontuário: o sistema guarda só dados administrativos
              (agenda, pacotes, pagamentos). Cada clínica vê apenas os próprios dados.</p>
            {signupEnabled && <div className="landing-cta cta-end"><Link to="/cadastro" className="button primary big">Criar conta grátis</Link></div>}
          </div>
        </section>
      </main>

      <footer className="landing-foot">
        <div className="landing-wrap">
          <span>© {new Date().getFullYear()} {PRODUCT_NAME}</span>
          <nav><Link to="/termos">Termos de uso</Link><Link to="/privacidade">Privacidade</Link><Link to="/login">Entrar</Link></nav>
        </div>
      </footer>
    </div>
  );
}

/** Moldura simples para páginas de texto (termos, privacidade). */
export function TextPage({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="landing">
      <header className="landing-nav">
        <div className="landing-wrap">
          <Link to="/" className="landing-brand"><span className="brand-mark"><Icon name="calendar" size={18} /></span>{PRODUCT_NAME}</Link>
          <nav><Link to="/login" className="landing-link">Entrar</Link></nav>
        </div>
      </header>
      <main className="landing-wrap legal"><h1>{title}</h1>{children}</main>
      <footer className="landing-foot">
        <div className="landing-wrap">
          <span>© {new Date().getFullYear()} {PRODUCT_NAME}</span>
          <nav><Link to="/termos">Termos de uso</Link><Link to="/privacidade">Privacidade</Link></nav>
        </div>
      </footer>
    </div>
  );
}
