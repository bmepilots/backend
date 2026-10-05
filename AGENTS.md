# Backend agent guide

Read `README.md`, `docs/ARCHITECTURE.md`, `docs/API.md`, and `docs/STATUS.md` before changing this repository.

## Non-negotiable rules
- All user-visible application text, API errors and default display labels must be English. Conversation with the user may be Hungarian. Documentation is English.
- Update relevant documentation and `docs/STATUS.md` in the same change as implementation. Record completed work, verification, known limitations, and the next steps. Never claim an unrun check passed.
- This is the private, unofficial BME Professional Pilot 2026 community portal. It is not an official university system. Planned domain: bmepilots2026.com. The authorized Ubuntu container deployment lives in `../db/deploy`; read its runbook before deployment changes.
- Three independent sibling repositories: `../frontend`, `../backend`, `../db`. Do not initialize a parent Git repository or move their `.git` directories.
- Java 21, Spring Boot, Spring Security, MariaDB, Flyway. Database runs in Docker. No H2 substitution for integration verification.
- Feature/domain packages. Controllers handle DTOs; services enforce business rules and own transactions. Do not expose database rows containing credentials.
- Cookie sessions and CSRF; never localStorage JWTs. Non-ACTIVE users cannot access protected APIs. All admin authorization is enforced by the backend.
- The backend owns all application schema migrations. Never duplicate schema creation in the db repository. Never edit an already released migration.
- No secrets, generated credentials, logs, database dumps, dependencies, build products, or attachments in Git.
- `.env.mail.local` and `.secrets/` are private local mail configuration. Never print or inspect password contents for routine verification; use connection status and aggregate sync counts. Preserve restrictive filesystem permissions.
- Maintain the authorized private VM deployment: base Compose publishes no ports; its HTTP preview override exposes only the gateway on VM loopback for SSH forwarding. Cloudflare/public HTTPS and automatic VM updates remain future work; never describe configured workflows as successfully executed without evidence.
- The container runs as UID/GID 10001. Preserve LF shell scripts, backend storage ownership and readable Docker secret mounts; never relax secret permissions to world-readable to fix startup.
- Do not add speculative infrastructure or placeholder implementations. Document deferred capabilities honestly.

## Verification
Use `pwsh -File scripts/test.ps1` with JAVA_HOME pointing to JDK 21. It starts the dedicated tmpfs MariaDB on loopback 3308 and runs Maven verify; never substitute the persistent development DB on 3307. Read `docs/TESTING.md` for other platforms. Format with `./mvnw spotless:apply`; check with `./mvnw spotless:check`.

## Documentation map
- README: installation and operator quick start.
- docs/ARCHITECTURE.md: module ownership, persistence and security decisions.
- docs/API.md: API contract, authentication and errors.
- docs/STATUS.md: handoff record and verification evidence.
- ../db/deploy/README.md: canonical VM deployment, persistent storage and operator procedures.
