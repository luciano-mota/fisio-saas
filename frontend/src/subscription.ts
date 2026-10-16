import type { SubscriptionCharge } from './api.ts';

/** Situação da mensalidade vista pela clínica (é despesa dela). */
export function clinicStatusLabel(c: SubscriptionCharge): string {
  if (c.status === 'PAID') return 'Paga';
  if (c.status === 'INFORMED') return 'Aguardando confirmação';
  if (c.status === 'CANCELED') return 'Cancelada';
  return c.overdue ? 'Atrasada' : 'A pagar';
}

/** Situação vista pela plataforma (é receita dela). */
export function adminStatusLabel(c: SubscriptionCharge): string {
  if (c.status === 'PAID') return 'Recebida';
  if (c.status === 'INFORMED') return 'Pagamento informado';
  if (c.status === 'CANCELED') return 'Cancelada';
  return c.overdue ? 'Atrasada' : 'Em aberto';
}

/** Classe de cor da situação. */
export function statusTone(c: SubscriptionCharge): 'ok' | 'info' | 'warn' | 'danger' | 'muted' {
  if (c.status === 'PAID') return 'ok';
  if (c.status === 'INFORMED') return 'info';
  if (c.status === 'CANCELED') return 'muted';
  return c.overdue ? 'danger' : 'warn';
}

function days(from: string, to: string): number {
  return Math.round((Date.parse(to + 'T12:00:00Z') - Date.parse(from + 'T12:00:00Z')) / 86_400_000);
}

export type ChargeNotice = { charge: SubscriptionCharge; level: 'info' | 'warn' | 'danger'; title: string; text: string; modal: boolean };

/**
 * Aviso de mensalidade no painel: a pagar vencendo em até 7 dias ou atrasada (com QR Code), ou pagamento informado
 * aguardando confirmação. A janela abre nos dias 7, 3, 1 e 0 antes do vencimento, e todo dia quando atrasada.
 */
export function chargeNotice(charges: SubscriptionCharge[], today: string): ChargeNotice | null {
  const open = charges.filter(c => c.status === 'OPEN').sort((a, b) => a.dueOn.localeCompare(b.dueOn));
  const next = open[0];
  if (next) {
    const left = days(today, next.dueOn);
    const due = next.dueOn.split('-').reverse().join('/');
    if (left < 0) {
      return { charge: next, level: 'danger', modal: true, title: `${next.description} atrasada`,
        text: `Venceu em ${due}. Pague pelo Pix e toque em "Pagamento realizado" para não ter o acesso suspenso.` };
    }
    if (left <= 7) {
      const when = left === 0 ? 'vence hoje' : left === 1 ? 'vence amanhã' : `vence em ${left} dias`;
      return { charge: next, level: left <= 1 ? 'warn' : 'info', modal: [7, 3, 1, 0].includes(left),
        title: `${next.description} ${when}`,
        text: `Vencimento em ${due}. Pague pelo Pix com o QR Code e toque em "Pagamento realizado".` };
    }
  }
  const informed = charges.find(c => c.status === 'INFORMED');
  if (informed) {
    return { charge: informed, level: 'info', modal: false, title: 'Pagamento informado',
      text: `${informed.description}: aguardando a confirmação da PhysioEvo. Assim que confirmarmos, ela aparece como paga.` };
  }
  return null;
}

/** Chave para abrir a janela da mensalidade uma vez por dia por pessoa. */
export function chargeSeenKey(email: string, chargeId: string, day: string): string {
  return `physioevo:mensalidade:${email.toLowerCase()}:${chargeId}:${day}`;
}
