/**
 * Datas da agenda como texto local da clínica: "2026-10-15" e "2026-10-15T08:00:00".
 * Contas feitas em UTC para não sofrer com fuso/horário de verão do navegador.
 */

const WEEKDAYS = ['dom', 'seg', 'ter', 'qua', 'qui', 'sex', 'sáb'];

function parse(isoDate: string): Date {
  const [y, m, d] = isoDate.slice(0, 10).split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

function iso(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/** Data de hoje no relógio do aparelho. */
export function today(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** Agora como "YYYY-MM-DDTHH:MM", para comparar com os horários da agenda. */
export function nowLocal(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${today(now)}T${pad(now.getHours())}:${pad(now.getMinutes())}`;
}

export function addDays(isoDate: string, days: number): string {
  const date = parse(isoDate);
  date.setUTCDate(date.getUTCDate() + days);
  return iso(date);
}

/** Segunda-feira da semana da data. */
export function weekStart(isoDate: string): string {
  const weekday = parse(isoDate).getUTCDay();
  return addDays(isoDate, weekday === 0 ? -6 : 1 - weekday);
}

/** 0 = domingo … 6 = sábado. */
export function weekday(isoDate: string): number {
  return parse(isoDate).getUTCDay();
}

/** "2026-10-15" → "qui, 15/10". */
export function dayLabel(isoDate: string): string {
  const [, m, d] = isoDate.slice(0, 10).split('-');
  return `${WEEKDAYS[weekday(isoDate)]}, ${d}/${m}`;
}

/** "2026-10-15T08:00:00" → "08:00". */
export function timeOf(localDateTime: string): string {
  return localDateTime.slice(11, 16);
}

export function dateOf(localDateTime: string): string {
  return localDateTime.slice(0, 10);
}

/** Minutos entre dois horários locais do mesmo formato. */
export function minutesBetween(start: string, end: string): number {
  const toMinutes = (value: string) => parse(value).getTime() / 60000 + Number(value.slice(11, 13)) * 60 + Number(value.slice(14, 16));
  return toMinutes(end) - toMinutes(start);
}
