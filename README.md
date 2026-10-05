# BME Pilots 2026 · Backend

Spring Boot modular monolith for the **unofficial, private BME Professional Pilot 2026 student community**. Planned public domain: `bmepilots2026.com`. The container deployment is maintained in the sibling `../db/deploy` directory and targets a private Ubuntu VM. The current access mode is loopback HTTP through SSH forwarding; public HTTPS and Cloudflare are future work. See `docs/STATUS.md` for actual deployment and verification evidence.

## Repository map

Keep these three independent Git repositories as siblings:

```
bmepilots/
  backend/   Java REST API and all Flyway application migrations
  frontend/  React/TypeScript user interface
  db/        Docker MariaDB, full-stack deployment and operations documentation
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

### Ubuntu VM container deployment

`Dockerfile` builds the packaged Spring Boot application with Java 21 and runs it as non-root UID/GID `10001`. The image sets `SERVER_ADDRESS=0.0.0.0` and listens on port 8080 inside Docker; host-run development retains the loopback default. The image healthcheck calls `/actuator/health` internally. `docker-entrypoint.sh` reads `DB_PASSWORD_FILE` and `BOOTSTRAP_ADMIN_PASSWORD_FILE` when the corresponding environment passwords are absent, then replaces the shell with Java. `MAIL_PASSWORD_FILE` is read directly by the mail provider. Secret contents are never included in the image.

Use the canonical Compose stack and runbook in [`../db/deploy`](../db/deploy/README.md), which replaces the earlier unversioned deployment draft. Its base configuration publishes no host ports. The backend joins an internal API network, an internal database network and a separate egress network for Gmail IMAP. Database, document, attachment and log data persist on the mounted `/srv/bmepilots` disk. `scripts/prepare.sh` prepares storage and Linux secret permissions; `scripts/start.sh` verifies the mount and waits for service health. Backend secret files must be readable by group `10001`, and backend storage must be writable by UID `10001`.

The temporary `compose.loopback.yml` override publishes only the gateway at `127.0.0.1:8088` on the VM and sets `COOKIE_SECURE=false` for an SSH-forwarded HTTP preview. Database and backend ports remain unpublished. Use `COOKIE_SECURE=true` with future HTTPS and omit that preview override when publishing through a tunnel. Keep the bootstrap secret file while Compose references it; after creating and changing the administrator password, clear `BOOTSTRAP_ADMIN_EMAIL` to disable subsequent bootstrap attempts.

### Continuous integration and image publication

`.github/workflows/ci.yml` is configured to run Spotless verification and the full Maven test suite against a disposable MariaDB service on pull requests and pushes to `main`, then build the Docker image. A successful `main` run is configured to publish `ghcr.io/<owner>/backend:<commit-sha>` plus the moving `:main` tag using the workflow's short-lived `GITHUB_TOKEN`. Use the published digest as the immutable deployment reference; tags can move. See `docs/CI.md` for workflow and registry details. Workflow configuration is not proof of a completed GitHub run or published image; consult `docs/STATUS.md` for verified results. The workflow does not deploy to the VM.

In host-run development, the API binds to `127.0.0.1:8080`. The frontend proxies `/api` to it; do not access the development server from an untrusted network. Development health: `http://127.0.0.1:8080/actuator/health`.

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
| DB_PASSWORD_FILE | Container-entrypoint alternative to DB_PASSWORD; readable secret file |
| PORT | 8080; network binding follows SERVER_ADDRESS |
| SERVER_ADDRESS | `127.0.0.1`; set to `0.0.0.0` for the container network |
| COOKIE_SECURE | false for local HTTP; true required with future HTTPS |
| BOOTSTRAP_ADMIN_EMAIL/PASSWORD | Optional first-admin bootstrap |
| BOOTSTRAP_ADMIN_PASSWORD_FILE | Container-entrypoint alternative to BOOTSTRAP_ADMIN_PASSWORD |
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

- The VM's private HTTP preview is not public HTTPS. Cloudflare, trusted proxy configuration and automatic VM updates remain follow-up work.
- The application and Flyway currently share a database-scoped credential. Separate runtime and migration privileges before public production use.
- Scheduled encrypted offsite backups and restore rehearsals remain operational follow-up work; preserve the database together with both private file stores.
- Session storage is in memory; restart logs users out. One backend instance only.
- Every active member can read all imported mail; read/important flags are private to each user.
- Members can share documents (1–5 files, up to 50 MiB each), discuss posts, add calendar entries and contribute useful links. Authors manage their own content; administrators manage all content. Knowledge articles existing at migration V5 are preserved as document posts; the old knowledge API is read-only.
- No SMTP, Gmail API, external calendar synchronization, permission editor or public email verification.
- Mail defaults to off in source configuration; this workspace has a verified local Gmail configuration. Read `docs/MAIL.md` before enabling elsewhere.
- See STATUS for exact completed functionality and outstanding items rather than treating the original design as implemented.
