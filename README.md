# PhysioEvo

Sistema para fisioterapeutas autônomos: a própria clínica cadastra serviços e pacotes de sessões
(com ou sem preço) e, de bônus pela assinatura, ganha um site profissional no estilo dela, com os serviços, fotos, formação, depoimentos e
perguntas frequentes. Preço e pagamento aparecem só na hora de agendar. Planejamento completo no Trello: **Fisio SaaS | Descoberta e MVP**.

## O que já funciona (Sprint 0)

- Login por sessão HTTP-only + CSRF, com bloqueio após 5 senhas erradas (15 min).
- Multi-tenant: cada clínica só vê e altera os próprios dados (`clinic_id` vem sempre da sessão).
- Perfis: **OWNER** (responsável, altera tudo) e **STAFF** (consulta).
- Painel admin pensado para o celular: barra de navegação embaixo (Hoje, Agenda, Pacientes, Catálogo, Mais) e menu
  lateral no computador; formulários em gaveta (folha que sobe no celular), menu "⋯" com as ações secundárias,
  confirmação e avisos próprios, listas vazias com o próximo passo.
  - **Hoje**: saudação, sessões do dia, quantas faltam confirmar até amanhã, pacotes acabando (com mensagem de
    renovação), atalhos (+ Sessão, + Paciente, lembretes de amanhã) e "Primeiros passos" até a clínica estar pronta.
  - **Serviços e pacotes** (Catálogo) — **Serviços**: nome, descrição, duração, preço opcional, "mostrar preço no site", "permitir agendamento online".
  - **Pacotes**: serviço, nº de sessões, preço opcional (mostra valor por sessão), validade, exibição do preço.
  - **Site**: **logo** (PNG, JPG, WebP ou SVG, convertida para PNG no navegador) com **cores sugeridas a partir da
    logo** (até 4 paletas legíveis), cores com prévia e alerta de contraste, estilo e fontes do site, **fotos** (capa
    e até 6 na galeria, reduzidas e recomprimidas no navegador; o servidor tira EXIF/GPS), sobre, formação,
    horários, bairros atendidos em casa, mapa, **depoimentos** (só com autorização por escrito do paciente; seção
    desligada por padrão) e **perguntas frequentes**, com ordem ajustável.
  - **Clínica**: dados da clínica, endereço da página pública e regra de falta dos pacotes.
- **Pacientes**: nome, WhatsApp, nascimento, CPF opcional (validado, para recibo) e observações administrativas;
  busca por nome sem acento. Sem dados clínicos até confirmar regras do COFFITO e da LGPD.
- **Agenda** (dia e semana): sessões avulsas com duração do serviço, status (agendada, confirmada, realizada,
  falta, cancelada), remarcação guardando o horário original, bloqueio de horários (almoço, folga).
  **Sem conflito de horário** na mesma clínica, garantido pelo banco (constraint de exclusão com `btree_gist`).
  Horários no fuso da clínica (padrão `America/Fortaleza`).
  **Recorrência** de sessões e bloqueios (ex.: seg/qua/sex às 8h por 5 semanas; almoço de segunda a sexta), com
  prévia das datas; se algum dia estiver ocupado, nada é criado e a tela lista os conflitos e oferece pular esses
  dias. "Cancelar próximas" encerra a série sem mexer no que já aconteceu.
- **Venda de pacote e saldo de sessões**: o pacote vendido copia nome, sessões e preço do catálogo (com valor
  combinado opcional) e calcula a validade. Sessão agendada reserva vaga, **realizada consome**, cancelada devolve;
  nunca passa do total (excedente fica avulsa). Ficha do paciente mostra compradas, realizadas, agendadas e
  restantes; aviso "faltam 2" na ficha e na agenda com mensagem de renovação pelo WhatsApp.
  **Falta sem aviso consome sessão?** Configurável por clínica (padrão: não consome); a decisão fica gravada em cada
  falta, então mudar a regra não reescreve o passado.
- **Confirmação pelo WhatsApp** (link `wa.me`, custo zero): botão "Pedir confirmação" nas sessões agendadas com a
  mensagem pronta ("Olá, Maria! Confirmando sua sessão de Pilates amanhã (qui, 15/10) às 8h. Responda SIM…") e
  **Lembretes de amanhã**, que abre um paciente por vez.
- Página pública `/c/{slug}`: site da clínica montado a partir da logo, das cores e **dos serviços** que ela oferece.
  **4 modelos** com estrutura diferente (Clássico: texto e foto lado a lado; Imersivo: foto em tela cheia e seções
  na cor da clínica; Editorial: título grande, foto larga e serviços numerados; Acolhedor: topo colorido, foto
  redonda e cartões) e **3 climas** (Claro, Suave, Escuro). Fundo e texto saem das cores da clínica — nada de
  branco/preto puros — com contraste WCAG conferido em teste para qualquer paleta. Escolha no painel com miniaturas
  feitas com a foto e as cores da clínica e "Ver prévia" (`/c/{slug}?modelo=&clima=`) antes de salvar.
  Os serviços sugerem o modelo e a **personalidade** (Sereno: Pilates/estética; Dinâmico: esportiva/ortopedia;
  Acolhedor: infantil/idosos/domiciliar; Clínico: respiratória/neuro/pélvica), com **par de fontes** do Google
  Fonts, ícones por serviço e frase de destaque. Seções (cada uma só aparece se tiver conteúdo): topo com a foto de capa e
  o nome da profissional, faixa com CREFITO/atendimento em casa/horários, **serviços em lista** (sem preço: preço e
  pagamento ficam para o agendamento), quem cuida de você + formação, galeria, como agendar, depoimentos, perguntas
  frequentes, onde atendo com mapa e rodapé. A logo aparece só na barra do topo e no rodapé. Botão "Agendar pelo
  WhatsApp" com mensagem pronta (por serviço) e botão flutuante no celular.
- **Agendamento pelo site** (sem login do paciente): horário de atendimento por dia da semana (vários intervalos),
  antecedência mínima, dias à frente, intervalo entre horários e por quanto tempo o pedido segura o horário
  (padrão 24 h). O site mostra só horários livres (tira sessões, bloqueios e pedidos pendentes) e pede nome,
  **CPF obrigatório** (validado), WhatsApp e consentimento; campo anti-robô e limite por IP e por CPF. O painel
  recebe em **Solicitações** (aviso na tela Hoje): o CPF acha o paciente já cadastrado — sem duplicar — e mostra o
  que mudou (nome/WhatsApp) para a clínica decidir se atualiza; paciente antigo sem CPF é sugerido pelo WhatsApp;
  CPF novo cria o cadastro ao aceitar. Aceitar põe a sessão na agenda (usa o pacote do paciente se houver) e abre a
  mensagem pronta no WhatsApp; recusar guarda o motivo. O site nunca revela se um CPF é paciente da clínica.
- **Financeiro**: pagamentos por Pix, dinheiro, débito ou crédito (até 12x), ligados a um pacote, a uma sessão
  avulsa ou "por conta". **Maquininhas** com taxa do débito, crédito à vista, crédito parcelado (2x a 12x), tarifa
  fixa e prazo; o servidor calcula taxa, líquido e os depósitos previstos (um por parcela, ou um só se antecipado).
  Opção de **repassar a taxa** ao paciente (cobra valor ÷ (1 − taxa) para a clínica receber o valor cheio). **Quem
  está devendo** = pacotes vendidos com valor + sessões avulsas realizadas − pagamentos, com lembrete pronto no
  WhatsApp. Resumo do mês (recebido, taxas, líquido, por forma), **a receber** da maquininha (marcar como
  recebido), estorno (o pagamento fica no histórico) e a conta de cada paciente na ficha. A sessão avulsa guarda o
  valor do serviço no dia em que foi marcada (pode ter desconto ou cortesia) e a agenda mostra "a pagar"/"pago".
- **Pix sem intermediário** (custo zero): a clínica cadastra a chave em Clínica → Pix; o sistema gera o **copia e cola
  + QR Code** (BR Code do Banco Central) com o valor exato e o código do pedido. No agendamento, serviço com preço
  visível oferece **Pagar agora via Pix** ou **Pagar na clínica**; quem paga envia o **comprovante** (foto ou PDF,
  conferido pelo conteúdo, até 3 MB, só com o token do próprio pedido) e o horário fica reservado por mais 48 h. Em
  Solicitações a clínica vê o comprovante (só a responsável), com o aviso de conferir no app do banco, e ao aceitar
  pode lançar o Pix no financeiro. O lembrete de quem está devendo pode levar o Pix com o valor em aberto.
- **Recibo para o Imposto de Renda**: de um pagamento (menu ⋯ do pagamento) ou de um período (ficha do paciente →
  "Recibo para o IR", ex.: o ano todo). Traz profissional (nome, CREFITO, CPF/CNPJ, endereço), paciente (nome e CPF),
  pagamentos com data e descrição, total por extenso e assinatura; avisa o que falta no cadastro para valer no IR.
  Imprime ou salva em PDF pelo próprio navegador (sem serviço externo). Estornos não entram.
- **Conta e equipe**: troca de senha; a responsável dá acesso a colaboradores (só consulta) ou a outra responsável,
  com **senha temporária** (mensagem pronta para o WhatsApp) que a pessoa troca no primeiro login; remover acesso
  (desativa), reativar e gerar nova senha. A clínica nunca fica sem uma responsável ativa.
- **Cadastro de clínica pelo site** (`/cadastro`, período de testes), com aceite dos **termos de uso** (`/termos`) e da
  **política de privacidade** (`/privacidade`, LGPD) — versão aceita fica registrada. Campo anti-robô e limite por IP;
  desligável com `SIGNUP_ENABLED=false`. Página do produto em `/` para quem não entrou.
- **Aniversariantes**: aviso animado ao abrir o painel (uma vez por dia) e sub-aba em Pacientes (hoje, 7 dias, mês),
  com ativos e desativados; mensagem de parabéns com emojis e modelo editável (`{nome}`, `{idade}`, `{clinica}`),
  enviada pelo WhatsApp (link `wa.me`).
- **Administração da plataforma** (`/admin`, só a equipe PhysioEvo — `PLATFORM_ADMIN_EMAIL`/`PLATFORM_ADMIN_PASSWORD`):
  lista as clínicas assinantes com a situação (em teste, assinante, acesso gratuito, vencida, suspensa, bloqueada) e
  permite dar teste grátis ou acesso gratuito por N dias (ou sem prazo), ativar, **suspender** (só consulta; o site
  fica como vitrine, sem agendamento) e **bloquear** (sem login e sem site). Toda ação exige motivo e fica no
  histórico. **Nada é apagado**: vencida, suspensa ou bloqueada, a clínica mantém todos os dados. Não mostra dados
  de pacientes (só a quantidade). Cadastro pelo site começa com **30 dias de teste** (`TRIAL_DAYS`).
- **Aviso de vencimento** no painel da clínica: faixa a partir de 7 dias antes e janela nos dias 7, 3, 1 e no dia.
- Itens são **desativados**, nunca apagados (preserva histórico de pacotes e agendamentos). Exceção: **fotos** do
  site são apagadas de verdade ao remover (não são histórico e podem mostrar pessoas — LGPD).

## Rodando localmente

Requisitos: Java 21, Maven 3.9, Node 20+, Docker.

```bash
docker compose up -d                 # PostgreSQL 16 (bancos fisio e fisio_test)

cd backend
BOOTSTRAP_OWNER_EMAIL=voce@email.com \
BOOTSTRAP_OWNER_PASSWORD='uma-senha-com-12+' \
BOOTSTRAP_CLINIC_NAME='Clínica Teste' \
BOOTSTRAP_CLINIC_SLUG=clinica-teste \
mvn spring-boot:run                  # API em http://localhost:8080

cd ../frontend
npm install && npm run dev           # painel em http://localhost:5173
```

As variáveis `BOOTSTRAP_*` só criam a primeira clínica quando o banco está vazio. Remova-as depois do
primeiro login.

## Testes

```bash
cd backend && mvn verify      # integração com PostgreSQL real (banco fisio_test) + Flyway
cd frontend && npm test       # tema, paletas da logo, estilo e conteúdo do site, fotos, CPF/telefone, datas, recorrência e mensagens
```

Cobertura atual: login e sessão, CSRF obrigatório, isolamento entre clínicas (ler, alterar, desativar,
pacote com serviço de outra clínica), permissão OWNER x STAFF, vitrine escondendo preço, página
desativada, validação de cores, WhatsApp só com dígitos, preço negativo, bloqueio de login; pacientes (busca sem
acento, CPF inválido/repetido, isolamento entre clínicas, STAFF só consulta, desativar e reativar); agenda (fuso,
conflito pela API e pelo banco, bloqueio, remarcação, realizada/falta só depois do horário, isolamento, STAFF);
recorrência (datas geradas, conflitos listados sem criar nada, pular conflitos, bloqueio recorrente, cancelar
próximas preservando realizadas, isolamento); pacotes (cópia do catálogo, consumo, reserva sem passar do saldo,
regra da falta sem reescrever o passado, desfazer, venda depois de agendar, encerrar, pacote de outro paciente
recusado pela API e pelo banco, isolamento, STAFF); logo (tipo pela assinatura, tamanho e dimensões, SVG/GIF
recusados, isolamento, vitrine desativada, STAFF); site (capa única, galeria até 6, ordem, foto JPEG com EXIF removido,
foto apagada de verdade, depoimento só com autorização e seção ligada, perguntas, textos de confiança, isolamento
de fotos/depoimentos/perguntas, STAFF só consulta); agendamento pelo site (horários livres, pedido e aceite, CPF
existente sem duplicar, paciente antigo sem CPF ligado, recusa/expiração, limites por CPF e IP, robô, CSRF,
isolamento entre clínicas, STAFF só consulta).

## API

| Método | Rota | Quem |
|---|---|---|
| GET | `/api/v1/auth/csrf` · POST `/api/v1/auth/login` · POST `/api/v1/auth/logout` | público / sessão |
| GET | `/api/v1/auth/me` | logado |
| GET · PUT | `/api/v1/clinic` | logado · OWNER |
| GET · POST · PUT `/{id}` · DELETE `/{id}` | `/api/v1/services` | logado · OWNER |
| GET · POST · PUT `/{id}` · DELETE `/{id}` | `/api/v1/packages` | logado · OWNER |
| GET `?q=&includeInactive=` · POST · GET `/{id}` · PUT `/{id}` · DELETE `/{id}` | `/api/v1/patients` | logado · OWNER |
| GET `?from=&to=&includeCanceled=` · POST · GET `/{id}` · PUT `/{id}` (remarcar) · DELETE `/{id}` (cancelar) | `/api/v1/appointments` | logado · OWNER |
| POST | `/api/v1/appointments/{id}/status` · `/api/v1/blocks` | OWNER |
| POST · DELETE `/{id}?from=` (cancelar próximas) | `/api/v1/appointments/series` | OWNER |
| PUT | `/api/v1/appointments/{id}/package` (trocar pacote ou deixar avulsa) | OWNER |
| GET · POST | `/api/v1/patients/{id}/packages` (saldo · vender) | logado · OWNER |
| PUT · DELETE `/{id}` · GET `/low-balance` | `/api/v1/patient-packages` | OWNER · logado |
| POST · GET · DELETE | `/api/v1/clinic/logo` | OWNER · logado · OWNER |
| GET · POST (multipart `file`, `kind`=COVER/GALLERY) · PUT `/{id}` (legenda) · POST `/{id}/move` · DELETE `/{id}` · GET `/{id}/image` | `/api/v1/clinic/photos` | logado · OWNER |
| GET · POST · PUT `/{id}` · POST `/{id}/move` · DELETE `/{id}` | `/api/v1/clinic/testimonials` · `/api/v1/clinic/faqs` | logado · OWNER |
| GET · PUT | `/api/v1/clinic/booking` (horário de atendimento e regras do agendamento) | logado · OWNER |
| GET `?status=PENDING\|ALL` · GET `/count` · GET `/{id}` · POST `/{id}/accept` · POST `/{id}/decline` | `/api/v1/booking-requests` | logado · OWNER |
| GET · POST · PUT `/{id}` · DELETE `/{id}` | `/api/v1/card-machines` (maquininhas e taxas) | logado · OWNER |
| GET `?from&to` · POST · POST `/preview` · POST `/{id}/cancel` (estorno) | `/api/v1/payments` | logado · OWNER |
| GET | `/api/v1/finance/debtors` · `/api/v1/finance/summary?month=` · `/api/v1/finance/receivables` · `/api/v1/patients/{id}/finance` | logado |
| POST · PUT | `/api/v1/finance/receivables/{id}/received?received=` · `/api/v1/appointments/{id}/price` | OWNER |
| GET · PUT | `/api/v1/clinic/pix` (chave Pix) · POST `/api/v1/finance/pix` (código com valor) | logado · OWNER |
| GET | `/api/v1/booking-requests/{id}/receipt` (comprovante do Pix) | OWNER |
| POST | `/api/v1/public/clinics/{slug}/booking-requests/{id}/receipt` (multipart `file` + `token`, com CSRF) | público |
| GET | `/api/v1/payments/{id}/receipt` · `/api/v1/patients/{id}/receipt?from&to` (recibo para o IR) | logado |
| PUT | `/api/v1/account/password` | logado |
| GET · POST · PUT `/{id}` · DELETE `/{id}` · POST `/{id}/reset-password` · POST `/{id}/reactivate` | `/api/v1/team` | logado · OWNER |
| POST `/signup` (com CSRF) · GET `/platform` | `/api/v1/public` | público |
| GET | `/api/v1/public/clinics/{slug}` · `/{slug}/logo` · `/{slug}/photos/{id}` · `/{slug}/slots?serviceId&from&to` | público |
| POST | `/api/v1/public/clinics/{slug}/booking-requests` (com CSRF) | público |

## Produção (Oracle Cloud Always Free)

`docker-compose.prod.yml` sobe app + PostgreSQL (sem porta exposta) + Caddy com HTTPS automático.
Crie um `.env` (fora do Git):

```
DOMAIN=app.seudominio.com.br
DATABASE_PASSWORD=senha-forte
BOOTSTRAP_OWNER_EMAIL=...
BOOTSTRAP_OWNER_PASSWORD=...
BOOTSTRAP_CLINIC_NAME=...
BOOTSTRAP_CLINIC_SLUG=...
PLATFORM_CONTACT_EMAIL=contato@seudominio.com.br   # aparece nos termos e na política de privacidade
SIGNUP_ENABLED=true                                # false = só a clínica inicial (sem cadastro pelo site)
TRIAL_DAYS=30                                      # dias de teste grátis de quem se cadastra pelo site
PLATFORM_ADMIN_EMAIL=equipe@seudominio.com.br      # administração da plataforma em /admin (criado se não existir)
PLATFORM_ADMIN_PASSWORD=...                        # 12+ caracteres; só vale na criação (não troca senha existente)
```

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

## Próximos passos (ordem sugerida)

1. Correções do teste de homologação (cartões 🐞 no Trello), começando pela sessão que cai a cada deploy.
2. Revisão jurídica dos termos e da política de privacidade; nome do produto e domínio.
3. Pós-MVP: site com IA (Gemini) só para sugerir textos, se fizer falta.

## Marca PhysioEvo

Arquivos em `frontend/public/brand/` (desenhados para o projeto; fonte Plus Jakarta Sans, licença OFL, já convertida
em curvas nos SVG, então não depende da fonte instalada):

| Arquivo | Uso |
|---|---|
| `physioevo-logo.svg` / `physioevo-logo-white.svg` | Logo horizontal (fundo claro / fundo escuro) |
| `physioevo-mark.svg` / `physioevo-mark-mono.svg` | Só o símbolo (colorido / uma cor) |
| `favicon.svg`, `favicon-32.png`, `apple-touch-icon.png`, `icon-192.png`, `icon-512.png` | Ícones do navegador e do app na tela inicial |
| `og-image.png` | Imagem de compartilhamento (WhatsApp, redes sociais), 1200×630 |

O símbolo é uma sequência de vértebras que sobe em direção a uma seta: evolução do paciente e da clínica.

| Cor | Hex |
|---|---|
| Azul-petróleo escuro | `#0A5C63` |
| Verde-menta | `#14B89A` |
| Verde "Evo" | `#0E8C7E` |
| Coral (chamadas para ação) | `#FF7A59` |
| Azul-marinho (textos) | `#0B2B33` |
| Menta claro (fundos) | `#E3F6F1` |

O site de divulgação (`/`) apresenta o site da clínica como bônus da assinatura, e o rodapé de cada site público
leva o crédito "Site feito com PhysioEvo".
