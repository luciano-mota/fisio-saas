/** Regras de texto do agendamento online (puras, testáveis sem navegador). */
import { addDays, dayLabel, timeOf, weekday } from './dates.ts';

export type OpeningHours = { weekday: number; start: string; end: string };

const SHORT = ['', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb', 'Dom'];

/** "08:00" → "8h"; "08:30" → "8h30". */
export function formatHour(time: string): string {
  const [h, m] = time.split(':');
  return `${Number(h)}h${m === '00' ? '' : m}`;
}

/**
 * Horário de atendimento em poucas linhas, juntando dias seguidos iguais:
 * ["Seg a Sex: 8h às 12h e 14h às 18h", "Sáb: 8h às 12h"].
 */
export function hoursSummary(hours: OpeningHours[]): string[] {
  const byDay = new Map<number, string>();
  for (let d = 1; d <= 7; d++) {
    const text = hours.filter(h => h.weekday === d)
      .sort((a, b) => a.start.localeCompare(b.start))
      .map(h => `${formatHour(h.start)} às ${formatHour(h.end)}`).join(' e ');
    if (text) byDay.set(d, text);
  }
  const lines: string[] = [];
  let d = 1;
  while (d <= 7) {
    const text = byDay.get(d);
    if (!text) { d++; continue; }
    let last = d;
    while (last < 7 && byDay.get(last + 1) === text) last++;
    const days = last === d ? SHORT[d] : last === d + 1 ? `${SHORT[d]} e ${SHORT[last]}` : `${SHORT[d]} a ${SHORT[last]}`;
    lines.push(`${days}: ${text}`);
    d = last + 1;
  }
  return lines;
}

/** Datas dos próximos {@code count} dias a partir de hoje (inclusive). */
export function nextDays(today: string, count: number): string[] {
  return Array.from({ length: count }, (_, i) => addDays(today, i));
}

/** "2026-10-16T10:00" → "sex, 16/10 às 10:00". */
export function whenLabel(localDateTime: string): string {
  return `${dayLabel(localDateTime)} às ${timeOf(localDateTime.length === 16 ? localDateTime + ':00' : localDateTime)}`;
}

/** Mensagem opcional do paciente para a clínica depois de pedir pelo site (sem CPF). */
export function requestSentMessage(clinicName: string, patientName: string, serviceName: string, startsAt: string): string {
  return `Olá! Sou ${patientName.split(' ')[0]} e acabei de pedir pelo site da ${clinicName} um horário de ${serviceName} ` +
    `para ${whenLabel(startsAt)}. Fico aguardando a confirmação.`;
}

/** Mensagem da clínica confirmando o pedido aceito. */
export function acceptedMessage(patientName: string, serviceName: string, startsAt: string): string {
  return `Olá, ${patientName.split(' ')[0]}! Seu horário de ${serviceName} está confirmado para ${whenLabel(startsAt)}. ` +
    'Qualquer imprevisto, é só avisar por aqui.';
}

/** Mensagem da clínica quando não consegue atender no horário pedido. */
export function declinedMessage(patientName: string, serviceName: string, startsAt: string, reason?: string | null): string {
  return `Olá, ${patientName.split(' ')[0]}! Infelizmente não consigo atender ${serviceName} em ${whenLabel(startsAt)}` +
    `${reason ? ` (${reason})` : ''}. Podemos combinar outro horário?`;
}

/** Dia da semana ISO (1 = segunda … 7 = domingo) de uma data. */
export function isoWeekdayOf(isoDate: string): number {
  const d = weekday(isoDate);
  return d === 0 ? 7 : d;
}
