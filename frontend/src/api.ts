import type { Theme } from './color';

export type Me = {
  userId: string; email: string; role: 'OWNER' | 'STAFF'; clinicId: string; clinicName: string; fullName: string;
  mustChangePassword: boolean;
};
export type Member = {
  id: string; email: string; fullName: string | null; role: 'OWNER' | 'STAFF'; active: boolean; mustChangePassword: boolean;
  createdAt: string; me: boolean;
};
export type Access = { member: Member; temporaryPassword: string };

export type ClinicProfile = {
  id: string; slug: string; displayName: string; professionalName: string | null; crefito: string | null;
  document: string | null; whatsapp: string | null; address: string | null; about: string | null;
  colorPrimary: string; colorSecondary: string; colorAccent: string; publicPageEnabled: boolean;
  noShowConsumesSession: boolean; logoVersion: number | null;
  siteStyle: string; siteFonts: string; siteHeadline: string | null;
  siteEducation: string | null; siteHours: string | null; siteServiceArea: string | null;
  siteShowMap: boolean; siteShowTestimonials: boolean; siteTemplate: string; siteMood: string;
  passCardFee: boolean;
};

export type SitePhoto = { id: string; kind: 'COVER' | 'GALLERY'; caption: string | null; width: number; height: number; position: number };
export type Testimonial = { id: string; authorName: string; text: string; position: number; consentConfirmedAt: string };
export type Faq = { id: string; question: string; answer: string; position: number };

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
  countsAgainstPackage: boolean; price: number | null; paidAmount: number;
};

/** Pacote vendido a um paciente, com saldo. */
export type PatientPackage = {
  id: string; patientId: string; patientName: string; patientWhatsapp: string | null; packageId: string;
  serviceId: string; serviceName: string; name: string; sessionCount: number; price: number | null;
  purchasedOn: string; expiresOn: string | null; notes: string | null; active: boolean;
  used: number; scheduled: number; remaining: number; expired: boolean; lowBalance: boolean;
};

export type OpeningHours = { weekday: number; start: string; end: string };
export type BookingConfig = {
  enabled: boolean; minNoticeHours: number; maxDays: number; holdHours: number; stepMinutes: number; hours: OpeningHours[];
};
export type PatientMatch = { id: string; fullName: string; whatsapp: string | null; birthDate: string | null; active: boolean };
export type BookingRequest = {
  id: string; serviceId: string; serviceName: string; servicePrice: number | null; startsAt: string; endsAt: string;
  fullName: string; cpf: string; whatsapp: string; birthDate: string | null; notes: string | null;
  status: 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'CANCELED'; holdUntil: string; createdAt: string;
  declineReason: string | null; appointmentId: string | null; patient: PatientMatch | null; suggestions: PatientMatch[];
  code: string | null; payMethod: 'PIX' | 'CLINICA' | null; payAmount: number | null;
  receipt: { contentType: string; sizeBytes: number; uploadedAt: string } | null; paymentId: string | null;
};
export type PixKeyType = 'CPF' | 'CNPJ' | 'TELEFONE' | 'EMAIL' | 'ALEATORIA';
export type PixSettings = { keyType: PixKeyType | null; key: string | null; name: string | null; city: string | null };
/** Resposta do pedido pelo site; com Pix traz o código, o QR Code e o token do comprovante. */
export type BookingSent = {
  message: string; requestId: string | null; code: string | null; amount: number | null; pixCode: string | null;
  qrSvg: string | null; receiptToken: string | null;
};
export type InstallmentFee = { installments: number; fee: number };
export type CardMachine = {
  id: string; name: string; debitFee: number; creditFee: number; fixedFee: number; debitDays: number; creditDays: number;
  anticipated: boolean; active: boolean; installmentFees: InstallmentFee[];
};
export type PaymentMethod = 'DINHEIRO' | 'PIX' | 'DEBITO' | 'CREDITO';
export type Payment = {
  id: string; patientId: string; patientName: string; patientPackageId: string | null; packageName: string | null;
  appointmentId: string | null; appointmentDate: string | null; method: PaymentMethod; machineId: string | null;
  machineName: string | null; installments: number; amount: number; chargedAmount: number; feePercent: number;
  feeAmount: number; netAmount: number; paidOn: string; notes: string | null; canceled: boolean;
};
export type PaymentPreview = {
  feePercent: number; chargedAmount: number; feeAmount: number; netAmount: number;
  receivables: { installment: number; dueOn: string; amount: number }[];
};
export type Debtor = {
  patientId: string; fullName: string; whatsapp: string | null; charged: number; paid: number; balance: number;
  lastPaymentOn: string | null;
};
export type FinanceSummary = {
  month: string; received: number; fees: number; net: number; toReceive: number; open: number; payments: number;
  byMethod: { method: PaymentMethod; charged: number; fees: number; net: number }[];
};
export type Receivable = {
  id: string; paymentId: string; patientName: string; machineName: string | null; installment: number; installments: number;
  dueOn: string; amount: number; receivedOn: string | null;
};
export type Charge = { kind: 'PACOTE' | 'SESSAO'; id: string; description: string; date: string; value: number; paid: number };
export type PatientFinance = { charged: number; paid: number; balance: number; charges: Charge[]; payments: Payment[] };

export type PublicPhoto = { id: string; caption: string | null; width: number; height: number };
export type PublicPackage = { id: string; name: string; sessionCount: number; price: number | null; validityDays: number | null };
export type PublicService = {
  id: string; name: string; description: string | null; durationMinutes: number; price: number | null;
  onlineBooking: boolean; packages: PublicPackage[];
};
export type PublicClinic = {
  slug: string; displayName: string; professionalName: string | null; crefito: string | null;
  whatsapp: string | null; address: string | null; about: string | null; theme: Theme; logoVersion: number | null;
  site: {
    style: string; fonts: string; template: string; mood: string; headline: string | null; education: string | null; hours: string | null;
    serviceArea: string | null; showMap: boolean; cover: PublicPhoto | null; gallery: PublicPhoto[];
    testimonials: { authorName: string; text: string }[]; faqs: { question: string; answer: string }[];
  };
  services: PublicService[];
  booking: { enabled: boolean; hours: OpeningHours[]; pix: boolean };
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
  if (res.status === 204) return null as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : null) as T;
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

/** Envia uma foto do site (JPEG já comprimido no navegador). */
export async function uploadPhoto(jpeg: Blob, kind: 'COVER' | 'GALLERY', caption?: string): Promise<SitePhoto> {
  const form = new FormData();
  form.append('file', jpeg, 'foto.jpg');
  form.append('kind', kind);
  if (caption) form.append('caption', caption);
  const res = await fetch('/api/v1/clinic/photos', {
    method: 'POST', credentials: 'same-origin', headers: { 'X-XSRF-TOKEN': await csrf() }, body: form
  });
  return handle<SitePhoto>(res);
}

/** Comprovante do Pix enviado pelo paciente (sem login: vale o token devolvido no pedido). */
export async function uploadReceipt(slug: string, requestId: string, token: string, file: Blob, name: string): Promise<{ message: string }> {
  const form = new FormData();
  form.append('file', file, name);
  form.append('token', token);
  const res = await fetch(`/api/v1/public/clinics/${slug}/booking-requests/${requestId}/receipt`, {
    method: 'POST', credentials: 'same-origin', headers: { 'X-XSRF-TOKEN': await csrf() }, body: form
  });
  return handle<{ message: string }>(res);
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
