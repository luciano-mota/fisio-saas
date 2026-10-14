import { useEffect, useState, type CSSProperties } from 'react';
import { api, logoUrl, uploadLogo, type ClinicProfile } from './api';
import { themeVariables, type Theme } from './color';
import { ACCEPT, pixelsFromUrl, prepareLogo } from './logoImage';
import { dominantColors, suggestPalettes } from './palette';

/**
 * Logo da clínica: envio (convertida para PNG no navegador), prévia e paletas sugeridas a partir das cores
 * dela. Escolher uma paleta só preenche as cores do formulário; vale ao salvar.
 */
export function LogoSection({ owner, profile, onLogoChange, onPick, current }: {
  owner: boolean; profile: ClinicProfile; current: Theme;
  onLogoChange: (profile: ClinicProfile) => void; onPick: (theme: Theme) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [palettes, setPalettes] = useState<Theme[]>([]);
  const src = logoUrl(profile.logoVersion);

  // Logo já salva: sugere cores dela ao abrir a tela.
  useEffect(() => {
    if (!src || !owner) return;
    pixelsFromUrl(src).then(px => setPalettes(suggestPalettes(dominantColors(px)))).catch(() => undefined);
  }, [src, owner]);

  async function choose(file: File | undefined) {
    if (!file) return;
    setBusy(true);
    setError('');
    try {
      const { png, pixels } = await prepareLogo(file);
      setPalettes(suggestPalettes(dominantColors(pixels)));
      await uploadLogo(png);
      onLogoChange(await api<ClinicProfile>('/api/v1/clinic'));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!confirm('Remover a logo? A página pública volta a mostrar as iniciais da clínica.')) return;
    await api('/api/v1/clinic/logo', 'DELETE');
    setPalettes([]);
    onLogoChange(await api<ClinicProfile>('/api/v1/clinic'));
  }

  const same = (t: Theme) => t.primary === current.primary && t.secondary === current.secondary && t.accent === current.accent;

  return (
    <div className="logo-section">
      <h3>Logo</h3>
      <div className="logo-row">
        <div className="logo-frame">
          {src ? <img src={src} alt="Logo da clínica" /> : <span className="muted small">Sem logo</span>}
        </div>
        {owner && (
          <div className="actions">
            <label className={busy ? 'button disabled' : 'button primary-link'}>
              {busy ? 'Enviando…' : src ? 'Trocar logo' : 'Enviar logo'}
              <input type="file" accept={ACCEPT} hidden disabled={busy}
                     onChange={e => { choose(e.target.files?.[0]); e.target.value = ''; }} />
            </label>
            {src && <button type="button" className="danger" onClick={remove}>Remover</button>}
          </div>
        )}
      </div>
      <p className="muted small">PNG, JPG, WebP ou SVG. De preferência com fundo transparente ou branco.</p>
      {error && <p className="error" role="alert">{error}</p>}

      {owner && palettes.length > 0 && (
        <>
          <p className="small"><strong>Cores sugeridas pela logo</strong> — toque para usar e depois em Salvar.</p>
          <div className="palettes">
            {palettes.map((t, i) => (
              <button type="button" key={i} className={same(t) ? 'palette chosen' : 'palette'}
                      aria-pressed={same(t)} aria-label={`Usar sugestão ${i + 1}`}
                      style={themeVariables(t) as CSSProperties} onClick={() => onPick(t)}>
                <span className="palette-hero">Sugestão {i + 1}</span>
                <span className="palette-dots">
                  <i style={{ background: t.primary }} /><i style={{ background: t.secondary }} /><i style={{ background: t.accent }} />
                </span>
                <span className="palette-cta">Agendar</span>
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
