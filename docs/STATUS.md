# Backend status and handoff

Updated: 2026-10-05. Keep this current with every implementation change.

## Implemented in the initial application pass
- Java 21 / Spring Boot 4.0.8 project with Maven Wrapper and feature packages.
- Eight Flyway migrations: identity, content, mail, durable mail failures, Shared Documents, calendar/community links, request-audit metadata and staged document uploads.
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
- No SMTP, email verification, public self-service recovery token workflow, external calendar sync or additional mail providers. Administrator-set password recovery was added on 2026-10-09 as recorded below.
- Private VM deployment is running; public HTTPS/Cloudflare and automatic VM updates remain pending. Hosted CI and GHCR publication succeeded on 2026-10-05.
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
- Fresh per-VM random secrets were generated without printing passwords. Non-root backend storage ownership and group-readable secret permissions were verified by successful startup/upload. VM Gmail was initially disabled because local development secrets were not part of the source transfer; it was subsequently enabled and verified as recorded below.
- A coordinated local backup stopped backend writes, captured MariaDB plus both file stores and image references, and restarted the existing backend. SHA256, gzip and tar integrity passed. Full restore rehearsal, scheduling and encrypted offsite copies are not yet implemented.
- CI workflows passed actionlint 1.7.12/ShellCheck locally. GitHub-hosted execution and image publication subsequently succeeded; the VM currently runs source-built images tagged vm-20261005, not registry images.
- Spotless and documented Maven verify rerun on Java 21: 12 tests, zero failures/errors/skips; isolated MariaDB3308 removed afterward. V1-V7 validated.

- Hosted verification and GHCR publication succeeded: [backend run](https://github.com/bmepilots/backend/actions/runs/37312796111), [frontend run](https://github.com/bmepilots/frontend/actions/runs/37312807646). The VM remains on the verified source-built image pair; image publication alone does not roll out a new version. All three repositories were pushed successfully.

## Public deployment preparation and staged uploads — 2026-10-05

- Added V8 and owner-only single-file staging: POST `/documents/uploads`, DELETE `/documents/uploads/{id}`, and JSON POST `/documents` with ordered `uploadIds`. Existing multipart clients remain supported. Five files of up to 50 MiB each can now be published without one oversized proxy request.
- Stages expire after 24 hours and have a ten-active-reference quota per member. A per-user database lock serializes creation/consumption; atomic publication retains valid references on rollback. Administrators cannot use another member's unpublished uploads. Metadata, comments, authorship and moderation contracts remain unchanged.
- Hourly cleanup removes expired uploads with system audit events and reconciles UUID/partial files older than 48 hours against both committed staging and published metadata. Recent, referenced and unknown files are preserved. A lost publication response requires checking the document list before starting another upload; no idempotency-key contract is claimed.
- Added optional separate Flyway connection credentials with datasource fallback and `FLYWAY_PASSWORD_FILE` entrypoint support. Forwarded headers explicitly default to `none`; the public deployment can opt into native processing only with its fixed trusted Caddy proxy. Runtime/migration grants and public proxy behavior still require host-side verification.
- Backend Docker images declare API contracts `1,2` for compatible paired rollouts; the server updater must inspect the frontend requirement before promotion.
- Java 21 compilation and final Spotless check succeeded. The documented dedicated MariaDB3308 Maven verify passed: **15 tests, zero failures, errors or skips**. V1–V8 applied from an empty schema, and repeated startup validated all eight. New real-HTTP tests cover private staging, ordered five-file publication, maximum-size/oversize uploads, quota, ownership, CSRF, rollback, expiration and orphan cleanup. The disposable test container was removed afterward; no development DB3307 data was used.
- Gmail on the Ubuntu VM is now enabled. The operator credential was installed outside Git with `root:10001` ownership/mode0640 and the backend recreated successfully. Authenticated connection test returned204; last successful import was `2026-10-05T13:36:37` UTC, account error null and durable failed-message count zero. No secret contents were printed. See MAIL for the configuration and initial-disabled explanation.
- This entry records implementation and local tests, not a new hosted CI run or public rollout. Record subsequent CI/image/deployment evidence after it actually completes.

## Live VM activation — 2026-10-05 evening

- New backend `7d155fef44438e6da6fab1d0ada46a8a9e02c49b` and frontend `e0c8bdeb94182629bf6f50f54937d6ac7de07b0c` were built and deployed on the VM. Hosted CI and GHCR publication also succeeded: [backend run](https://github.com/bmepilots/backend/actions/runs/37352812488), [frontend run](https://github.com/bmepilots/frontend/actions/runs/37352581856). The running pair currently uses local source-built images recorded in private release.env; registry rollout is not yet activated.
- Flyway V1–V8 are successful. Separate runtime DML and migration connections are active; the runtime account identity/grants were checked. Gmail continues to synchronize successfully with no durable failures after the deployment.
- The staged-upload smoke check passed through the VM gateway: anonymous rejection, CSRF/login, protected routes, per-file upload followed by JSON publication, comment, container recreation, exact downloaded bytes, deletion of only the test post, and logout rejection.
- Public Compose mode is active. MariaDB, backend, Caddy and the pinned cloudflared connector are all healthy; Docker reports no published host ports for any of them. Session cookie flags Secure, HttpOnly and SameSite=Lax were verified. The tunnel is connected, while domain DNS routing and the external HTTPS/login check remain pending user account configuration at this point. A healthy connector alone is not a public-login test.
- Daily local backups are enabled for 03:15 Europe/Budapest plus up to five minutes of jitter. Backup 20261005T175514Z passed an isolated full SQL/application restore, three referenced-file checks and an exact document download check; temporary resources and the production smoke post were removed. Encrypted offsite backup and external alert delivery remain unconfigured.
- Installed update service/timer and 13 operation safety tests passed on Ubuntu, but the update timer stays disabled until GHCR read access is supplied and one registry update succeeds. The root-owned operation directory/lock were hardened. Infrastructure scripts and database engine versions require explicit reviewed installation; only application images follow successful main workflows automatically once enabled.

## Dependency security patch — 2026-10-05

- GitHub reported six open dependency alerts on the deployed manifest. Upgraded Bouncy Castle `bcprov-jdk18on` from 1.83 to 1.85 and jsoup from 1.22.1 to 1.23.1, meeting the patched versions for all six reported advisories. This is dependency remediation, not a claim that every vulnerable code path was reachable through this application.
- References: [Bouncy Castle nesting guard](https://github.com/advisories/GHSA-qp49-qgx5-5m26), [name constraints](https://github.com/advisories/GHSA-9pwp-9qqc-pr26), [GOST counter reuse](https://github.com/advisories/GHSA-574f-3g2m-x479), [timing channel](https://github.com/advisories/GHSA-p93r-85wp-75v3), [LDAP injection](https://github.com/advisories/GHSA-c3fc-8qff-9hwx), [jsoup cleaner](https://github.com/advisories/GHSA-pmhh-3w7g-xqp8).
- Final Spotless check and the complete Java 21/MariaDB integration verification passed again: 15 tests, zero failures/errors/skips. The disposable test DB was removed; development data was untouched. Existing tests exercise password authentication, HTML sanitation, mail fixtures and staged upload behavior with the new dependencies. Hosted publication and VM activation require subsequent evidence.

## Registry rollout and public HTTPS verification — 2026-10-05

- The owner made the backend/frontend GHCR packages public. Anonymous pulls and the updater's immutable-digest, OCI revision and API compatibility checks succeeded; no GitHub credential was installed on the VM.
- Backend dependency patch 7660284d366032c769c2305d0e3481f0cf0f29b3 passed [hosted verification and publication](https://github.com/bmepilots/backend/actions/runs/37354712297). GitHub's authenticated Dependabot query subsequently returned zero open backend dependency alerts. The patch updates Bouncy Castle to 1.85 and jsoup to 1.23.1; this is not a general security-audit claim.
- The actual updater completed a coordinated backup, promoted the compatible registry image pair, and passed health/SPA/proxied-CSRF checks. The update timer is now enabled (five minutes after each run plus up to 30 seconds jitter). The persisted release.env/update-state.json are the running-image authority; source build tags are no longer active.
- Cloudflare routing is active: public HTTPS returned the BME Pilots login page and application configuration, and plain HTTP redirected to HTTPS with 301. Session cookies have Secure, HttpOnly and SameSite=Lax. Public HTTPS authentication, protected APIs, staged upload, JSON publication, comments and logout passed through the real tunnel with certificate validation. This API/transport check is distinct from browser visual testing.
- The workstation router DNS at 192.168.0.1 still cached a negative response temporarily, while Cloudflare/Google public resolvers returned the correct edge addresses. During that cache window the operator test explicitly used a freshly resolved Cloudflare edge IP while preserving the real hostname, HTTPS SNI and certificate verification. No hosts-file or persistent DNS setting was changed. Direct home-router WAN IP access is not the application route.
- Final public persistence check passed after recreating the registry-backed backend: exact staged-document bytes/comments survived, the operator test post was deleted, and logout invalidated the session. A new backup of the patched V8 deployment also passed an isolated restore (SQL/Flyway, login/protected APIs, two referenced mail files; no document remained in that snapshot after test cleanup).
- The router's negative DNS cache expired. A normal HTTPS request from the workstation, using its unchanged system DNS and no address override, returned 200 for /login. Cloudflare configuration and local hostname resolution are both verified. The smoke client identifies itself as BMEPilotsDeploymentCheck/1.0; the generic Python user agent had been rejected by the edge.

## Administrator password recovery and successful login history — 2026-10-09

- Added ADMIN/CSRF-protected `PUT /api/v1/admin/users/{id}/password` with required nonblank 12–128-character `newPassword` and required nonnull nonnegative optimistic `version`. Returns the updated safe user view; missing targets return 404 and stale edits 409. Resets apply to any existing account, including the administrator's own, without changing role/status. No email, reset-token or forced-next-change workflow is implied.
- The existing Argon2 encoder stores the replacement. Password reset atomically increments the user/auth versions and records `PASSWORD_RESET` with actor and target IDs; passwords/hashes never appear in responses or audit records. Every old target session fails the next protected request; unrelated administrator sessions remain valid. Self-reset therefore requires signing in again.
- New immutable Flyway V9 adds nullable UTC `last_login_at`. It backfills only the latest existing `LOGIN_SUCCEEDED` USER event whose actor matches its target, leaving missing history null. All safe user views include `lastLoginAt`. Successful active authentication updates this timestamp together with its named audit event and does not increment the optimistic version. Failures, inactive attempts, session reads and resets leave the timestamp unchanged.
- Authentication and self-service password changes compare the credential snapshot's auth version before writing, preventing an administrator reset from being overwritten or an obsolete login from being accepted while verification is in flight. Authentication, self-service change and administrator reset explicitly use `READ_COMMITTED`; the first deterministic race tests exposed MariaDB 11.8 SQL1020 under default `REPEATABLE_READ`, which otherwise surfaced as an unintended 500. With the explicit isolation the races now return the specified 401/409 without stale-credential retries.
- The backend image now advertises API contracts `1,2,3`; the coordinated new frontend requires `3`. Existing contracts remain supported. README, API, architecture, audit and testing documents describe the endpoint, migration, date semantics, session revocation, concurrency and remaining limitations.
- Verification: `pwsh -File scripts/test.ps1` passed on Java 21.0.11 against the dedicated Docker MariaDB 11.8.8 at loopback3308: **23 tests, zero failures, errors or skips**, packaged application JAR produced. Final `./mvnw.cmd --no-transfer-progress spotless:check` passed as well. Eight new real-HTTP/database tests cover role/CSRF/validation boundaries, stale/missing targets, stored hashes, old/new credentials, multiple-session revocation, self-reset, inactive accounts, private audit data, timestamp semantics and deterministic credential races. The exact V9 SQL also passed historical backfill assertions against connection-local temporary tables; the ordinary application schema validated V1–V9. No persistent development database3307 or production accounts were used.
- The disposable test database was intentionally left running for the parent task's separate browser QA. Browser/production rollout and hosted-CI evidence must be appended only after those checks complete; this entry records backend implementation and local API verification.
- Final isolated desktop browser QA passed with the real backend on8081/test MariaDB3308 and frontend5174: member reset, confirmation validation, Budapest last-sign-in/null display, self-reset notice/redirect and login with the new password. Test services and the disposable database were removed afterward; persistent development data and production passwords were untouched.
- Backend commit `174c1b44caea69cd119833dead7bd354ef89d6a0` passed [hosted CI and image publication](https://github.com/bmepilots/backend/actions/runs/37868601240). It was picked up automatically by the enabled VM updater. Live inspection confirmed successful Flyway V9 and a healthy backend supporting API contracts `1,2,3`.
- The paired frontend `5cfc66b7a92d20c31adb285db77d657da3a5065d` passed [hosted CI](https://github.com/bmepilots/frontend/actions/runs/37910463507). After its normal settling interval, the existing guarded update service was started once to avoid waiting for the next timer tick; it completed successfully at2026-10-09 09:23:01 UTC (11:23 Budapest), including the coordinated backup and health checks. All four production containers are healthy. Public HTTPS returned200 and the new admin asset contains both reset controls and the last-sign-in column. The periodic updater remains enabled; no production account was reset as a deployment test.
