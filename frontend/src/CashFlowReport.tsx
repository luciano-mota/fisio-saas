import { useEffect, useState, type FormEvent } from 'react';
import { api, logoUrl, type CashFlow } from './api';
import { today } from './dates';
import { addMonths, brl, dateBr, methodLabel, monthRange } from './finance';
import { formatPhone } from './format';
import { navigate } from './router';
import { Icon, Sheet } from './ui';

const SUB_METHODS: Record<string, string> = { CARTAO: 'Cartão', BOLETO: 'Boleto', OUTRO: 'Outro' };
const label = (m: string) => SUB_METHODS[m] ?? methodLabel(m as never);
const entries = (n: number) => `${n} ${n === 1 ? 'lançamento' : 'lançamentos'}`;

/**
 * Relatório de fluxo de caixa pronto para imprimir ou salvar em PDF (Imprimir → Salvar como PDF), com a logo da clínica
 * ou da PhysioEvo. {@code source}: endpoint do relatório; {@code back}: para onde voltar.
 */
export function CashFlowReport({ source, back, platform = false }: { source: string; back: string; platform?: boolean }) {
  const params = new URLSearchParams(window.location.search);
  const from = params.get('de') ?? '';
  const to = params.get('ate') ?? '';
  const [data, setData] = useState<CashFlow | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    api<CashFlow>(`${source}?from=${from}&to=${to}`).then(d => { setData(d); document.title = `Fluxo de caixa ${dateBr(d.from)} a ${dateBr(d.to)}`; })
      .catch(e => setError((e as Error).message));
  }, [source, from, to]);

  const toolbar = (
    <div className="receipt-toolbar">
      <button className="link-button back" onClick={() => (window.history.length > 1 ? window.history.back() : navigate(back))}>
        <Icon name="left" size={18} /> Voltar</button>
      {data && <button className="primary" onClick={() => window.print()}>Imprimir ou salvar PDF</button>}
    </div>
  );
  if (!data) return <div className="receipt-wrap">{toolbar}{error && <p className="error-box">{error}</p>}</div>;

  const logo = platform ? '/brand/physioevo-logo.svg' : logoUrl(data.clinic.logoVersion);
  let balance = 0;
  const c = data.clinic;
  return (
    <div className="receipt-wrap report">
      {toolbar}
      <article className="receipt-paper">
        <header className="report-head">
          <div className="report-brand">
            {logo && <img src={logo} alt="" className="report-logo" />}
            <div>
              {!platform && <strong className="receipt-pro">{c.name}</strong>}
              {c.professionalName && <span>{c.professionalName}{c.crefito ? ` · CREFITO ${c.crefito}` : ''}</span>}
              {c.address && <span>{c.address}</span>}
              {c.whatsapp && <span>WhatsApp {formatPhone(c.whatsapp)}</span>}
            </div>
          </div>
          <div className="receipt-title">
            <h1>Fluxo de caixa</h1>
            <span>{dateBr(data.from)} a {dateBr(data.to)}</span>
          </div>
        </header>

        <section className="report-totals">
          <div><span>Entradas (bruto)</span><strong>{brl(data.totals.gross)}</strong><small>{entries(data.totals.entries)}</small></div>
          {!platform && <div><span>Taxas de cartão</span><strong>− {brl(data.totals.fees)}</strong></div>}
          {!platform && <div><span>Saídas</span><strong>− {brl(data.totals.expenses)}</strong><small>{entries(data.totals.exits)}</small></div>}
          <div className="main"><span>Saldo líquido</span><strong>{brl(data.totals.net)}</strong></div>
        </section>

        <table className="receipt-table report-table">
          <thead><tr><th>Data</th><th>Descrição</th><th>{platform ? 'Clínica' : 'Quem'}</th><th>Forma</th>
            <th className="num">Entrada</th>{!platform && <th className="num">Taxa</th>}{!platform && <th className="num">Saída</th>}<th className="num">Saldo</th></tr></thead>
          <tbody>
            {data.entries.length === 0 && <tr><td colSpan={8} className="muted">Nenhum lançamento no período.</td></tr>}
            {data.entries.map((e, i) => {
              balance += e.kind === 'IN' ? e.net : -e.net;
              return (
                <tr key={i} className={e.kind === 'OUT' ? 'out' : ''}>
                  <td>{dateBr(e.date)}</td><td>{e.description}</td><td>{e.party}</td><td>{label(e.method)}</td>
                  <td className="num">{e.kind === 'IN' ? brl(e.gross) : ''}</td>
                  {!platform && <td className="num">{e.kind === 'IN' && e.fee > 0 ? `− ${brl(e.fee)}` : ''}</td>}
                  {!platform && <td className="num">{e.kind === 'OUT' ? `− ${brl(e.net)}` : ''}</td>}
                  <td className="num">{brl(balance)}</td>
                </tr>
              );
            })}
          </tbody>
          <tfoot><tr><td colSpan={4}>Totais</td><td className="num">{brl(data.totals.gross)}</td>
            {!platform && <td className="num">− {brl(data.totals.fees)}</td>}{!platform && <td className="num">− {brl(data.totals.expenses)}</td>}
            <td className="num">{brl(data.totals.net)}</td></tr></tfoot>
        </table>

        {data.byMethod.length > 0 && (
          <section className="report-methods">
            <h2>Entradas por forma de pagamento</h2>
            <ul>{data.byMethod.map(m => (
              <li key={m.method}><span>{label(m.method)} ({m.count})</span><strong>{brl(m.gross)}</strong>
                {m.net !== m.gross && <small>líquido {brl(m.net)}</small>}</li>))}</ul>
          </section>
        )}
        <p className="receipt-foot">Gerado em {new Date().toLocaleString('pt-BR')} · {platform ? 'PhysioEvo' : `${c.name} · PhysioEvo`}</p>
      </article>
    </div>
  );
}

/** Escolha do período do relatório (este mês, mês anterior ou datas livres) e abre a página para imprimir. */
export function CashFlowSheet({ path, onClose, todayIso = today() }: { path: string; onClose: () => void; todayIso?: string }) {
  const month = todayIso.slice(0, 7);
  const [mode, setMode] = useState<'este' | 'anterior' | 'livre'>('este');
  const [from, setFrom] = useState(`${month}-01`);
  const [to, setTo] = useState(todayIso);
  function open(event: FormEvent) {
    event.preventDefault();
    const [de, ate] = mode === 'este' ? [`${month}-01`, todayIso] : mode === 'anterior' ? monthRange(addMonths(month, -1)) : [from, to];
    navigate(`${path}?de=${de}&ate=${ate}`);
  }
  return (
    <Sheet title="Relatório de fluxo de caixa" onClose={onClose}>
      <form className="form" onSubmit={open}>
        <div className="chips" role="radiogroup" aria-label="Período">
          {([['este', 'Este mês'], ['anterior', 'Mês anterior'], ['livre', 'Escolher datas']] as const).map(([id, text]) => (
            <button key={id} type="button" role="radio" aria-checked={mode === id} className={mode === id ? 'chip on' : 'chip'}
                    onClick={() => setMode(id)}>{text}</button>))}
        </div>
        {mode === 'livre' && (
          <div className="row">
            <label>De<input type="date" required value={from} onChange={e => setFrom(e.target.value)} /></label>
            <label>Até<input type="date" required value={to} min={from} onChange={e => setTo(e.target.value)} /></label>
          </div>
        )}
        <p className="muted small">Abre o relatório com a sua logo, pronto para imprimir ou salvar em PDF.</p>
        <div className="sheet-actions"><button type="button" onClick={onClose}>Cancelar</button>
          <button className="primary">Gerar relatório</button></div>
      </form>
    </Sheet>
  );
}
