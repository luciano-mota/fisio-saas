import { firstName } from './messages.ts';

/** Mensagem de parabéns: troca {nome} (primeiro nome), {idade} e {clinica} no modelo da clínica. */
export function birthdayMessage(template: string, data: { fullName: string; age: number; clinicName: string }): string {
  return template
    .replace(/\{nome\}/gi, firstName(data.fullName))
    .replace(/\{idade\}/gi, String(data.age))
    .replace(/\{cl[ií]nica\}/gi, data.clinicName);
}

/** Insere o texto na posição do cursor (ou no fim) e devolve o novo texto e onde o cursor fica. */
export function insertAt(text: string, insert: string, start?: number | null, end?: number | null): { text: string; cursor: number } {
  const from = start == null ? text.length : Math.max(0, Math.min(start, text.length));
  const to = end == null ? from : Math.max(from, Math.min(end, text.length));
  return { text: text.slice(0, from) + insert + text.slice(to), cursor: from + insert.length };
}

/** "hoje", "amanhã", "ontem" ou "15/10" para a lista de aniversariantes. */
export function birthdayDayLabel(date: string, today: string): string {
  const diff = Math.round((Date.parse(date + 'T12:00:00Z') - Date.parse(today + 'T12:00:00Z')) / 86_400_000);
  if (diff === 0) return 'hoje';
  if (diff === 1) return 'amanhã';
  if (diff === -1) return 'ontem';
  const [, m, d] = date.split('-');
  return `${d}/${m}`;
}

/** Período de cada filtro da sub-aba, a partir de hoje (no fuso da clínica). */
export type BirthdayRange = 'hoje' | 'semana' | 'mes';
export function rangeFor(range: BirthdayRange, today: string): { from: string; to: string } {
  const [y, m] = today.split('-').map(Number);
  if (range === 'hoje') return { from: today, to: today };
  if (range === 'semana') {
    const end = new Date(Date.parse(today + 'T12:00:00Z') + 6 * 86_400_000).toISOString().slice(0, 10);
    return { from: today, to: end };
  }
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const mm = String(m).padStart(2, '0');
  return { from: `${y}-${mm}-01`, to: `${y}-${mm}-${String(last).padStart(2, '0')}` };
}

/** Emojis para a mensagem (sem biblioteca): festa, carinho e saúde. */
export const EMOJIS = [
  '🎉', '🎂', '🥳', '🎁', '🎈', '🎊', '🍰', '🧁', '🥂', '✨', '🌟', '💫',
  '💚', '💙', '💜', '❤️', '🧡', '💛', '🤗', '😊', '😄', '😍', '🙏', '👏',
  '💪', '🏃', '🧘', '🤸', '🦵', '🦴', '🩺', '🌷', '🌻', '🌈', '☀️', '🍀'
];

/** Chave para mostrar o aviso de aniversariantes uma vez por dia para cada pessoa. */
export function seenKey(email: string, today: string): string {
  return `physioevo:aniversarios:${email.toLowerCase()}:${today}`;
}
