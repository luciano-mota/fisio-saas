import { useEffect, useState } from 'react';
import { api } from './api';
import { brl, dateBr, longDate, methodLabel } from './finance';
import { formatCpf, formatDocument } from './format';
import { navigate, usePath } from './router';
import { Icon } from './ui';

type Receipt = {
  number: string; issuedOn: string; from: string; to: string;
  provider: { name: string; clinicName: string; crefito: string | null; document: string | null; address: string | null };
  payer: { id: string; name: string; cpf: string | null };
  items: { paidOn: string; description: string; method: string; amount: number }[];
  total: number; totalInWords: string; missing: string[];
};

/**
 * Recibo para o Imposto de Renda, pronto para imprimir ou salvar em PDF pelo navegador (Imprimir → Salvar como PDF).
 * Rotas: /painel/recibo/pagamento/{id} e /painel/recibo/paciente/{id}?de=AAAA-MM-DD&ate=AAAA-MM-DD.
 */
export function ReceiptPage() {
  const path = usePath();
  const [, , , kind, id] = path.split('/');
  const params = new URLSearchParams(window.location.search);
  const [receipt, setReceipt] = useState<Receipt | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    const url = kind === 'paciente'
      ? `/api/v1/patients/${id}/receipt?from=${params.get('de')}&to=${params.get('ate')}`
      : `/api/v1/payments/${id}/receipt`;
    api<Receipt>(url).then(r => { setReceipt(r); document.title = `Recibo ${r.payer.name}`; })
      .catch(e => setError((e as Error).message));
  }, [kind, id]);

  const back = () => (window.history.length > 1 ? window.history.back() : navigate('/painel/financeiro/pagamentos'));
  const toolbar = (
    <div className="receipt-toolbar">
      <button className="link-button back" onClick={back}><Icon name="left" size={18} /> Voltar</button>
      {receipt && <button className="primary" onClick={() => window.print()}>Imprimir ou salvar PDF</button>}
    </div>
  );
  if (!receipt) return <div className="receipt-wrap">{toolbar}{error && <p className="error-box">{error}</p>}</div>;

  const { provider: pro, payer } = receipt;
  const period = receipt.from === receipt.to ? dateBr(receipt.from) : `${dateBr(receipt.from)} a ${dateBr(receipt.to)}`;
  return (
    <div className="receipt-wrap">
      {toolbar}
      {receipt.missing.length > 0 && (
        <p className="notice small receipt-toolbar">
          Para valer no Imposto de Renda, complete: <strong>{receipt.missing.join(', ')}</strong>.{' '}
          {receipt.missing.includes('CPF do paciente')
            ? <a href={`/painel/pacientes/${payer.id}`}>Editar paciente</a>
            : <a href="/painel/clinica">Editar dados da clínica</a>}
        </p>
      )}
      <article className="receipt-paper">
        <header className="receipt-head">
          <div>
            <strong className="receipt-pro">{pro.name}</strong>
            {pro.name !== pro.clinicName && <span>{pro.clinicName}</span>}
            <span>Fisioterapeuta{pro.crefito && ` · CREFITO ${pro.crefito}`}</span>
            {pro.document && <span>CPF/CNPJ {formatDocument(pro.document)}</span>}
            {pro.address && <span>{pro.address}</span>}
          </div>
          <div className="receipt-title">
            <h1>Recibo</h1>
            <span>Nº {receipt.number}</span>
            <strong className="receipt-value">{brl(receipt.total)}</strong>
          </div>
        </header>

        <p className="receipt-text">
          Recebi de <strong>{payer.name}</strong>{payer.cpf && <>, CPF <strong>{formatCpf(payer.cpf)}</strong></>}, a importância
          de <strong>{brl(receipt.total)} ({receipt.totalInWords})</strong>, referente a serviços de fisioterapia prestados
          {receipt.items.length > 1 ? ` no período de ${period}` : ''}, conforme discriminado abaixo.
        </p>

        <table className="receipt-table">
          <thead><tr><th>Data</th><th>Descrição</th><th>Forma</th><th className="num">Valor</th></tr></thead>
          <tbody>
            {receipt.items.map((item, i) => (
              <tr key={i}>
                <td>{dateBr(item.paidOn)}</td><td>{item.description}</td><td>{methodLabel(item.method)}</td>
                <td className="num">{brl(item.amount)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot><tr><td colSpan={3}>Total</td><td className="num">{brl(receipt.total)}</td></tr></tfoot>
        </table>

        <p className="receipt-date">{longDate(receipt.issuedOn)}.</p>
        <div className="receipt-sign">
          <span className="line" />
          <strong>{pro.name}</strong>
          <span>{[pro.crefito && `CREFITO ${pro.crefito}`, pro.document && `CPF/CNPJ ${formatDocument(pro.document)}`].filter(Boolean).join(' · ')}</span>
        </div>
        <p className="receipt-foot">Recibo para comprovação de despesa com fisioterapia (dedução no Imposto de Renda — despesas médicas).</p>
      </article>
    </div>
  );
}
