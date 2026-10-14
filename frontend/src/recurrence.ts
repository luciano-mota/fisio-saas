import { addDays, weekday } from './dates.ts';

export const MAX_OCCURRENCES = 100;
/** Dias da semana no padrão ISO (1 = segunda … 7 = domingo), como a API espera. */
export const WEEKDAYS = [
  { iso: 1, label: 'Seg' }, { iso: 2, label: 'Ter' }, { iso: 3, label: 'Qua' }, { iso: 4, label: 'Qui' },
  { iso: 5, label: 'Sex' }, { iso: 6, label: 'Sáb' }, { iso: 7, label: 'Dom' }
];

export function isoWeekday(isoDate: string): number {
  const day = weekday(isoDate);
  return day === 0 ? 7 : day;
}

/**
 * Mesmas regras do backend (Recurrence.java): "por N semanas" conta a partir da data inicial; com semanas e
 * quantidade, para no que vier primeiro. Devolve até MAX_OCCURRENCES + 1 datas, para a tela avisar do excesso.
 */
export function seriesDates(start: string, weekdays: number[], weeks?: number, count?: number): string[] {
  if (weekdays.length === 0 || (!weeks && !count)) return [];
  const totalDays = (weeks ?? MAX_OCCURRENCES) * 7;
  const limit = Math.min(count ?? Infinity, MAX_OCCURRENCES + 1);
  const dates: string[] = [];
  for (let i = 0; i < totalDays && dates.length < limit; i++) {
    const day = addDays(start, i);
    if (weekdays.includes(isoWeekday(day))) dates.push(day);
  }
  return dates;
}
