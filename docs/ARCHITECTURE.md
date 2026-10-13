# Backend architecture and decisions

## Product boundary
Private, unofficial student community portal for BME Professional Pilot, class of 2026. No Neptun, Moodle or university integrations. Frontend language English; source and operational documentation English for agent navigation. The public deployment uses bmepilots2026.com through Cloudflare Tunnel; dated verification evidence is in STATUS.

## Technical baseline
Java 21, Spring Boot 4.0.8, Spring MVC/Security/JDBC/Validation, Flyway and MariaDB 11.8.8. One process and one database; no external queue broker, microservices, Redis or external search engine. Mail forwarding uses a transactional MariaDB outbox. Maven Wrapper pins Maven. Dependencies are pinned or managed by the Spring Boot BOM.

## Container deployment boundary
The canonical full-stack Compose configuration is versioned in `../db/deploy`; the earlier unversioned deployment draft is superseded. The backend image uses a Java 21 build stage and a JRE runtime with non-root UID/GID `10001`. `SERVER_ADDRESS` defaults to loopback in application configuration and is set to `0.0.0.0` in Docker. The entrypoint supports file-backed database and bootstrap passwords; the mail provider reads its own password file. Secrets remain private host files mounted under `/run/secrets`, outside source, build context and logs.

The frontend Caddy container owns the same-origin HTTP boundary and proxies `/api/*` to `backend:8080`. Backend and MariaDB publish no host ports. The backend joins separate internal API/database networks plus an egress network for Gmail IMAP and SMTP. Bind-mounted documents, imported attachments and logs are writable by UID `10001`; MariaDB has its own persistent directory. The whole data set lives under the mounted `/srv/bmepilots` disk. Preparation configures Docker to require the mount, and the start script checks it before launching health-ordered services.

The active public stack publishes no ports and uses Secure cookies and explicitly restricted proxy trust. The optional `compose.loopback.yml` is retained for private HTTP previews only; it must not accompany public tunnel mode. Cloudflare, compatible-image updates, daily local backups and separate runtime/migration database credentials have dated deployment verification in STATUS. Encrypted offsite backup remains an operational follow-up. Record new deployment evidence separately from source configuration.

## Modules and allowed dependencies
- auth: login/register endpoints, bootstrap. Calls user application services.
- user: credentials, lifecycle, roles, session validation. Calls settings and audit.
- settings: singleton nonsecret configuration. Calls audit.
- announcement: administrator content CRUD; calls audit. knowledge: historical read-only archive after V5.
- documents: community posts, private file storage, comments, author/admin moderation; calls audit.
- calendar, links: member contributions and author/admin mutations; call audit. Calendar dates are local Europe/Budapest wall times, unlike UTC audit timestamps.
- mail: read API, local state, scheduling, durable forwarding and provider coordination; calls audit. MailProvider is the incoming boundary and MailDelivery is the outgoing boundary.
- dashboard: read composition through domain application services; never accesses another module's SQL directly.
- audit: append/read activity; no business workflow.
- common: security, error translation and principal utilities. It must not grow into a generic business-service bucket.

Controllers contain HTTP mappings and validated request records. Application services own transactions and rules. Simple domains currently use Spring JdbcClient directly in application services; extract an infrastructure repository when persistence complexity makes it useful. Do not introduce empty layers or interfaces per class. Mail already has infrastructure adapters because it crosses external boundaries.

## ADR 001: explicit SQL instead of JPA
The initial design mentioned JPA as an option. This implementation uses Spring JDBC to keep schema, joins, concurrency and data returned to the browser explicit. It avoids lazy-loading/serialization surprises and leaves Flyway the sole schema owner. The requested stack did not mandate an ORM. Introduce repositories per domain rather than a global repository if services need separation later.

## ADR 002: UUID storage
Business IDs are UUID strings stored as CHAR(36) with ASCII binary collation instead of the initially proposed BINARY(16). This costs modest space for a small community while making SQL inspection, JDBC records and cross-repo integration straightforward. Never assume UUID unpredictability replaces authorization. Role codes are small stable natural keys; audit/recipient rows use BIGINT IDs.

## ADR 003: sessions and CSRF
Server-side HttpSession with HttpOnly BMESESSION cookie; SameSite=Lax. Secure is false only for localhost HTTP. Session fixation protection is explicit on login (old session invalidated, new context saved). CSRF uses HttpSessionCsrfTokenRepository; the frontend gets a token from /auth/csrf and sends it in the supplied header. Login rotates session/token; frontend fetches a new token afterward. Logout is Spring Security's POST endpoint and requires CSRF. No browser JWT storage.

Each protected request validates ACTIVE status and auth_version against the DB and enforces an absolute 12h session age. Idle timeout is 60m. Role/status/password changes increment auth_version; old sessions stop working. Memory sessions are intentionally lost on restart.

Successful authentication updates `users.last_login_at` and appends `LOGIN_SUCCEEDED` in one service transaction before the controller establishes the session. After password verification the update checks ACTIVE status and the previously read `auth_version`, so a concurrent completed reset/status/role change rejects authentication with the old credential snapshot. Normal session checks and unsuccessful attempts do not update the timestamp. Login does not change `version` or `updated_at`, so genuine sign-ins do not make administrator forms stale. As with the existing login event, an interruption between the committed authentication transaction and HTTP/session completion can leave evidence of successful authentication without a completed browser session.

Administrator password resets require the latest optimistic user `version`, hash the replacement with the existing encoder, increment `version` and `auth_version`, and record `PASSWORD_RESET` transactionally. The route uses the existing ADMIN and CSRF boundary; neither the target's current password nor a status transition is required. All target sessions, including the acting session on self-reset, fail the next protected-request validation. Other sessions remain valid. Self-service password change also compares the auth version read with the old password hash: an in-flight change cannot overwrite a concurrent administrator reset after verifying an obsolete password. Request filters cannot undo work that was already executing when a session was revoked; these credential mutations therefore enforce their own database concurrency checks.

These three credential transactions explicitly use `READ_COMMITTED`. The conditional `auth_version`/`version` writes remain the authoritative concurrency guard. Real MariaDB 11.8 tests showed that default `REPEATABLE_READ` could raise SQL error 1020 when the password snapshot changed, producing an unintended HTTP 500 instead of the specified login 401 or mutation 409. Do not remove the explicit isolation or blindly retry a stale credential operation. The deterministic concurrent HTTP regressions cover this behavior.

Authentication throttles per remote IP and normalized email in bounded in-memory windows. This is single-instance development behavior. Do not trust forwarded client-IP headers without explicit future proxy trust configuration. Validation limits request field sizes. Cookie sessions and CSRF do not by themselves defend a compromised browser.

`SERVER_FORWARD_HEADERS_STRATEGY` defaults explicitly to `none`. The public deployment may enable native Tomcat forwarding with `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES=172[.]30[.]27[.]2`, the dedicated Caddy IP. Its gateway must accept client identity only from the dedicated cloudflared address and overwrite untrusted incoming forwarding headers. This keeps the existing limiter on the verified client IP instead of collapsing all users onto the proxy IP. Do not enable `framework` forwarding or broad trust on a directly exposed backend. The backend remains unpublished; public trust configuration is owned by `db/deploy`.

## User lifecycle
Registration -> PENDING_APPROVAL/USER. Allowed transitions: pending -> active/rejected; active -> suspended/disabled; suspended -> active/disabled; disabled -> active. Rejected registrations are not automatically reactivated. Roles are USER or ADMIN, one effective role per user, represented by roles/user_roles for future extension. ADMIN can read member content and use /admin/**. The roles ADMIN row is locked for privileged lifecycle changes to serialize the last-active-admin guard.

Mutations use version fields for users, settings, announcements, documents, comments, calendar entries and links. Stale updates/deletes return 409. Categories currently use last-write-wins names/order; deletion is prevented by foreign keys if referenced. No hard user deletion. Permission-level access and per-mailbox membership are deferred. A revoked session is invalidated before processing a private request (401); public auth/config routes can then proceed anonymously so a fresh CSRF token and login remain usable.

## Schema ownership and migrations
- V1: users, roles, user_roles, app_settings, audit_log.
- V2: announcements, knowledge_categories/articles, link_categories/useful_links.
- V3: mail_accounts, mail_folders, mail_messages, mail_message_locations, addresses, attachments, user_mail_state.
- V4: durable per-message sync failures.
- V5: document posts/files/comments and non-destructive import of existing knowledge articles.
- V6: calendar entries, link authorship and a starter General link category when needed.
- V7: API request method, route template, status and duration in the audit log.
- V8: private document staging references, owner, expiration and file metadata.
- V9: nullable `users.last_login_at` with historical backfill from the maximum matching `LOGIN_SUCCEEDED` USER audit timestamp, requiring the actor and target IDs to agree. Missing history stays null; registration and failed attempts are never substituted. The migration changes no account versions or passwords.
- V10: administrator-controlled forwarding settings, enable timestamp/version and a durable per-message/member delivery outbox. Defaults OFF with no historical delivery backfill.

Creation/update/audit/mail timestamps use UTC LocalDateTime for DATETIME(6); the frontend interprets these as UTC. Calendar startsAt/endsAt are the explicit exception: Europe/Budapest local wall times with no UTC conversion, and exclusive all-day end dates. Input emails are trimmed/lowercased for identity (no Gmail-specific canonicalization). UTF8MB4 text; FK relations prevent invalid references. Cursor state is co-located with mail_folders rather than a separate table. Sender fields are optimized on the message; recipients remain a one-to-many table.

Application services use parameterized SQL. The only dynamic SQL fragments are developer-controlled optional clauses, never user-provided table/column names. Search escapes LIKE wildcard input and caps lengths/pages. Lists have server-side limits. Current mail pagination is offset-based; cursor pagination is a documented future improvement.

Flyway accepts separate `FLYWAY_URL`, `FLYWAY_USER` and `FLYWAY_PASSWORD` (or container `FLYWAY_PASSWORD_FILE`). Each defaults to the actual corresponding Spring datasource property, preserving both development and isolated integration-test connections. Deployment can run JDBC with DML-only runtime grants while Flyway uses schema-scoped migration privileges. Merely supporting these variables does not prove grants were separated on a host; record that verification in STATUS. Both credentials are available inside the same backend process at startup, so this separates accidental runtime SQL capabilities, not host/process compromise.

New Shared Document clients stage each file privately before one atomic publication. A user-row lock serializes quota and consumption changes; publication locks all selected stages, records the post/files/audit and consumes stage metadata together. No file moves occur during publication. Hourly cleanup expires unused stages, then reconciles generated files older than 48 hours against both staging and published metadata. Keep database and file-store backups together, including stage rows. Legacy multipart creation remains compatible with older clients. Details and invariants are in DOCUMENTS.

## Mail forwarding transaction boundaries

V10 adds singleton `mail_forwarding_settings` (disabled by default, enable cutoff, optimistic version) and `mail_forwarding_outbox` (unique message/user, enable generation, state, attempt count, next attempt and safe error). It is additive and leaves all existing imported mail and user flags untouched. `MailSyncService` calls `MailForwardingService.enqueue` only while persisting a brand-new message, inside the same transaction; files, message metadata, recipient references and the queue therefore cannot publish a partial import. The forwarding service locks its singleton settings row to serialize enqueue/claim/complete with admin toggles.

One backend process dispatches at most one recipient per five-second scheduling pass. Claim commits `SENDING` and its audit event before any network call, then SMTP runs outside the transaction. The result commits separately with a named audit event. Disable cancels pending rows; an in-flight accepted send cannot be recalled. The stored enable generation prevents a pre-acceptance failure from returning to the queue after a disable/re-enable cycle. Restart converts outstanding `SENDING` rows to `UNCERTAIN`, never automatically resending them. One process remains a requirement: startup recovery is intentionally not a multi-instance lease protocol.

Recipients are ACTIVE users at import, rechecked with their current email when claiming. Gmail aliases of the shared mailbox are excluded as recipients. An ordinary incoming message may originate from that mailbox; source eligibility relies on the provider's explicit forwarded-message, automatic-reply and bounce guards, not a blanket sender-address exclusion. Newly activated users join future imports without replaying old mail. Imported content and private attachments are reused; each SMTP envelope and visible To header contains exactly one recipient. The outgoing From is the authenticated mailbox, with original sender/date/subject in the body and the first stored Reply-To (validated, original-sender fallback). Detailed retry, loop prevention and recovery semantics are in MAIL.

## Audit and transaction boundaries
Content/user/settings mutations and their audit rows share transactions. Audit contains actor ID, action, entity type/id and timestamp, never passwords/message bodies. Actor can be NULL for bootstrap/system activity. There is no audit-edit API. Development and the initial VM stack share a database-scoped account with Flyway privileges: SQL-level append-only audit enforcement and separate runtime/migration users remain production follow-ups.

## Extension recipe
Add a domain package; define DTOs and service ownership; add a new migration if necessary; enforce admin paths or service ownership checks; write behavior tests and document endpoints. Compose dashboard data using application services. For new external providers implement MailProvider without importing provider classes into controllers. Community writes must validate ownership server-side, preserve original authorship and include transactional audit entries.

## Security boundaries
The request audit filter surrounds session validation, CSRF, authorization and controllers. It records API route templates, actor IDs, status and duration without request payloads or query strings. Login/logout/registration and session revocation also have named activity entries. See `AUDIT.md` for coverage, operational file rotation and the distinction between transactional domain audit and best-effort request logging.

All /api/v1/admin/** requires ADMIN; all other private APIs require active authenticated membership. Unknown routes denied. API responses use Spring Security cache-control and security headers. Email body HTML is sanitized before persistence and displayed in a sandboxed client iframe. Attachment bytes live outside the web root with random keys; downloads verify message/attachment relation and force application/octet-stream plus attachment disposition. Public TLS and gateway security headers are part of the verified tunnel deployment; review current gateway configuration and STATUS for evidence. Backups coordinate a consistent database dump with both file stores. Local restore rehearsals have passed; encrypted offsite copies remain an operational follow-up. Restoring an old outbox requires the forwarding precautions in MAIL.

## Exam prep — API contract 5

The new Exam prep area provides 38 original engineering exercises, an interactive circuit explorer, Learn/Practice/Mock exam flows and private saved results. Backend Flyway V11 owns study-attempt storage; Docker MariaDB and existing backups are reused. See `STUDY.md` for source analysis, ownership, grading, extension and operational details. Local implementation does not imply deployment; consult STATUS for evidence.
