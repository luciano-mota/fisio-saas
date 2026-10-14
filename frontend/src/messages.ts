import { addDays, dayLabel, weekday } from './dates.ts';

/** Mensagens prontas para o WhatsApp (link wa.me; a fisioterapeuta só toca em enviar). */

export function firstName(fullName: string | null | undefined): string {
  return (fullName ?? '').trim().split(/\s+/)[0] ?? '';
}

export function sessionsLabel(count: number): string {
  if (count <= 0) return 'nenhuma sessão';
  return count === 1 ? '1 sessão' : `${count} sessões`;
}

/** Aviso de saldo baixo, oferecendo renovação. */
export function renewalMessage(patientName: string, pkg: { name: string; serviceName: string; remaining: number }): string {
  const remaining = pkg.remaining <= 0
    ? `as sessões do seu pacote de ${pkg.serviceName} (${pkg.name}) terminaram`
    : `${pkg.remaining === 1 ? 'resta' : 'restam'} ${sessionsLabel(pkg.remaining)} do seu pacote de ${pkg.serviceName} (${pkg.name})`;
  return `Olá, ${firstName(patientName)}! Passando para avisar que ${remaining}. ` +
    'Quer renovar para não interromper o tratamento?';
}

/** "08:00" → "8h"; "08:30" → "8h30". */
export function hourLabel(time: string): string {
  const [h, m] = time.split(':');
  return `${Number(h)}h${m === '00' ? '' : m}`;
}

/** "hoje", "amanhã" ou "na qui, 15/10" em relação à data de hoje. */
export function dayPhrase(isoDate: string, todayIso: string): string {
  if (isoDate === todayIso) return 'hoje';
  if (isoDate === addDays(todayIso, 1)) return 'amanhã';
  return `${weekday(isoDate) === 0 || weekday(isoDate) === 6 ? 'no' : 'na'} ${dayLabel(isoDate)}`;
}

/** Pedido de confirmação da sessão: "Olá, Maria! Confirmando sua sessão de Pilates amanhã (qui, 15/10) às 8h. …" */
export function confirmationMessage(patientName: string, serviceName: string, startsAt: string, todayIso: string): string {
  const date = startsAt.slice(0, 10);
  const phrase = dayPhrase(date, todayIso);
  const when = phrase === 'hoje' || phrase === 'amanhã' ? `${phrase} (${dayLabel(date)})` : phrase;
  return `Olá, ${firstName(patientName)}! Confirmando sua sessão de ${serviceName} ${when} às ${hourLabel(startsAt.slice(11, 16))}. ` +
    'Responda SIM para confirmar.';
}
