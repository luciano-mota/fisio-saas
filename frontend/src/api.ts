import type { Theme } from './color';

export type Me = { userId: string; email: string; role: 'OWNER' | 'STAFF'; clinicId: string; clinicName: string };

export type ClinicProfile = {
  id: string; slug: string; displayName: string; professionalName: string | null; crefito: string | null;
  document: string | null; whatsapp: string | null; address: string | null; about: string | null;
  colorPrimary: string; colorSecondary: string; colorAccent: string; publicPageEnabled: boolean;
};

export type Service = {
  id: string; name: string; description: string | null; durationMinutes: number; price: number | null;
  showPrice: boolean; onlineBookingEnabled: boolean; active: boolean; displayOrder: number;
};

export type SessionPackage = {
  id: string; serviceId: string; serviceName: string; name: string; sessionCount: number;
  price: number | null; showPrice: boolean; validityDays: number | null; active: boolean;
};

export type Patient = {
  id: string; fullName: string; cpf: string | null; whatsapp: string | null; birthDate: string | null;
  adminNotes: string | null; active: boolean;
};

export type PublicPackage = { id: string; name: string; sessionCount: number; price: number | null; validityDays: number | null };
export type PublicService = {
  id: string; name: string; description: string | null; durationMinutes: number; price: number | null;
  onlineBooking: boolean; packages: PublicPackage[];
};
export type PublicClinic = {
  slug: string; displayName: string; professionalName: string | null; crefito: string | null;
  whatsapp: string | null; address: string | null; about: string | null; theme: Theme; services: PublicService[];
};

async function csrf(): Promise<string> {
  const res = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store' });
  if (!res.ok) throw new Error('Não foi possível iniciar a sessão segura.');
  return ((await res.json()) as { token: string }).token;
}

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = 'Falha na operação (' + res.status + ')';
    try {
      const body = JSON.parse(await res.text()) as { error?: string };
      if (body.error) message = body.error;
    } catch { /* resposta sem JSON */ }
    if (res.status === 401) window.dispatchEvent(new Event('session-expired'));
    throw new Error(message);
  }
  return (res.status === 204 ? null : await res.json()) as T;
}

export async function api<T>(url: string, method = 'GET', payload?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (method !== 'GET') {
    headers['X-XSRF-TOKEN'] = await csrf();
    headers['Content-Type'] = 'application/json';
  }
  const res = await fetch(url, {
    method, headers, credentials: 'same-origin',
    body: payload === undefined ? undefined : JSON.stringify(payload)
  });
  return handle<T>(res);
}

export async function signIn(email: string, password: string): Promise<void> {
  const res = await fetch('/api/v1/auth/login', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': await csrf() },
    body: new URLSearchParams({ email, password })
  });
  if (res.status === 401) throw new Error('E-mail ou senha incorretos.');
  await handle<unknown>(res);
}

export function signOut(): Promise<void> {
  return api<void>('/api/v1/auth/logout', 'POST');
}

export function money(value: number | null | undefined): string {
  if (value === null || value === undefined) return 'Valor sob consulta';
  return value.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

/** Link wa.me com mensagem pronta (custo zero, sem API do WhatsApp). */
export function whatsappLink(phoneDigits: string, message: string): string {
  const digits = phoneDigits.replace(/\D/g, '');
  const full = digits.startsWith('55') ? digits : '55' + digits;
  return `https://wa.me/${full}?text=${encodeURIComponent(message)}`;
}
