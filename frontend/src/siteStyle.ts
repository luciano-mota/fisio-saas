/**
 * Estilo do site da clínica a partir do que ela oferece. Tudo determinístico e testável: a mesma clínica gera
 * sempre o mesmo site, e a clínica pode trocar o estilo, as fontes e a frase no painel.
 */

export type StyleId = 'sereno' | 'dinamico' | 'acolhedor' | 'clinico';
export type FontsId = 'elegante' | 'classica' | 'moderna' | 'geometrica' | 'suave' | 'profissional';
export type IconId = 'pilates' | 'spine' | 'hands' | 'home' | 'child' | 'lungs' | 'sport' | 'brain' | 'heart' |
  'sparkle' | 'pelvic' | 'activity';

export const STYLES: Record<StyleId, { label: string; description: string; fonts: FontsId; eyebrow: string }> = {
  sereno: {
    label: 'Sereno', description: 'Leve e elegante. Combina com Pilates, estética e bem-estar.',
    fonts: 'elegante', eyebrow: 'Movimento, equilíbrio e bem-estar'
  },
  dinamico: {
    label: 'Dinâmico', description: 'Forte e enérgico. Combina com esportiva, ortopedia e traumato.',
    fonts: 'moderna', eyebrow: 'Reabilitação e performance'
  },
  acolhedor: {
    label: 'Acolhedor', description: 'Próximo e gentil. Combina com infantil, idosos e domiciliar.',
    fonts: 'suave', eyebrow: 'Cuidado de perto, com carinho'
  },
  clinico: {
    label: 'Clínico', description: 'Técnico e confiável. Combina com respiratória, neuro e pélvica.',
    fonts: 'profissional', eyebrow: 'Fisioterapia baseada em evidências'
  }
};

/** Pares de fontes gratuitas (Google Fonts): títulos + texto. Lista fechada, igual à aceita pelo backend. */
export const FONTS: Record<FontsId, { label: string; heading: string; body: string; families: string[] }> = {
  elegante: { label: 'Elegante', heading: 'Cormorant Garamond', body: 'Lato',
    families: ['Cormorant+Garamond:wght@500;600;700', 'Lato:wght@400;700'] },
  classica: { label: 'Clássica', heading: 'Playfair Display', body: 'Source Sans 3',
    families: ['Playfair+Display:wght@600;700', 'Source+Sans+3:wght@400;600'] },
  moderna: { label: 'Moderna', heading: 'Montserrat', body: 'Inter',
    families: ['Montserrat:wght@700;800', 'Inter:wght@400;600'] },
  geometrica: { label: 'Geométrica', heading: 'Poppins', body: 'DM Sans',
    families: ['Poppins:wght@600;700', 'DM+Sans:wght@400;500;700'] },
  suave: { label: 'Suave', heading: 'Nunito', body: 'Nunito Sans',
    families: ['Nunito:wght@700;800', 'Nunito+Sans:wght@400;600'] },
  profissional: { label: 'Profissional', heading: 'Plus Jakarta Sans', body: 'Plus Jakarta Sans',
    families: ['Plus+Jakarta+Sans:wght@400;500;600;700'] }
};

export function fontsUrl(id: FontsId): string {
  return `https://fonts.googleapis.com/css2?${FONTS[id].families.map(f => 'family=' + f).join('&')}&display=swap`;
}

/** Minúsculas e sem acento, para comparar palavras ("Estética" → "estetica"). */
export function fold(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
}

const KEYWORDS: Record<StyleId, string[]> = {
  sereno: ['pilates', 'estetic', 'dermato', 'drenagem', 'linfatic', 'massag', 'relax', 'yoga', 'acupunt',
    'bem-estar', 'bem estar', 'miofascial', 'ventosa', 'spa', 'facial', 'corporal'],
  dinamico: ['esportiv', 'esporte', 'atleta', 'ortoped', 'traumato', 'lesao', 'lesoes', 'joelho', 'ombro',
    'crossfit', 'corrida', 'quiropraxia', 'osteopatia', 'funcional', 'treino', 'performance', 'agulhamento',
    'liberacao', 'tendin', 'entorse'],
  acolhedor: ['infantil', 'pediatr', 'crianca', 'bebe', 'neonat', 'idoso', 'geriatr', 'domiciliar', 'em casa',
    'home care', 'materna', 'gestante', 'gestacao', 'pos-parto', 'pos parto', 'terceira idade'],
  clinico: ['respirator', 'cardio', 'pulmon', 'neuro', 'avc', 'parkinson', 'pelvic', 'uroginec', 'incontinen',
    'hospitalar', 'uti', 'vestibular', 'labirint', 'disfun']
};

export type Classification = { style: StyleId; scores: Record<StyleId, number>; reasons: string[] };

/**
 * Estilo mais combinado com os serviços. Nome do serviço pesa 3, descrição e "sobre" pesam 1.
 * Sem pistas, fica "clínico" (neutro e profissional).
 */
export function classify(services: { name: string; description?: string | null }[], about?: string | null): Classification {
  const scores: Record<StyleId, number> = { sereno: 0, dinamico: 0, acolhedor: 0, clinico: 0 };
  const hits: Record<StyleId, string[]> = { sereno: [], dinamico: [], acolhedor: [], clinico: [] };
  const score = (text: string | null | undefined, weight: number, label?: string) => {
    if (!text) return;
    const t = fold(text);
    for (const style of Object.keys(KEYWORDS) as StyleId[]) {
      if (KEYWORDS[style].some(k => t.includes(k))) {
        scores[style] += weight;
        if (label && !hits[style].includes(label)) hits[style].push(label);
      }
    }
  };
  for (const s of services) {
    score(s.name, 3, s.name);
    score(s.description, 1, s.name);
  }
  score(about, 1);
  // Empate: vence o estilo mais específico (clínico é o neutro, fica por último).
  const order: StyleId[] = ['sereno', 'dinamico', 'acolhedor', 'clinico'];
  const best = order.reduce((a, b) => (scores[b] > scores[a] ? b : a));
  const style = scores[best] === 0 ? 'clinico' : best;
  return { style, scores, reasons: hits[style] };
}

export function resolveStyle(chosen: string | null | undefined, services: { name: string; description?: string | null }[],
                             about?: string | null): StyleId {
  return chosen && chosen in STYLES ? chosen as StyleId : classify(services, about).style;
}

export function resolveFonts(chosen: string | null | undefined, style: StyleId): FontsId {
  return chosen && chosen in FONTS ? chosen as FontsId : STYLES[style].fonts;
}

/** Ícone do serviço pelo nome/descrição. */
export function serviceIcon(name: string, description?: string | null): IconId {
  const t = fold(`${name} ${description ?? ''}`);
  const rules: [IconId, string[]][] = [
    ['pilates', ['pilates', 'yoga', 'alongamento']],
    ['child', ['infantil', 'pediatr', 'crianca', 'bebe', 'neonat']],
    ['home', ['domiciliar', 'em casa', 'home care']],
    ['lungs', ['respirator', 'pulmon']],
    ['brain', ['neuro', 'avc', 'parkinson', 'vestibular', 'labirint']],
    ['heart', ['cardio']],
    ['pelvic', ['pelvic', 'uroginec', 'incontinen', 'gestante', 'pos-parto', 'materna']],
    ['sport', ['esportiv', 'esporte', 'atleta', 'corrida', 'crossfit', 'funcional', 'treino']],
    ['sparkle', ['estetic', 'facial', 'dermato', 'corporal']],
    ['hands', ['drenagem', 'massag', 'manual', 'miofascial', 'liberacao', 'quiropraxia', 'osteopatia', 'ventosa', 'acupunt']],
    ['spine', ['rpg', 'postur', 'coluna', 'ortoped', 'traumato', 'joelho', 'ombro', 'lombar']]
  ];
  return rules.find(([, words]) => words.some(w => t.includes(w)))?.[0] ?? 'activity';
}

/** "A, B e C". */
export function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? '';
  return `${names.slice(0, -1).join(', ')} e ${names[names.length - 1]}`;
}

/** Frase de destaque automática (a clínica pode escrever a dela no painel). */
export function autoHeadline(style: StyleId, services: { name: string; description?: string | null }[]): string {
  const homeCare = services.some(s => serviceIcon(s.name, s.description) === 'home');
  switch (style) {
    case 'sereno': return 'Movimento com leveza, corpo em equilíbrio';
    case 'dinamico': return 'Volte a se mover com força e segurança';
    case 'acolhedor': return homeCare ? 'Fisioterapia de qualidade, no conforto da sua casa' : 'Cuidado gentil para cada fase da vida';
    default: return 'Fisioterapia com técnica, cuidado e confiança';
  }
}

/** Subtítulo com os principais serviços: "Pilates, RPG e Drenagem com atendimento individual." */
export function autoSubtitle(services: { name: string }[]): string {
  const names = services.slice(0, 3).map(s => s.name);
  if (names.length === 0) return 'Atendimento individual, do jeito que você precisa.';
  return `${joinNames(names)} com atendimento individual e acompanhamento de perto.`;
}
