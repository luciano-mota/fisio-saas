import { avatarInitials } from './format';
import { createContext, useCallback, useContext, useEffect, useId, useRef, useState, type ReactNode } from 'react';

/** Componentes do painel: ícones, gaveta, menu "⋯", confirmação, avisos e lista vazia. Sem biblioteca de UI. */

const ICONS = {
  home: ['M4 11l8-7 8 7', 'M6 9.5V20h12V9.5', 'M10 20v-5.5h4V20'],
  calendar: ['M5 6h14v14H5z', 'M5 10h14', 'M9 4v4', 'M15 4v4'],
  users: ['M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z', 'M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6', 'M16 4.5a3.5 3.5 0 0 1 0 6.5', 'M18 14.5c1.8.8 3 2.6 3 5.5'],
  tag: ['M3 12V4h8l9 9-8 8z', 'M7.5 8.5a1 1 0 1 0 0-.01'],
  globe: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M3 12h18', 'M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z'],
  building: ['M4 21V5l8-2v18', 'M12 8h8v13', 'M2 21h20', 'M7.5 8h1', 'M7.5 12h1', 'M7.5 16h1', 'M15.5 12h1', 'M15.5 16h1'],
  more: ['M5 12h.01', 'M12 12h.01', 'M19 12h.01'],
  plus: ['M12 5v14', 'M5 12h14'],
  search: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z', 'M20 20l-4-4'],
  left: ['M15 5l-7 7 7 7'],
  right: ['M9 5l7 7-7 7'],
  check: ['M5 12.5l4.5 4.5L19 7.5'],
  x: ['M6 6l12 12', 'M18 6L6 18'],
  clock: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 7v5l3 2'],
  whatsapp: ['M4 20l1.3-4A8 8 0 1 1 8 18.8z', 'M9 9.5c0 3 2.5 5.5 5.5 5.5l1.2-1.4-1.9-.9-.9.8a4 4 0 0 1-2.4-2.4l.8-.9-.9-1.9z'],
  edit: ['M4 20h4L19 9l-4-4L4 16z', 'M13.5 6.5l4 4'],
  trash: ['M4 7h16', 'M9 7V4h6v3', 'M6 7l1 13h10l1-13'],
  logout: ['M15 4h4v16h-4', 'M10 8l-4 4 4 4', 'M6 12h11'],
  external: ['M14 4h6v6', 'M20 4l-9 9', 'M18 14v6H4V6h6'],
  box: ['M3 7.5L12 3l9 4.5v9L12 21l-9-4.5z', 'M3 7.5L12 12l9-4.5', 'M12 12v9'],
  alert: ['M12 3l10 18H2z', 'M12 10v4', 'M12 17.5h.01'],
  bell: ['M6 16V11a6 6 0 1 1 12 0v5l2 2H4z', 'M10 20a2 2 0 0 0 4 0'],
  block: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M5.6 5.6l12.8 12.8'],
  repeat: ['M17 3l3 3-3 3', 'M4 12V9a3 3 0 0 1 3-3h13', 'M7 21l-3-3 3-3', 'M20 12v3a3 3 0 0 1-3 3H4'],
  sparkle: ['M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z'],
  money: ['M3 6.5h18v11H3z', 'M12 14.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z', 'M6.5 9.5v.01', 'M17.5 14.5v.01'],
  phone: ['M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a1 1 0 0 1-1 1A16 16 0 0 1 4 5a1 1 0 0 1 1-1z']
} satisfies Record<string, string[]>;

export type IconName = keyof typeof ICONS;

export function Icon({ name, size = 20 }: { name: IconName; size?: number }) {
  return (
    <svg className="icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {ICONS[name].map((d, i) => <path key={i} d={d} />)}
    </svg>
  );
}

// ---------- Avisos ("Serviço salvo") e confirmação ----------

type ConfirmOptions = { title?: string; confirmLabel?: string; danger?: boolean };
type Pending = { message: string; options: ConfirmOptions; resolve: (ok: boolean) => void };
type Ui = { toast: (message: string) => void; confirm: (message: string, options?: ConfirmOptions) => Promise<boolean> };

const UiContext = createContext<Ui>({
  toast: () => undefined,
  confirm: async message => window.confirm(message)
});

export const useUi = () => useContext(UiContext);

export function UiProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<{ id: number; message: string }[]>([]);
  const [pending, setPending] = useState<Pending | null>(null);
  const nextId = useRef(0);

  const toast = useCallback((message: string) => {
    const id = ++nextId.current;
    setToasts(list => [...list, { id, message }]);
    setTimeout(() => setToasts(list => list.filter(t => t.id !== id)), 3500);
  }, []);
  const confirm = useCallback((message: string, options: ConfirmOptions = {}) =>
    new Promise<boolean>(resolve => setPending({ message, options, resolve })), []);
  const close = (ok: boolean) => { pending?.resolve(ok); setPending(null); };

  return (
    <UiContext.Provider value={{ toast, confirm }}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map(t => <div key={t.id} className="toast"><Icon name="check" size={18} />{t.message}</div>)}
      </div>
      {pending && (
        <Dialog title={pending.options.title ?? 'Tem certeza?'} onClose={() => close(false)} className="confirm">
          <p>{pending.message}</p>
          <div className="actions end">
            <button type="button" onClick={() => close(false)}>Voltar</button>
            <button type="button" className={pending.options.danger ? 'primary danger-fill' : 'primary'} autoFocus
                    onClick={() => close(true)}>{pending.options.confirmLabel ?? 'Confirmar'}</button>
          </div>
        </Dialog>
      )}
    </UiContext.Provider>
  );
}

// ---------- Gaveta (computador) / folha que sobe (celular) ----------

function Dialog({ title, onClose, children, className = '' }: {
  title: string; onClose: () => void; children: ReactNode; className?: string;
}) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    document.body.classList.add('no-scroll');
    // Foco no primeiro campo (ou no botão principal) ao abrir.
    const first = panel.current?.querySelector<HTMLElement>('[autofocus], input:not([type=hidden]), select, textarea, button.primary');
    first?.focus();
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.classList.remove('no-scroll');
      previous?.focus?.();
    };
  }, []);
  return (
    <div className={`overlay ${className}`} onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="sheet" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={panel}>
        <header className="sheet-head">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="icon-button" aria-label="Fechar" onClick={onClose}><Icon name="x" /></button>
        </header>
        <div className="sheet-body">{children}</div>
      </div>
    </div>
  );
}

export function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return <Dialog title={title} onClose={onClose}>{children}</Dialog>;
}

// ---------- Menu "⋯" ----------

export type MenuItem = { label: string; icon?: IconName; onClick?: () => void; href?: string; danger?: boolean };
/** Itens condicionais podem vir como false/''/null (ex.: {@code cond && {...}}) e são ignorados. */
export type MenuEntry = MenuItem | false | '' | null | undefined;

export function Menu({ items, label = 'Mais ações' }: { items: MenuEntry[]; label?: string }) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const shown = items.filter((i): i is MenuItem => !!i);
  useEffect(() => {
    if (!open) return;
    const outside = (e: MouseEvent) => { if (!root.current?.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', outside);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', outside); document.removeEventListener('keydown', onKey); };
  }, [open]);
  if (shown.length === 0) return null;
  return (
    <div className="menu" ref={root}>
      <button type="button" className="icon-button" aria-label={label} aria-haspopup="menu" aria-expanded={open}
              onClick={() => setOpen(o => !o)}><Icon name="more" /></button>
      {open && (
        <div className="menu-list" role="menu">
          {shown.map(item => item.href ? (
            <a key={item.label} role="menuitem" href={item.href} target="_blank" rel="noreferrer"
               className={item.danger ? 'danger' : ''} onClick={() => setOpen(false)}>
              {item.icon && <Icon name={item.icon} size={18} />}{item.label}</a>
          ) : (
            <button key={item.label} type="button" role="menuitem" className={item.danger ? 'danger' : ''}
                    onClick={() => { setOpen(false); item.onClick?.(); }}>
              {item.icon && <Icon name={item.icon} size={18} />}{item.label}</button>
          ))}
        </div>
      )}
    </div>
  );
}

// ---------- Cabeçalho da tela e lista vazia ----------

export function PageHeader({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children?: ReactNode }) {
  return (
    <header className="page-head">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
      </div>
      {children && <div className="page-actions">{children}</div>}
    </header>
  );
}

export function EmptyState({ icon, title, text, children }: { icon: IconName; title: string; text?: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <span className="empty-icon"><Icon name={icon} size={28} /></span>
      <strong>{title}</strong>
      {text && <p className="muted small">{text}</p>}
      {children}
    </div>
  );
}

export function Avatar({ name }: { name: string }) {
  return <span className="avatar" aria-hidden="true">{avatarInitials(name)}</span>;
}
