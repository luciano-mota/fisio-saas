/** Regras de texto do site público (puras, testáveis sem navegador). */

/** Formação em itens: uma por linha ou separadas por ";" ("Graduação UFMA; Pilates clínico" → 2 itens). */
export function educationItems(text: string | null | undefined): string[] {
  if (!text) return [];
  return text.split(/\r?\n|;/).map(item => item.replace(/^[\s•\-–]+/, '').trim()).filter(Boolean);
}

/** Mapa incorporado do Google pelo endereço (sem chave de API, custo zero). */
export function mapEmbedUrl(address: string): string {
  return `https://www.google.com/maps?q=${encodeURIComponent(address)}&output=embed`;
}

/** Link para abrir o endereço no app de mapas do celular. */
export function mapLinkUrl(address: string): string {
  return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(address)}`;
}

/** Mensagem pronta do WhatsApp; com serviço, já diz o que a pessoa quer agendar. */
export function bookingMessage(clinicName: string, serviceName?: string): string {
  return serviceName
    ? `Olá! Vi o site da ${clinicName} e gostaria de agendar ${serviceName}. Quais horários você tem?`
    : `Olá! Vi o site da ${clinicName} e gostaria de agendar uma avaliação.`;
}

/** URL pública de uma foto do site. */
export function photoUrl(slug: string, photoId: string): string {
  return `/api/v1/public/clinics/${slug}/photos/${photoId}`;
}
