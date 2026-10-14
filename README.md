# Fisio SaaS (nome provisório)

Sistema para fisioterapeutas autônomos: a própria clínica cadastra serviços e pacotes de sessões
(com ou sem preço) e ganha uma página pública no estilo dela, onde o paciente escolhe o serviço e a
quantidade de sessões. Planejamento completo no Trello: **Fisio SaaS | Descoberta e MVP**.

## O que já funciona (Sprint 0)

- Login por sessão HTTP-only + CSRF, com bloqueio após 5 senhas erradas (15 min).
- Multi-tenant: cada clínica só vê e altera os próprios dados (`clinic_id` vem sempre da sessão).
- Perfis: **OWNER** (responsável, altera tudo) e **STAFF** (consulta).
- Painel admin:
  - **Serviços**: nome, descrição, duração, preço opcional, "mostrar preço no site", "permitir agendamento online".
  - **Pacotes**: serviço, nº de sessões, preço opcional (mostra valor por sessão), validade, exibição do preço.
  - **Clínica e aparência**: dados públicos, cores do tema com prévia e alerta de contraste, endereço da página.
- **Pacientes**: nome, WhatsApp, nascimento, CPF opcional (validado, para recibo) e observações administrativas;
  busca por nome sem acento. Sem dados clínicos até confirmar regras do COFFITO e da LGPD.
- Página pública `/c/{slug}`: vitrine com as cores da clínica; preço só aparece se a clínica permitir
  ("Valor sob consulta" caso contrário); botão "Agendar pelo WhatsApp" com a escolha já escrita.
- Itens são **desativados**, nunca apagados (preserva histórico de pacotes e agendamentos).

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
cd frontend && npm test       # contraste do tema, formatação de CPF/telefone
```

Cobertura atual: login e sessão, CSRF obrigatório, isolamento entre clínicas (ler, alterar, desativar,
pacote com serviço de outra clínica), permissão OWNER x STAFF, vitrine escondendo preço, página
desativada, validação de cores, WhatsApp só com dígitos, preço negativo, bloqueio de login; pacientes (busca sem
acento, CPF inválido/repetido, isolamento entre clínicas, STAFF só consulta, desativar e reativar).

## API

| Método | Rota | Quem |
|---|---|---|
| GET | `/api/v1/auth/csrf` · POST `/api/v1/auth/login` · POST `/api/v1/auth/logout` | público / sessão |
| GET | `/api/v1/auth/me` | logado |
| GET · PUT | `/api/v1/clinic` | logado · OWNER |
| GET · POST · PUT `/{id}` · DELETE `/{id}` | `/api/v1/services` | logado · OWNER |
| GET · POST · PUT `/{id}` · DELETE `/{id}` | `/api/v1/packages` | logado · OWNER |
| GET `?q=&includeInactive=` · POST · GET `/{id}` · PUT `/{id}` · DELETE `/{id}` | `/api/v1/patients` | logado · OWNER |
| GET | `/api/v1/public/clinics/{slug}` | público |

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

1. Agenda com sessões recorrentes e saldo de pacotes.
2. Pagamentos: maquininhas e taxas (bruto/taxa/líquido) e Pix com QR Code + comprovante.
3. Logo com cores sugeridas automaticamente.
4. Agendamento online na vitrine (solicitação → pagamento → confirmação).
