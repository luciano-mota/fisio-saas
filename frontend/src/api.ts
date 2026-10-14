import type { Theme } from './color';

export type Me = { userId: string; email: string; role: 'OWNER' | 'STAFF'; clinicId: string; clinicName: string };

export type ClinicProfile = {
  id: string; slug: string; displayName: string; professionalName: string | null; crefito: string | null;
  document: string | null; whatsapp: string | null; address: string | null; about: string | null;
  colorPrimary: string; colorSecondary: string; colorAccent: string; publicPageEnabled: boolean;
  noShowConsumesSession: boolean; logoVersion: number | null;
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

export type AppointmentStatus = 'SCHEDULED' | 'CONFIRMED' | 'DONE' | 'NO_SHOW' | 'CANCELED';
export type Appointment = {
  id: string; kind: 'SESSION' | 'BLOCK'; patientId: string | null; patientName: string | null;
  patientWhatsapp: string | null; serviceId: string | null; serviceName: string | null;
  startsAt: string; endsAt: string; status: AppointmentStatus; notes: string | null;
  originalStartsAt: string | null; rescheduleCount: number; seriesId: string | null;
  patientPackageId: string | null; packageName: string | null; packageRemaining: number | null;
  countsAgainstPackage: boolean;
};

/** Pacote vendido a um paciente, com saldo. */
export type PatientPackage = {
  id: string; patientId: string; patientName: string; patientWhatsapp: string | null; packageId: string;
  serviceId: string; serviceName: string; name: string; sessionCount: number; price: number | null;
  purchasedOn: string; expiresOn: string | null; notes: string | null; active: boolean;
  used: number; scheduled: number; remaining: number; expired: boolean; lowBalance: boolean;
};

export type PublicPackage = { id: string; name: string; sessionCount: number; price: number | null; validityDays: number | null };
export type PublicService = {
  id: string; name: string; description: string | null; durationMinutes: number; price: number | null;
  onlineBooking: boolean; packages: PublicPackage[];
};
export type PublicClinic = {
  slug: string; displayName: string; professionalName: string | null; crefito: string | null;
  whatsapp: string | null; address: string | null; about: string | null; theme: Theme; logoVersion: number | null;
  services: PublicService[];
};

async function csrf(): Promise<string> {
  const res = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store' });
  if (!res.ok) throw new Error('Não foi possível iniciar a sessão segura.');
  return ((await res.json()) as { token: string }).token;
}

/** Erro da API com o status HTTP e, no 409 de série recorrente, a lista de datas em conflito. */
export class ApiError extends Error {
  readonly status: number;
  readonly conflicts: string[];

  constructor(message: string, status: number, conflicts: string[] = []) {
    super(message);
    this.status = status;
    this.conflicts = conflicts;
  }
}

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = 'Falha na operação (' + res.status + ')';
    let conflicts: string[] = [];
    try {
      const body = JSON.parse(await res.text()) as { error?: string; conflicts?: string[] };
      if (body.error) message = body.error;
      if (body.conflicts) conflicts = body.conflicts;
    } catch { /* resposta sem JSON */ }
    if (res.status === 401) window.dispatchEvent(new Event('session-expired'));
    throw new ApiError(message, res.status, conflicts);
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

/** Envia a logo (PNG já preparado no navegador) com o token CSRF. */
export async function uploadLogo(png: Blob): Promise<void> {
  const form = new FormData();
  form.append('file', png, 'logo.png');
  const res = await fetch('/api/v1/clinic/logo', {
    method: 'POST', credentials: 'same-origin', headers: { 'X-XSRF-TOKEN': await csrf() }, body: form
  });
  await handle<void>(res);
}

export function logoUrl(logoVersion: number | null | undefined, slug?: string): string | null {
  if (!logoVersion) return null;
  return (slug ? `/api/v1/public/clinics/${slug}/logo` : '/api/v1/clinic/logo') + `?v=${logoVersion}`;
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
