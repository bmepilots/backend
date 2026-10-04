# BME Pilots 2026 · Backend

Spring Boot modular monolith for the **unofficial, private BME Professional Pilot 2026 student community**. Planned public domain: `bmepilots2026.com`. This version is local development only; cloud deployment, Cloudflare, CI/CD and production exposure are deliberately deferred.

## Repository map

Keep these three independent Git repositories as siblings:

```
bmepilots/
  backend/   Java REST API and all Flyway application migrations
  frontend/  React/TypeScript user interface
  db/        Docker MariaDB and local operations documentation
```

Start documentation reading here, then `docs/ARCHITECTURE.md`, `docs/API.md`, `docs/MAIL.md`, `docs/DOCUMENTS.md`, `docs/COMMUNITY.md`, `docs/AUDIT.md`, and `docs/STATUS.md`. Agents must read `AGENTS.md` and update documentation with every change.

## Requirements

- Java 21 JDK. Maven is downloaded by the committed Maven Wrapper.
- Running Docker Desktop with Linux containers; start MariaDB from `../db`.
- Node 22.12+ for the sibling frontend (not needed for this API alone).
- No Gmail account is needed to run auth, content, admin, or the empty local inbox.

## Local quick start (PowerShell 7)

1. In `../db`, run `pwsh -File scripts/setup.ps1` once, then `docker compose -f compose.yml -f compose.dev.yml up -d --wait`.
2. Set `$env:JAVA_HOME` to your Java 21 installation if it is not already set.
3. In this repository run `pwsh -File scripts/dev.ps1 -Bootstrap`.
4. In `../frontend`, run `npm ci` and `npm run dev`.
5. Open `http://127.0.0.1:5173`.

The API binds to `127.0.0.1:8080`. The frontend proxies `/api` to it; do not access the development server from an untrusted network. Health: `http://127.0.0.1:8080/actuator/health`.

### Initial administrator

`-Bootstrap` creates `.local-admin.env` with email `admin@bmepilots2026.local` and a random password, without printing the password. Open that **local ignored file** to sign in. Bootstrap runs only if no ADMIN role assignment exists; it does not reset an existing administrator or make the first public registrant an admin. Change the password via `/account` after signing in. The old bootstrap password file does not follow password changes.

Subsequent normal starts use `pwsh -File scripts/dev.ps1` without `-Bootstrap`. Delete the bootstrap credential file once securely recorded/changed. Never commit it. For manually configured launchers, use `BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` (at least 16 characters), then remove them after first creation.

Registration starts **closed**. Open it at Admin → Settings. New registrations receive USER / PENDING_APPROVAL and require admin approval. Existing email registration receives the same generic response.

### Linux/macOS or IDE

Set JAVA_HOME and `DB_URL`, `DB_USER`, `DB_PASSWORD` from your private db `.env`, then run `./mvnw spring-boot:run`. The PowerShell script is a convenience, not an application requirement. In an IDE, run `PortalApplication` with those environment variables and working directory set to this repository. Never paste environment secrets into a shared run configuration.

## Configuration reference

| Variable | Default / purpose |
|---|---|
| DB_URL | `jdbc:mariadb://127.0.0.1:3307/bmepilots` |
| DB_USER | `bmepilots` |
| DB_PASSWORD | Required; no working password committed |
| PORT | 8080, loopback only |
| COOKIE_SECURE | false for local HTTP; true required with future HTTPS |
| BOOTSTRAP_ADMIN_EMAIL/PASSWORD | Optional first-admin bootstrap |
| MAIL_ENABLED | false |
| MAIL_USERNAME | Shared Gmail address |
| MAIL_PASSWORD_FILE | Absolute private file path containing only the App Password |
| MAIL_STORAGE | `./storage/attachments`, ignored by Git |
| MAIL_INTERVAL_MS | 60000 |
| MAIL_INITIAL_DAYS | 90 |
| DOCUMENT_STORAGE | `./storage/documents`; private uploaded files, ignored by Git |
| APP_LOG_FILE | `./logs/portal.log`; rolling operational log, ignored by Git |

The application does not automatically load `.env`. The development launcher reads `../db/.env`, optional `.env.mail.local`, and `.local-admin.env` when explicitly bootstrapping. The mail file accepts only the six MAIL variables listed above; existing process environment values take precedence. Relative MAIL_PASSWORD_FILE and MAIL_STORAGE paths resolve against this repository. Use plain, unquoted `KEY=value` lines. The `-Test` path skips this local configuration. IDE/manual launches must supply their own environment variables.

The local Gmail configuration uses ignored `.env.mail.local` and `.secrets/gmail-app-password`. Only the password file contains the secret; the local Windows directory restricts access to the current user and SYSTEM. These files are machine-local and are not distributed by Git. See `docs/MAIL.md` for setup and credential replacement. Never put secrets in YAML or VITE variables.

## Development workflow

1. Read module ownership and API documentation.
2. Implement a feature inside its domain package.
3. Add a new Flyway migration when the schema changes; do not edit applied migrations.
4. Add meaningful behavior/security tests.
5. Run the documented checks and update STATUS, API, architecture and mail docs as appropriate.
6. Coordinate API changes with the frontend repo; there is no atomic multi-repository release mechanism yet.

## Checks

Compilation: `./mvnw -DskipTests compile`. Full tests: `pwsh -File scripts/test.ps1` (also available via `dev.ps1 -Test`). Formatting: `./mvnw spotless:apply`, verification: `./mvnw spotless:check`. Full test instructions are in `docs/TESTING.md`. Tests require a dedicated Docker MariaDB, never the development schema or H2. See STATUS for actual checks performed and remaining verification.

## Limitations and future work

- Local HTTP settings are not production hardened. Deployment/backup automation is not configured.
- Session storage is in memory; restart logs users out. One backend instance only.
- Every active member can read all imported mail; read/important flags are private to each user.
- Members can share documents (1–5 files, up to 50 MiB each), discuss posts, add calendar entries and contribute useful links. Authors manage their own content; administrators manage all content. Knowledge articles existing at migration V5 are preserved as document posts; the old knowledge API is read-only.
- No SMTP, Gmail API, external calendar synchronization, permission editor or public email verification.
- Mail defaults to off in source configuration; this workspace has a verified local Gmail configuration. Read `docs/MAIL.md` before enabling elsewhere.
- See STATUS for exact completed functionality and outstanding items rather than treating the original design as implemented.
