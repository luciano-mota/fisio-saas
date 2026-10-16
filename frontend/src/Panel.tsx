import { useCallback, useEffect, useState } from 'react';
import { api, logoUrl, signOut, type ClinicProfile, type Me } from './api';
import { themeVariables } from './color';
import { AgendaTab } from './Agenda';
import { CatalogTab } from './Catalog';
import { AccessNotice } from './AccessNotice';
import { AccountTab, MustChangePassword } from './Account';
import { BirthdayGreeting } from './Birthdays';
import { ClinicTab } from './ClinicTab';
import { FinanceTab, type FinanceSection } from './Finance';
import { PatientsTab } from './Patients';
import { PlatformNotices } from './PlatformNotices';
import { SubscriptionNotice } from './Subscription';
import { ReceiptPage } from './ReceiptPage';
import { CashFlowReport } from './CashFlowReport';
import { RequestsTab } from './Requests';
import { RequestAlert, useRequestWatch } from './RequestWatch';
import { useFonts } from './PublicPage';
import { navigate, usePath } from './router';
import { SiteTab } from './SiteTab';
import { TodayTab } from './Today';
import { avatarInitials } from './format';
import { Icon, Sheet, UiProvider, type IconName } from './ui';

type Tab = 'hoje' | 'agenda' | 'solicitacoes' | 'pacientes' | 'financeiro' | 'catalogo' | 'site' | 'clinica' | 'conta';
const NAV: { id: Tab; label: string; short?: string; icon: IconName }[] = [
  { id: 'hoje', label: 'Hoje', icon: 'home' },
  { id: 'agenda', label: 'Agenda', icon: 'calendar' },
  { id: 'solicitacoes', label: 'Solicitações', short: 'Pedidos', icon: 'bell' },
  { id: 'pacientes', label: 'Pacientes', icon: 'users' },
  { id: 'financeiro', label: 'Financeiro', icon: 'money' },
  { id: 'catalogo', label: 'Serviços e pacotes', short: 'Catálogo', icon: 'tag' },
  { id: 'site', label: 'Site', icon: 'globe' },
  { id: 'clinica', label: 'Clínica', icon: 'building' },
  { id: 'conta', label: 'Conta e equipe', icon: 'key' }
];
/** No celular, a barra de baixo tem as 4 do dia a dia; o resto fica em "Mais". */
const BOTTOM = NAV.slice(0, 4);
const MORE = NAV.slice(4);

/** Endereços antigos (abas separadas) continuam funcionando. */
const LEGACY: Record<string, string> = { servicos: '/painel/catalogo', pacotes: '/painel/catalogo/pacotes' };

function applyTheme(profile: ClinicProfile | null) {
  if (!profile) return;
  const vars = themeVariables({
    primary: profile.colorPrimary, secondary: profile.colorSecondary, accent: profile.colorAccent
  });
  Object.entries(vars).forEach(([name, value]) => document.documentElement.style.setProperty(name, value));
}

export function Panel({ me, onLogout, onRefresh }: { me: Me; onLogout: () => void; onRefresh: () => void }) {
  const path = usePath();
  const [, , segment = '', sub] = path.split('/');
  const tab = (NAV.find(t => t.id === segment)?.id ?? 'hoje') as Tab;
  const [profile, setProfile] = useState<ClinicProfile | null>(null);
  const [more, setMore] = useState(false);
  const [noticeDone, setNoticeDone] = useState(false);
  const [subDone, setSubDone] = useState(false);
  const subReady = useCallback(() => setSubDone(true), []);
  const [platformDone, setPlatformDone] = useState(false);
  const platformReady = useCallback(() => setPlatformDone(true), []);
  const owner = me.role === 'OWNER';
  useFonts('profissional');

  useEffect(() => {
    if (LEGACY[segment]) navigate(LEGACY[segment]);
  }, [segment]);
  useEffect(() => {
    api<ClinicProfile>('/api/v1/clinic').then(p => { setProfile(p); applyTheme(p); });
  }, []);
  const name = profile?.displayName ?? me.clinicName;
  // Pedidos do site: acompanhados sem recarregar a página (aviso, som, título da aba e notificação do sistema).
  const watch = useRequestWatch(name);
  const pending = watch.pending;
  useEffect(() => { setMore(false); window.scrollTo(0, 0); watch.refresh(); }, [tab]);

  async function logout() {
    await signOut().catch(() => undefined);
    onLogout();
  }

  const siteUrl = profile?.publicPageEnabled ? `/c/${profile.slug}` : null;
  const brand = profile?.logoVersion
    ? <img className="brand-mark logo" src={logoUrl(profile.logoVersion)!} alt="" />
    : <span className="brand-mark">{avatarInitials(name)}</span>;
  const saved = (p: ClinicProfile) => { setProfile(p); applyTheme(p); };

  // Primeiro acesso com senha temporária: troca antes de qualquer coisa.
  if (me.mustChangePassword) return <UiProvider><MustChangePassword me={me} onDone={onRefresh} onLogout={logout} /></UiProvider>;
  // Recibo: página limpa (sem menu), feita para imprimir ou salvar em PDF.
  if (segment === 'recibo') return <UiProvider><ReceiptPage /></UiProvider>;
  // Relatório de fluxo de caixa: página limpa para imprimir ou salvar em PDF.
  if (segment === 'relatorio') return <UiProvider><CashFlowReport source="/api/v1/finance/cashflow" back="/painel/financeiro" /></UiProvider>;

  return (
    <UiProvider>
      <div className="app">
        <aside className="sidebar" aria-label="Menu do painel">
          <div className="sidebar-brand">{brand}<strong>{name}</strong></div>
          <nav className="side-nav">
            {NAV.map(t => (
              <a key={t.id} href={`/painel/${t.id}`} className={t.id === tab ? 'active' : ''}
                 aria-current={t.id === tab ? 'page' : undefined}
                 onClick={e => { e.preventDefault(); navigate('/painel/' + t.id); }}>
                <Icon name={t.icon} />{t.label}
                {t.id === 'solicitacoes' && pending > 0 && <span className="badge" aria-label={`${pending} pendentes`}>{pending}</span>}</a>
            ))}
          </nav>
          <div className="sidebar-foot">
            {siteUrl && <a href={siteUrl} target="_blank" rel="noreferrer"><Icon name="external" size={18} /> Ver meu site</a>}
            <span className="muted small" title={me.email}>{me.email}</span>
            <button className="link-button" onClick={logout}><Icon name="logout" size={18} /> Sair</button>
          </div>
        </aside>

        <header className="mobile-top">
          {brand}<strong>{name}</strong>
          {siteUrl && <a className="icon-button" href={siteUrl} target="_blank" rel="noreferrer" aria-label="Ver meu site">
            <Icon name="globe" /></a>}
        </header>

        <main className="content">
          <AccessNotice email={me.email} onReady={() => setNoticeDone(true)} />
          <SubscriptionNotice email={me.email} owner={owner} active={noticeDone} onReady={subReady} />
          <PlatformNotices active={subDone} onReady={platformReady} />
          {!owner && <p className="notice small">Você está como colaborador: pode consultar, mas só a responsável altera.</p>}
          {tab === 'hoje' && <TodayTab owner={owner} profile={profile} pendingRequests={pending} />}
          {tab === 'agenda' && <AgendaTab owner={owner} action={sub} />}
          {tab === 'solicitacoes' && <RequestsTab owner={owner} onChanged={watch.refresh} version={watch.version} />}
          {tab === 'pacientes' && <PatientsTab owner={owner} patientId={sub} clinicName={name} />}
          {tab === 'financeiro' && <FinanceTab owner={owner} clinicName={name}
                                              section={(['devendo', 'pagamentos', 'receber', 'maquininhas', 'assinatura'].includes(sub ?? '') ? sub : 'resumo') as FinanceSection} />}
          {tab === 'catalogo' && <CatalogTab owner={owner} section={sub === 'pacotes' || sub === 'equipe' ? sub : 'servicos'} />}
          {tab === 'site' && profile && <SiteTab owner={owner} profile={profile} onSaved={saved} onLogoChange={setProfile} />}
          {tab === 'conta' && <AccountTab me={me} />}
          {tab === 'clinica' && profile && <ClinicTab owner={owner} profile={profile} onSaved={saved} />}
        </main>
        <RequestAlert alerts={watch.alerts} onDismiss={watch.dismiss} />
        {platformDone && <BirthdayGreeting email={me.email} clinicName={name} />}

        <nav className="bottom-nav" aria-label="Seções do painel">
          {BOTTOM.map(t => (
            <a key={t.id} href={`/painel/${t.id}`} className={t.id === tab ? 'active' : ''}
               aria-current={t.id === tab ? 'page' : undefined}
               onClick={e => { e.preventDefault(); navigate('/painel/' + t.id); }}>
              <Icon name={t.icon} size={22} /><span>{t.short ?? t.label}</span>
              {t.id === 'solicitacoes' && pending > 0 && <span className="badge dot" aria-label={`${pending} pendentes`}>{pending}</span>}</a>
          ))}
          <button className={MORE.some(t => t.id === tab) ? 'active' : ''} aria-haspopup="dialog"
                  onClick={() => setMore(true)}>
            <Icon name="more" size={22} /><span>Mais</span></button>
        </nav>

        {more && (
          <Sheet title="Mais" onClose={() => setMore(false)}>
            <div className="more-list">
              {MORE.map(t => (
                <button key={t.id} onClick={() => navigate('/painel/' + t.id)}><Icon name={t.icon} />{t.label}
                  <span className="chev"><Icon name="right" size={18} /></span></button>
              ))}
              {siteUrl && <a href={siteUrl} target="_blank" rel="noreferrer"><Icon name="external" />Ver meu site</a>}
              <button onClick={logout}><Icon name="logout" />Sair <span className="muted small">{me.email}</span></button>
            </div>
          </Sheet>
        )}
      </div>
    </UiProvider>
  );
}
