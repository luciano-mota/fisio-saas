# CLAUDE.md

SaaS para fisioterapeutas autônomos (a maioria atende em casa e controla tudo pelo WhatsApp).
Leia o `README.md`. Planejamento e decisões no Trello, board "Fisio SaaS | Descoberta e MVP"
(https://trello.com/b/Wyanne6Y/fisio-saas-descoberta-e-mvp); o cartão "📝 Registro de decisões" resume o que
já foi decidido.

## Produto

- Cada clínica tem um painel admin onde cadastra serviços e pacotes de sessões, com preço **opcional** e
  "mostrar preço no site". Sem preço (ou com preço oculto) → "Valor sob consulta".
- Cada clínica tem uma página pública `/c/{slug}` (vitrine estilo loja) com as cores dela.
- Sem dados clínicos (prontuário/evolução) até confirmar regras do COFFITO e da LGPD.

## Stack

- Backend: Java 21, Spring Boot 3.3, Maven, `JdbcTemplate` (sem JPA), PostgreSQL 16 + Flyway
  (`backend/src/main/resources/db/migration`, nunca editar migração já publicada; crie uma nova `V{n}__...`).
- Frontend: React 18 + Vite + TypeScript, sem bibliotecas de UI. Servido pelo mesmo JAR (mesmo domínio):
  o Dockerfile copia `frontend/dist` para `backend/src/main/resources/static`.

## Regras do código

- **Auth:** sessão HTTP-only + CSRF por cookie (`X-XSRF-TOKEN`). Perfis **OWNER** (altera) e **STAFF** (consulta).
  Escrita usa `current.requireOwner(auth)`; leitura usa `current.require(auth)`.
- **Multi-tenant:** o `clinic_id` SEMPRE vem da sessão (`CurrentClinic`), nunca do cliente. Toda query de dados
  de clínica filtra por `clinic_id`, inclusive ao validar IDs estrangeiros (ex.: serviço de um pacote).
  Registro de outra clínica responde 404 (não 403), para não revelar que existe.
- **Toda feature nova precisa de teste de isolamento entre clínicas** (ler, alterar, desativar, referenciar).
- **Nada é apagado:** itens são desativados (`active = false`) para preservar histórico. `DELETE` na API = desativar.
- **Dinheiro:** `NUMERIC(10,2)` no banco e `BigDecimal` no Java. Nunca `double` para valores.
- **Idioma:** mensagens de erro, UI e nomes de teste em português.
- **Custo zero de infraestrutura:** nada de serviços pagos. WhatsApp via link `wa.me`; Pix sem gateway (BR Code estático).
- WhatsApp é guardado só com dígitos.
- Erros da API: `{"error": "mensagem"}` via `ApiErrors` (`IllegalArgumentException` → 400,
  `NoSuchElementException` → 404, `AccessDeniedException` → 403, violação de integridade → 409).
- Trabalhar em passos pequenos, um commit por funcionalidade, com testes. Antes de cada feature, mostrar o plano
  (tabelas, endpoints, telas) e esperar o ok.

## Comandos

```bash
docker compose up -d                       # PostgreSQL 16 com os bancos fisio e fisio_test

cd backend && mvn verify                   # testes de integração (banco fisio_test, Flyway limpa e migra)
cd frontend && npm install && npm test && npm run build

# App local (API em :8080; painel em :5173 com proxy para a API)
cd backend && BOOTSTRAP_OWNER_EMAIL=voce@email.com BOOTSTRAP_OWNER_PASSWORD='senha-com-12+' \
  BOOTSTRAP_CLINIC_NAME='Clínica Teste' BOOTSTRAP_CLINIC_SLUG=clinica-teste mvn spring-boot:run
cd frontend && npm run dev

# Como em produção (React dentro do JAR)
cd frontend && npm run build && rm -rf ../backend/src/main/resources/static \
  && cp -r dist ../backend/src/main/resources/static
cd backend && mvn -DskipTests package && java -jar target/backend-0.1.0-SNAPSHOT.jar
```

Sem Docker: um PostgreSQL 16 local com usuário `fisio` / senha `fisio_dev_only` e os bancos `fisio` e
`fisio_test` serve. Em shell com locale POSIX, exporte `LANG=C.UTF-8` antes de subir o JAR, senão variáveis
de ambiente com acento (ex.: `BOOTSTRAP_CLINIC_NAME`) são lidas com caracteres trocados.

CI (GitHub Actions, `.github/workflows/ci.yml`): backend com Postgres, frontend (testes + build) e imagem Docker.
