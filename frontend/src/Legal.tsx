import { useEffect, useState } from 'react';
import { TextPage } from './Landing';
import { PRODUCT_NAME, TERMS_VERSION } from './product';

const VERSION = TERMS_VERSION.split('-').reverse().join('/');

function useContact(): string | null {
  const [email, setEmail] = useState<string | null>(null);
  useEffect(() => {
    fetch('/api/v1/public/platform').then(r => (r.ok ? r.json() : null))
      .then((p: { contactEmail: string | null } | null) => setEmail(p?.contactEmail ?? null)).catch(() => undefined);
  }, []);
  return email;
}

function Contact({ email }: { email: string | null }) {
  return email ? <a href={`mailto:${email}`}>{email}</a> : <>o canal de suporte informado no painel</>;
}

/** Termos de uso da plataforma. */
export function TermsPage() {
  const contact = useContact();
  return (
    <TextPage title="Termos de uso">
      <p className="legal-version">Versão de {VERSION}</p>
      <p>Estes termos regem o uso do {PRODUCT_NAME} (“plataforma”), sistema on-line para fisioterapeutas e clínicas
        (“clínica”) organizarem agenda, pacientes, pacotes de sessões, pagamentos e o site de agendamento. Ao criar a conta,
        a clínica declara ter lido e aceitado estes termos e a <a href="/privacidade">Política de privacidade</a>.</p>

      <h2>1. Conta e acesso</h2>
      <ul>
        <li>A conta é criada por um profissional responsável, que responde pela veracidade dos dados informados.</li>
        <li>A responsável pode dar acesso a outras pessoas (responsáveis ou colaboradores) e deve remover o acesso de quem
          deixar a clínica. Cada pessoa usa o próprio login; senhas não devem ser compartilhadas.</li>
        <li>Cada clínica enxerga somente os próprios dados.</li>
      </ul>

      <h2>2. Uso permitido</h2>
      <ul>
        <li>A plataforma é para gestão administrativa: agenda, cadastro de pacientes, pacotes, pagamentos, recibos e site.</li>
        <li><strong>Não registre dados clínicos</strong> (diagnóstico, evolução, prontuário) nos campos de observação: a plataforma
          ainda não foi desenhada para esse tipo de dado.</li>
        <li>É proibido usar a plataforma para enviar mensagens em massa não solicitadas, publicar conteúdo ilegal ou tentar
          acessar dados de outras clínicas.</li>
      </ul>

      <h2>3. Dados dos pacientes</h2>
      <p>A clínica é a <strong>controladora</strong> dos dados dos seus pacientes (Lei 13.709/2018 — LGPD) e decide o que
        cadastra; a plataforma atua como <strong>operadora</strong>, tratando esses dados apenas para prestar o serviço. Cabe à
        clínica informar os pacientes e atender aos pedidos deles (acesso, correção, exclusão). O agendamento pelo site já
        pede o consentimento do paciente para uso de nome, CPF e WhatsApp.</p>

      <h2>4. Pagamentos de pacientes</h2>
      <p>O Pix gerado pela plataforma vai direto para a chave da clínica; a plataforma não recebe, não guarda e não repassa
        dinheiro. A conferência de comprovantes e de valores no banco é responsabilidade da clínica. Taxas de maquininha e
        prazos são os cadastrados pela clínica e servem como estimativa.</p>

      <h2>5. Preço e período de testes</h2>
      <p>Durante o período de testes o uso é gratuito. Antes de qualquer cobrança, a clínica será avisada com antecedência
        sobre preço e forma de pagamento, e poderá encerrar a conta sem custo.</p>

      <h2>6. Disponibilidade e responsabilidade</h2>
      <p>Trabalhamos para manter a plataforma no ar e com cópias de segurança, mas não garantimos funcionamento sem
        interrupções. A plataforma não se responsabiliza por decisões tomadas com base em estimativas (como taxas e
        recebíveis) nem por informações cadastradas incorretamente.</p>

      <h2>7. Encerramento</h2>
      <p>A clínica pode pedir o encerramento da conta a qualquer momento por <Contact email={contact} />. Antes do
        encerramento, a clínica pode pedir uma cópia dos seus dados. Contas usadas em desacordo com estes termos podem ser
        suspensas.</p>

      <h2>8. Alterações</h2>
      <p>Estes termos podem mudar; mudanças relevantes serão avisadas no painel, e a versão aceita fica registrada.</p>

      <h2>Contato</h2>
      <p>Dúvidas: <Contact email={contact} />.</p>
    </TextPage>
  );
}

/** Política de privacidade (LGPD). */
export function PrivacyPage() {
  const contact = useContact();
  return (
    <TextPage title="Política de privacidade">
      <p className="legal-version">Versão de {VERSION}</p>
      <p>Esta política explica quais dados o {PRODUCT_NAME} trata, para quê e quais são os seus direitos, conforme a Lei Geral
        de Proteção de Dados (Lei 13.709/2018 — LGPD).</p>

      <h2>Quem é responsável pelos dados</h2>
      <ul>
        <li><strong>Dados da conta e da clínica</strong> (nome, e-mail, dados profissionais): a plataforma é a controladora.</li>
        <li><strong>Dados dos pacientes</strong> (cadastrados pela clínica ou enviados no agendamento pelo site): a clínica é
          a controladora e a plataforma é a operadora, tratando os dados apenas para prestar o serviço à clínica.</li>
      </ul>

      <h2>Quais dados</h2>
      <ul>
        <li>Conta: nome, e-mail e senha (guardada só como código irreversível, nunca em texto).</li>
        <li>Clínica: nome, profissional, CREFITO, CPF/CNPJ, WhatsApp, endereço, logo, fotos e textos do site, chave Pix.</li>
        <li>Pacientes: nome, CPF, WhatsApp, data de nascimento e observações administrativas; agenda, pacotes e pagamentos;
          comprovantes de Pix enviados no agendamento.</li>
        <li>Dados técnicos: endereço IP e registros de acesso, usados para segurança (por exemplo, limitar tentativas).</li>
      </ul>
      <p>Não tratamos dados clínicos (prontuário, diagnóstico, evolução).</p>

      <h2>Para quê</h2>
      <ul>
        <li>Prestar o serviço: agenda, pacotes, pagamentos, recibos e site de agendamento (execução de contrato).</li>
        <li>Identificar o paciente pelo CPF e evitar cadastros duplicados; emitir recibos (consentimento no agendamento e
          cumprimento de obrigação legal da clínica).</li>
        <li>Segurança e prevenção de abuso (legítimo interesse).</li>
      </ul>

      <h2>Com quem compartilhamos</h2>
      <p>Não vendemos dados. Os dados ficam em provedores de hospedagem contratados para manter a plataforma no ar. Mensagens
        de WhatsApp são abertas no aparelho de quem envia (link “wa.me”); a plataforma não envia mensagens nem usa
        inteligência artificial com dados de pacientes.</p>

      <h2>Cookies</h2>
      <p>Usamos apenas os cookies necessários para manter o login e proteger os formulários (sessão e CSRF). Não usamos
        cookies de publicidade nem de rastreamento.</p>

      <h2>Por quanto tempo</h2>
      <p>Enquanto a conta estiver ativa. Itens desativados ficam guardados para preservar o histórico (por exemplo, pagamentos
        e recibos). Fotos removidas do site são apagadas de verdade. Ao encerrar a conta, os dados são excluídos em até 90
        dias, salvo o que a lei obrigar a guardar.</p>

      <h2>Segurança</h2>
      <p>Conexão com HTTPS, senhas protegidas por algoritmo de hash, isolamento entre clínicas em todas as consultas, acesso
        aos comprovantes de Pix só pela responsável da clínica e cópias de segurança.</p>

      <h2>Seus direitos</h2>
      <p>Você pode pedir confirmação, acesso, correção, portabilidade, anonimização ou exclusão dos seus dados, e informações
        sobre compartilhamento (art. 18 da LGPD). Pacientes devem procurar primeiro a clínica que os atende; a plataforma
        ajuda a clínica a atender o pedido.</p>

      <h2>Contato do encarregado</h2>
      <p><Contact email={contact} />.</p>
    </TextPage>
  );
}
