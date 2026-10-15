import { firstName } from './messages.ts';

/** Regras de exibição do financeiro (o cálculo de taxa e líquido é sempre do servidor). */

export type Method = 'DINHEIRO' | 'PIX' | 'DEBITO' | 'CREDITO';

export const METHODS: { id: Method; label: string }[] = [
  { id: 'PIX', label: 'Pix' },
  { id: 'DINHEIRO', label: 'Dinheiro' },
  { id: 'DEBITO', label: 'Débito' },
  { id: 'CREDITO', label: 'Crédito' }
];

export const isCard = (method: Method) => method === 'DEBITO' || method === 'CREDITO';

/** "R$ 1.234,56" (com espaço comum, para não quebrar testes e cópias). */
export function brl(value: number): string {
  return value.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' }).replace(/\s/g, ' ');
}

/**
 * Valor digitado → número. Aceita "1.234,56", "90,5", "90" e "90.50". Vazio → null; inválido → NaN.
 * Com vírgula, os pontos são de milhar; sem vírgula, um ponto seguido de 1 ou 2 dígitos é decimal.
 */
export function parseMoney(text: string): number | null {
  const t = text.trim().replace(/^R\$\s*/, '');
  if (!t) return null;
  let normalized: string;
  if (t.includes(',')) normalized = t.replace(/\./g, '').replace(',', '.');
  else if (/^\d+\.\d{1,2}$/.test(t)) normalized = t;
  else normalized = t.replace(/\./g, '');
  if (!/^\d+(\.\d{1,2})?$/.test(normalized)) return NaN;
  return Math.round(Number(normalized) * 100) / 100;
}

/** 90 → "90,00" (para preencher campo de valor). */
export function moneyInput(value: number | null | undefined): string {
  return value === null || value === undefined ? '' : value.toFixed(2).replace('.', ',');
}

/** "Crédito 3x · Stone", "Pix", "Débito · Ton". */
export function methodLabel(method: string, installments = 1, machineName?: string | null): string {
  const base = METHODS.find(m => m.id === method)?.label ?? method;
  const parts = [method === 'CREDITO' && installments > 1 ? `${base} ${installments}x` : base];
  if (machineName) parts.push(machineName);
  return parts.join(' · ');
}

const MONTHS = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro',
  'novembro', 'dezembro'];

/** "2026-10" → "outubro de 2026". */
export function monthLabel(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return `${MONTHS[m - 1]} de ${y}`;
}

/** "2026-01", -1 → "2025-12". */
export function addMonths(month: string, delta: number): string {
  const [y, m] = month.split('-').map(Number);
  const total = y * 12 + (m - 1) + delta;
  return `${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, '0')}`;
}

/** Primeiro e último dia do mês ("2026-02" → ["2026-02-01", "2026-02-28"]). */
export function monthRange(month: string): [string, string] {
  const [y, m] = month.split('-').map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return [`${month}-01`, `${month}-${String(last).padStart(2, '0')}`];
}

/** "2026-10-15" → "15 de outubro de 2026". */
export function longDate(iso: string): string {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number);
  return `${d} de ${MONTHS[m - 1]} de ${y}`;
}

/** "2026-10-15" → "15/10/2026". */
export function dateBr(iso: string): string {
  const [y, m, d] = iso.slice(0, 10).split('-');
  return `${d}/${m}/${y}`;
}

/** Lembrete gentil de valor em aberto, para o WhatsApp. Com o Pix da clínica, já vai o "copia e cola" com o valor. */
export function chargeMessage(patientName: string, balance: number, clinicName?: string, pixCode?: string): string {
  const from = clinicName ? ` Aqui é da ${clinicName}.` : '';
  const text = `Olá, ${firstName(patientName)}! Tudo bem?${from} Passando para lembrar que ficou em aberto ${brl(balance)} ` +
    'dos seus atendimentos. Pode ser por Pix, dinheiro ou cartão. Se já pagou, me avise para eu dar baixa. Obrigado!';
  return pixCode ? `${text}\n\nPix copia e cola (é só colar no app do banco):\n${pixCode}` : text;
}

/** Situação da sessão avulsa: "a pagar", "pago" ou "pago em parte" (sem preço → null). */
export function sessionPaymentState(price: number | null, paid: number): 'a pagar' | 'pago' | 'pago em parte' | null {
  if (price === null || price === 0) return null;
  if (paid >= price) return 'pago';
  return paid > 0 ? 'pago em parte' : 'a pagar';
}
