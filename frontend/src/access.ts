import type { ClinicAccessView, EffectiveStatus } from './api.ts';

/** Nome da situação para a equipe da plataforma e para a clínica. */
export const STATUS_LABEL: Record<EffectiveStatus, string> = {
  TRIAL: 'Em teste', ACTIVE: 'Assinante', FREE: 'Acesso gratuito', EXPIRED: 'Vencida', SUSPENDED: 'Suspensa', BLOCKED: 'Bloqueada'
};

/** "15/10/2026". */
export function dateBrFull(iso: string): string {
  const [y, m, d] = iso.split('-');
  return `${d}/${m}/${y}`;
}

/** "hoje", "amanhã" ou "em 5 dias". */
export function inDays(days: number): string {
  if (days <= 0) return 'hoje';
  return days === 1 ? 'amanhã' : `em ${days} dias`;
}

/** Dias em que o aviso de vencimento abre como janela (nos outros, fica só a faixa no topo). */
export const MODAL_DAYS = [7, 3, 1, 0];

export type AccessNotice = { level: 'info' | 'warn' | 'danger'; title: string; text: string; modal: boolean };

/** Aviso dentro do painel: vencimento chegando (7 dias), prazo vencido ou acesso suspenso. Null = nada a avisar. */
export function accessNotice(view: ClinicAccessView): AccessNotice | null {
  const keep = 'Seus dados continuam guardados.';
  if (view.warning === 'SUSPENDED') {
    return { level: 'danger', title: 'Acesso suspenso', modal: true,
      text: `O painel está só para consulta e o agendamento pelo site está pausado. ${keep} Fale com o suporte para reativar.` };
  }
  if (view.warning === 'EXPIRED') {
    const what = view.status === 'TRIAL' ? 'O período de teste terminou' : view.status === 'FREE' ? 'O acesso gratuito terminou' : 'A assinatura venceu';
    return { level: 'danger', title: what, modal: true,
      text: `O painel está só para consulta e o agendamento pelo site está pausado. ${keep} Fale com o suporte para continuar usando.` };
  }
  if (view.warning === 'SOON' && view.daysLeft != null && view.accessUntil) {
    const what = view.status === 'TRIAL' ? 'Seu período de teste' : view.status === 'FREE' ? 'Seu acesso gratuito' : 'Sua assinatura';
    const ends = view.status === 'ACTIVE' ? 'vence' : 'termina';
    return { level: view.daysLeft <= 1 ? 'warn' : 'info', modal: MODAL_DAYS.includes(view.daysLeft),
      title: `${what} ${ends} ${inDays(view.daysLeft)}`,
      text: `Dia ${dateBrFull(view.accessUntil)}. Depois disso o painel fica só para consulta, e seus dados continuam guardados. ` +
        'Fale com o suporte para continuar sem interrupção.' };
  }
  return null;
}

/** Chave para abrir a janela do aviso uma vez por dia para cada pessoa. */
export function accessSeenKey(email: string, day: string, warning: string): string {
  return `physioevo:acesso:${email.toLowerCase()}:${day}:${warning}`;
}
