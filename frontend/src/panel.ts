/** Regras de tela do painel (puras, testáveis sem navegador). */
import { weekday } from './dates.ts';

type Item = { kind: 'SESSION' | 'BLOCK'; status: 'SCHEDULED' | 'CONFIRMED' | 'DONE' | 'NO_SHOW' | 'CANCELED'; startsAt?: string };

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

/**
 * Resumo do dia: sessões (sem bloqueios e canceladas), quantas faltam confirmar (só as que ainda não começaram),
 * quantas já passaram sem registro (nem realizada nem falta) e quantas foram realizadas. {@code now}: "AAAA-MM-DDTHH:MM".
 */
export function daySummary(items: Item[], now = ''): { sessions: number; toConfirm: number; pending: number; done: number } {
  const sessions = items.filter(i => i.kind === 'SESSION' && i.status !== 'CANCELED');
  const started = (i: Item) => !!now && !!i.startsAt && i.startsAt.slice(0, 16) <= now;
  return {
    sessions: sessions.length,
    toConfirm: sessions.filter(i => i.status === 'SCHEDULED' && !started(i)).length,
    pending: sessions.filter(i => (i.status === 'SCHEDULED' || i.status === 'CONFIRMED') && started(i)).length,
    done: sessions.filter(i => i.status === 'DONE').length
  };
}

export function summaryText(s: { sessions: number; toConfirm: number; pending?: number; done: number }): string {
  if (s.sessions === 0) return 'Nenhuma sessão';
  const parts = [sessionsCount(s.sessions)];
  if (s.toConfirm) parts.push(`${s.toConfirm} a confirmar`);
  if (s.pending) parts.push(`${s.pending} sem registro`);
  if (s.done) parts.push(`${s.done} ${s.done === 1 ? 'realizada' : 'realizadas'}`);
  return parts.join(' · ');
}

/** "nenhuma sessão", "1 sessão", "3 sessões". */
export function sessionsCount(n: number): string {
  if (n === 0) return 'nenhuma sessão';
  return n === 1 ? '1 sessão' : `${n} sessões`;
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
