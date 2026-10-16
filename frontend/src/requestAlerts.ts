import { whenLabel } from './booking.ts';

/** Pedido pendente recente, como vem de /booking-requests/count. */
export type RecentRequest = { id: string; fullName: string; serviceName: string; startsAt: string; createdAt: number };

/** Pedidos que chegaram depois do último que o painel já viu (o primeiro carregamento não avisa nada). */
export function newSince(recent: RecentRequest[], lastSeen: number | null): RecentRequest[] {
  if (lastSeen == null) return [];
  return recent.filter(r => r.createdAt > lastSeen).sort((a, b) => a.createdAt - b.createdAt);
}

/** Maior data de criação vista até agora. */
export function latestSeen(recent: RecentRequest[], lastSeen: number | null): number {
  return recent.reduce((max, r) => Math.max(max, r.createdAt), lastSeen ?? 0);
}

/** "Maria · Pilates · qui, 16/10 às 08:00". */
export function requestLine(r: RecentRequest): string {
  return `${r.fullName.split(' ')[0]} · ${r.serviceName} · ${whenLabel(r.startsAt)}`;
}

/** Texto do aviso de pedido novo (um ou vários). */
export function alertTitle(count: number): string {
  return count === 1 ? 'Novo pedido de agendamento' : `${count} novos pedidos de agendamento`;
}

/** Título da aba do navegador com os pedidos pendentes na frente: "(2) Clínica Ana · PhysioEvo". */
export function tabTitle(clinicName: string, pending: number): string {
  const base = `${clinicName} · PhysioEvo`;
  return pending > 0 ? `(${pending}) ${base}` : base;
}
