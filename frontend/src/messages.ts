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
