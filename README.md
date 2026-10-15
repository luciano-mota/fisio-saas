# Fisio SaaS (nome provisório)

Sistema para fisioterapeutas autônomos: a própria clínica cadastra serviços e pacotes de sessões
(com ou sem preço) e ganha um site profissional no estilo dela, com os serviços, fotos, formação, depoimentos e
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
de fotos/depoimentos/perguntas, STAFF só consulta).

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
| GET | `/api/v1/public/clinics/{slug}` · `/{slug}/logo` · `/{slug}/photos/{id}` | público |

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
```

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

## Próximos passos (ordem sugerida)

1. Horário de atendimento e horários livres (base do agendamento online).
2. Agendamento online pelo site, sem Pix (solicitação → aprovação da clínica → sessão na agenda).
3. Pix com QR Code estático + envio de comprovante.
4. Site com IA (Gemini): conteúdo e design em JSON validado, rascunho revisado pelo admin, sem dados de pacientes.
5. Pagamentos: maquininhas e taxas (bruto/taxa/líquido).
