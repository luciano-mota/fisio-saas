/** Formatação de dados brasileiros para exibição (o backend guarda só dígitos). */

export function onlyDigits(text: string | null | undefined): string {
  return (text ?? '').replace(/\D/g, '');
}

/** "52998224725" → "529.982.247-25". Outros tamanhos ficam como vieram. */
export function formatCpf(cpf: string | null | undefined): string {
  const d = onlyDigits(cpf);
  return d.length === 11 ? `${d.slice(0, 3)}.${d.slice(3, 6)}.${d.slice(6, 9)}-${d.slice(9)}` : (cpf ?? '');
}

/** "98999991234" → "(98) 99999-1234"; aceita também fixo com 10 dígitos e prefixo 55. */
export function formatPhone(phone: string | null | undefined): string {
  let d = onlyDigits(phone);
  if (d.length > 11 && d.startsWith('55')) d = d.slice(2);
  if (d.length === 11) return `(${d.slice(0, 2)}) ${d.slice(2, 7)}-${d.slice(7)}`;
  if (d.length === 10) return `(${d.slice(0, 2)}) ${d.slice(2, 6)}-${d.slice(6)}`;
  return phone ?? '';
}

/** Mesma regra do backend: dígitos verificadores e sem sequência repetida. */
export function isValidCpf(text: string): boolean {
  const d = onlyDigits(text);
  if (d.length !== 11 || /^(\d)\1{10}$/.test(d)) return false;
  const check = (length: number) => {
    let sum = 0;
    for (let i = 0; i < length; i++) sum += Number(d[i]) * (length + 1 - i);
    const rest = (sum * 10) % 11;
    return rest === 10 ? 0 : rest;
  };
  return check(9) === Number(d[9]) && check(10) === Number(d[10]);
}

/** Idade completa em anos na data de referência ("1980-05-17" → 46). */
export function ageOn(birthDate: string, today: Date = new Date()): number {
  const [y, m, d] = birthDate.split('-').map(Number);
  let age = today.getFullYear() - y;
  const month = today.getMonth() + 1;
  if (month < m || (month === m && today.getDate() < d)) age--;
  return age;
}

/** Iniciais para o avatar ("Maria Souza Lima" → "ML"). */
export function avatarInitials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}
