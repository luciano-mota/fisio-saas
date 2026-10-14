import type { IconId } from './siteStyle';

/** Ícones de traço (24×24, cor herdada) desenhados para o site; sem biblioteca externa. */
const PATHS: Record<IconId | 'whatsapp' | 'pin' | 'check' | 'calendar' | 'chat' | 'award' | 'arrow', string[]> = {
  pilates: ['M12 4.5a1.8 1.8 0 1 0 0 .01', 'M5 20h14', 'M7 16.5l3.5-3 1.5 2.5 2.5-4 3.5 4.5', 'M10.5 13.5L9 9h6l-1.2 2.6'],
  spine: ['M12 3v18', 'M9 5.5h6', 'M8.5 9.5h7', 'M8.5 13.5h7', 'M9 17.5h6'],
  hands: ['M7 12V7.5a1.5 1.5 0 0 1 3 0V11', 'M10 10.5V6a1.5 1.5 0 0 1 3 0v4.5', 'M13 10.5V7a1.5 1.5 0 0 1 3 0v6',
    'M16 11.5a1.5 1.5 0 0 1 3 0V15a6 6 0 0 1-6 6h-1.5A5.5 5.5 0 0 1 7 18.4L4.7 14.5a1.5 1.5 0 0 1 2.6-1.5L9 15.5'],
  home: ['M4 11l8-7 8 7', 'M6 9.5V20h12V9.5', 'M10 20v-5.5h4V20'],
  child: ['M12 7a2.5 2.5 0 1 0 0 .01', 'M8 21v-5l-2-3.5L9 10h6l3 2.5-2 3.5v5', 'M12 13v4'],
  lungs: ['M12 4v8', 'M12 12l-3 2', 'M12 12l3 2', 'M9 8.5C6 8 4 12 4 16.5c0 2 1 3 2.5 3S9 18 9 15V8.5',
    'M15 8.5c3-.5 5 3.5 5 8 0 2-1 3-2.5 3S15 18 15 15V8.5'],
  sport: ['M14.5 4.5a1.8 1.8 0 1 0 0 .01', 'M6 20l4-6 3 2 1.5-5', 'M9 9.5l3.5-1.5 2.5 3 3 1', 'M12.5 8l-2 6'],
  brain: ['M9.5 4.5A3 3 0 0 0 6.5 7.5 3 3 0 0 0 5 13a3 3 0 0 0 2 4.5A3 3 0 0 0 12 19V5.5A2.5 2.5 0 0 0 9.5 4.5',
    'M14.5 4.5a3 3 0 0 1 3 3A3 3 0 0 1 19 13a3 3 0 0 1-2 4.5A3 3 0 0 1 12 19'],
  heart: ['M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z', 'M7.5 12h2.5l1.5-2.5 2 5 1.5-2.5h2'],
  sparkle: ['M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z', 'M18.5 16.5l.8 2 .2.2 2 .8-2 .8-.2.2-.8 2-.8-2-.2-.2-2-.8 2-.8.2-.2z'],
  pelvic: ['M12 21c-4 0-7-3-7-7.5C5 8 8.5 4 12 4s7 4 7 9.5C19 18 16 21 12 21z', 'M12 8v6', 'M9 11h6'],
  activity: ['M3 12h4l2.5-6 4 12 2.5-6H21'],
  whatsapp: ['M4 20l1.3-4A8 8 0 1 1 8 18.8z', 'M9 9.5c0 3 2.5 5.5 5.5 5.5l1.2-1.4-1.9-.9-.9.8a4 4 0 0 1-2.4-2.4l.8-.9-.9-1.9z'],
  pin: ['M12 21s-6.5-6-6.5-11a6.5 6.5 0 0 1 13 0c0 5-6.5 11-6.5 11z', 'M12 10a2 2 0 1 0 0 .01'],
  check: ['M5 12.5l4.5 4.5L19 7.5'],
  calendar: ['M5 6h14v14H5z', 'M5 10h14', 'M9 4v4', 'M15 4v4'],
  chat: ['M5 5h14v10H9l-4 4z', 'M9 9.5h6', 'M9 12h4'],
  award: ['M12 14a5 5 0 1 0 0-10 5 5 0 0 0 0 10z', 'M9 13l-1.5 7L12 18l4.5 2L15 13'],
  arrow: ['M5 12h14', 'M13 6l6 6-6 6']
};

export type SiteIconName = keyof typeof PATHS;

export function SiteIcon({ name, size = 24 }: { name: SiteIconName; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7}
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {PATHS[name].map((d, i) => <path key={i} d={d} />)}
    </svg>
  );
}
