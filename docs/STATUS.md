# Backend status and handoff

Updated: 2026-10-05. Keep this current with every implementation change.

## Implemented in the initial application pass
- Java 21 / Spring Boot 4.0.8 project with Maven Wrapper and feature packages.
- Seven Flyway migrations: identity, content, mail, durable mail failures, Shared Documents, calendar/community links and request-audit metadata.
- Argon2id passwords, cookie session, CSRF, bootstrap admin, approval lifecycle, backend ADMIN guard, optimistic versions and last-active-admin protection.
- Announcements, read-only legacy knowledge archive, Shared Documents with private multi-file uploads/comments, member-contributed calendar and useful links, settings and audit APIs.
- Dashboard composition; shared local inbox, recipient/attachment downloads and per-user state.
- Disabled-by-default Gmail IMAP adapter, bounded MIME processing, sanitization and scheduled idempotent import.
- Setup/architecture/API/mail documentation and agent rules.
- All API/user-facing messages are English. Spotless formats the Java source; no default Spring Security user is created.
- All active members can contribute documents, comments, calendar entries and useful links; authors can manage their own content and administrators can manage all content.
- Request metadata and named activity logging are separated in the admin audit views. Payloads, credentials, query strings and personal content are excluded.
- Java 21 container image runs as UID/GID 10001 with database/bootstrap file-secret support and configurable SERVER_ADDRESS. Canonical full-stack deployment is in ../db/deploy. Private VM runtime verified on 2026-10-05; CI workflows are configured for verification and GHCR publication.

## Verified so far
- Maven compilation successful against Java 21.
- Docker MariaDB 11.8.8 healthy on local loopback after Docker Desktop network adjustment.
- Application booted and applied V1–V7 successfully to development database without resetting existing data.
- Maven verify passed on 2026-10-04: 12 tests, zero failures/errors/skips, including Shared Documents multipart boundaries/rollback, calendar/link permissions, audit safety, real HTTP+MariaDB membership/content flow and idempotent mail/provider-state flow.
- Sanitization, MIME nesting limits, storage traversal, anonymous/wrong-message attachment access, stale content versions, approval, role guard, last-admin protection and suspended-session invalidation verified.
- Repeated Flyway startup validated existing V1–V7 without data reset. The development database had no legacy knowledge articles to migrate; V5 preserves such rows on a populated upgrade.
- Browser visual verification was blocked by browser permission denial; no workaround was attempted.
- Final Maven verify and Spotless check passed after English conversion and request-validation fixes. The packaged application JAR was built successfully.

## Known limitations / planned follow-up
- Real inbox browser rendering remains unverified; live backend Gmail import succeeded.
- No SMTP, email verification, account recovery UI/token workflow, external calendar sync or additional mail providers.
- Private VM deployment is running; public HTTPS/Cloudflare and automatic VM updates remain pending. Hosted CI/GHCR publication is not yet verified.
- No automated backup/offsite job yet; do not store irreplaceable mail without implementing operations backup.
- Mail offset pagination, periodic retry (no exponential backoff), no source deletion reconciliation, no automatic retention, no antivirus/CID preview, no orphan cleanup.
- Session restart logout; local HTTP only. Migration/runtime SQL credentials not yet separated.
- Category edits have no optimistic version. Mail polling remains single-process; do not run multiple backend instances.
- Flyway emits a MariaDB 11.8 compatibility warning (latest verified version in its metadata 11.7); all migration/integration checks passed against the exact runtime DB version 11.8.8.
- Browser desktop/mobile visual QA remains unverified because in-app browser permission was denied; component and API tests cover behavior.

## Next-agent starting points
1. Read AGENTS and architecture before edits.
2. Use tests and docs/TESTING rather than the development schema for verification.
3. Complete an authorized browser visual pass when available; record actual evidence here. Live Gmail connectivity is already verified.
4. Update API and sibling frontend contracts together when adding features.

## Local Gmail configuration — 2026-10-03
- Added optional allowlisted `.env.mail.local` loading to the development launcher, with process environment precedence and repository-relative file/storage paths. The test launcher skips this file.
- Configured the shared account locally; the App Password is in ignored `.secrets/gmail-app-password`, under a Windows directory restricted to the current user and SYSTEM. No credentials are included in tracked configuration or documentation.
- Restarted the backend successfully against existing V1–V4 migrations. Live scheduled Gmail IMAP import succeeded: 3 messages, no account error, zero durable message failures. Only operational metadata/counts were inspected.
- Default polling is 60 seconds with a 90-day initial import window. Source defaults remain disabled for other checkouts.
- A later scheduled pass also succeeded with the count remaining at 3 and zero failures. PowerShell launcher syntax validation passed; Git ignores both local configuration and the password file. Password-file ACL identities were checked without reading its contents (current user and SYSTEM only).

## Community and login verification — 2026-10-04
- The refreshed backend was started against the persistent development database. Flyway applied V5–V7 and validated all seven migrations.
- A local HTTP smoke check through the frontend proxy logged in with the existing bootstrap admin, verified `/dashboard`, `/documents`, `/calendar/events`, `/links`, and both audit views (all HTTP 200), then logged out (204) and confirmed the session was rejected afterward (401). Credentials were not printed.
- The revoked-session recovery regression is covered: a stale session can obtain a fresh CSRF token and sign in again on public auth/config routes, while private routes still return 401.

## Private VM deployment — 2026-10-05

- Canonical configuration is versioned in db/deploy; the old _deployment-draft is superseded. Backend and frontend images were built on the Ubuntu 22.04.5 VM with Docker Engine 29.8.2 and Compose 5.6.0.
- MariaDB 11.8.8, backend and frontend are healthy. Caddy serves the SPA and proxies /api from 127.0.0.1:8088; access is through SSH forwarding. No public tunnel is configured.
- Persistent ext4 disk mounted at /srv/bmepilots contains MariaDB, documents, attachments and rolling logs. Docker has a RequiresMountsFor dependency; startup checks mount presence. Existing development DB3307 was not touched.
- VM gateway checks passed: SPA/deep links, anonymous rejection, CSRF/login, authenticated dashboard/community/admin routes, upload/comment creation, logout rejection. Database metadata, comments and exact file bytes survived forced recreation of all three containers; only the test post was then removed.
- Fresh per-VM random secrets were generated without printing passwords. Non-root backend storage ownership and group-readable secret permissions were verified by successful startup/upload. VM Gmail is disabled; local development Gmail settings were not copied.
- A coordinated local backup stopped backend writes, captured MariaDB plus both file stores and image references, and restarted the existing backend. SHA256, gzip and tar integrity passed. Full restore rehearsal, scheduling and encrypted offsite copies are not yet implemented.
- CI workflows passed actionlint 1.7.12/ShellCheck locally. GitHub-hosted execution and image publication remain unverified; the VM currently runs source-built images tagged vm-20261005, not registry images.
- Spotless and documented Maven verify rerun on Java 21: 12 tests, zero failures/errors/skips; isolated MariaDB3308 removed afterward. V1-V7 validated.
