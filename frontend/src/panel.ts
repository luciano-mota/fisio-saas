/** Regras de tela do painel (puras, testáveis sem navegador). */
import { weekday } from './dates.ts';

type Item = { kind: 'SESSION' | 'BLOCK'; status: 'SCHEDULED' | 'CONFIRMED' | 'DONE' | 'NO_SHOW' | 'CANCELED' };

const DAYS = ['domingo', 'segunda-feira', 'terça-feira', 'quarta-feira', 'quinta-feira', 'sexta-feira', 'sábado'];
const MONTHS = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro',
  'novembro', 'dezembro'];
const SHORT_DAYS = ['dom', 'seg', 'ter', 'qua', 'qui', 'sex', 'sáb'];

export function greeting(hour: number): string {
  if (hour < 5) return 'Boa noite';
  if (hour < 12) return 'Bom dia';
  if (hour < 18) return 'Boa tarde';
  return 'Boa noite';
}

/** "Dra. Ana Ribeiro" → "Ana"; ignora títulos comuns. */
export function firstName(name: string | null | undefined): string {
  const words = (name ?? '').trim().split(/\s+/).filter(w => !/^(dra?|dr|ft|fisio)\.?$/i.test(w));
  return words[0] ?? '';
}

/** "2026-10-14" → "quarta-feira, 14 de outubro". */
export function longDayLabel(isoDate: string): string {
  const [, m, d] = isoDate.split('-').map(Number);
  return `${DAYS[weekday(isoDate)]}, ${d} de ${MONTHS[m - 1]}`;
}

/** Para a faixa de dias da semana: { dia: "qua", numero: "14" }. */
export function dayChip(isoDate: string): { day: string; number: string } {
  return { day: SHORT_DAYS[weekday(isoDate)], number: String(Number(isoDate.slice(8, 10))) };
}

/** Resumo do dia: sessões (sem bloqueios e canceladas), quantas faltam confirmar e quantas já foram realizadas. */
export function daySummary(items: Item[]): { sessions: number; toConfirm: number; done: number } {
  const sessions = items.filter(i => i.kind === 'SESSION' && i.status !== 'CANCELED');
  return {
    sessions: sessions.length,
    toConfirm: sessions.filter(i => i.status === 'SCHEDULED').length,
    done: sessions.filter(i => i.status === 'DONE').length
  };
}

export function summaryText(s: { sessions: number; toConfirm: number; done: number }): string {
  if (s.sessions === 0) return 'Nenhuma sessão';
  const parts = [`${s.sessions} ${s.sessions === 1 ? 'sessão' : 'sessões'}`];
  if (s.toConfirm) parts.push(`${s.toConfirm} a confirmar`);
  if (s.done) parts.push(`${s.done} ${s.done === 1 ? 'realizada' : 'realizadas'}`);
  return parts.join(' · ');
}

export type PrimaryAction = 'ask-confirmation' | 'confirm' | 'done' | null;

/**
 * A ação que aparece em destaque na sessão; as outras ficam no menu "⋯".
 * Antes do horário: pedir confirmação pelo WhatsApp (ou marcar confirmada, sem WhatsApp). Depois: realizada.
 */
export function primaryAction(item: Item, started: boolean, hasWhatsapp: boolean): PrimaryAction {
  if (item.kind !== 'SESSION') return null;
  const open = item.status === 'SCHEDULED' || item.status === 'CONFIRMED';
  if (!open) return null;
  if (started) return 'done';
  if (item.status === 'SCHEDULED') return hasWhatsapp ? 'ask-confirmation' : 'confirm';
  return null;
}
