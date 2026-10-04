# Backend architecture and decisions

## Product boundary
Private, unofficial student community portal for BME Professional Pilot, class of 2026. No Neptun, Moodle or university integrations. Frontend language English; source and operational documentation English for agent navigation. The intended future domain is bmepilots2026.com but is not configured.

## Technical baseline
Java 21, Spring Boot 4.0.8, Spring MVC/Security/JDBC/Validation, Flyway and MariaDB 11.8.8. One process and one database; no queues, microservices, Redis or external search engine. Maven Wrapper pins Maven. Dependencies are pinned or managed by the Spring Boot BOM.

## Modules and allowed dependencies
- auth: login/register endpoints, bootstrap. Calls user application services.
- user: credentials, lifecycle, roles, session validation. Calls settings and audit.
- settings: singleton nonsecret configuration. Calls audit.
- announcement: administrator content CRUD; calls audit. knowledge: historical read-only archive after V5.
- documents: community posts, private file storage, comments, author/admin moderation; calls audit.
- calendar, links: member contributions and author/admin mutations; call audit. Calendar dates are local Europe/Budapest wall times, unlike UTC audit timestamps.
- mail: read API, local state, scheduling and provider coordination; calls audit. MailProvider is the provider boundary.
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

Authentication throttles per remote IP and normalized email in bounded in-memory windows. This is single-instance development behavior. Do not trust forwarded client-IP headers without explicit future proxy trust configuration. Validation limits request field sizes. Cookie sessions and CSRF do not by themselves defend a compromised browser.

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

Creation/update/audit/mail timestamps use UTC LocalDateTime for DATETIME(6); the frontend interprets these as UTC. Calendar startsAt/endsAt are the explicit exception: Europe/Budapest local wall times with no UTC conversion, and exclusive all-day end dates. Input emails are trimmed/lowercased for identity (no Gmail-specific canonicalization). UTF8MB4 text; FK relations prevent invalid references. Cursor state is co-located with mail_folders rather than a separate table. Sender fields are optimized on the message; recipients remain a one-to-many table.

Application services use parameterized SQL. The only dynamic SQL fragments are developer-controlled optional clauses, never user-provided table/column names. Search escapes LIKE wildcard input and caps lengths/pages. Lists have server-side limits. Current mail pagination is offset-based; cursor pagination is a documented future improvement.

## Audit and transaction boundaries
Content/user/settings mutations and their audit rows share transactions. Audit contains actor ID, action, entity type/id and timestamp, never passwords/message bodies. Actor can be NULL for bootstrap/system activity. There is no audit-edit API. The local DB account still has broad privileges for Flyway: SQL-level append-only audit enforcement and separate runtime/migration users are production follow-ups.

## Extension recipe
Add a domain package; define DTOs and service ownership; add a new migration if necessary; enforce admin paths or service ownership checks; write behavior tests and document endpoints. Compose dashboard data using application services. For new external providers implement MailProvider without importing provider classes into controllers. Community writes must validate ownership server-side, preserve original authorship and include transactional audit entries.

## Security boundaries
The request audit filter surrounds session validation, CSRF, authorization and controllers. It records API route templates, actor IDs, status and duration without request payloads or query strings. Login/logout/registration and session revocation also have named activity entries. See `AUDIT.md` for coverage, operational file rotation and the distinction between transactional domain audit and best-effort request logging.

All /api/v1/admin/** requires ADMIN; all other private APIs require active authenticated membership. Unknown routes denied. API responses use Spring Security cache-control and security headers. Email body HTML is sanitized before persistence and displayed in a sandboxed client iframe. Attachment bytes live outside the web root with random keys; downloads verify message/attachment relation and force application/octet-stream plus attachment disposition. Deployment TLS, full page CSP and network-facing rate limits are deferred with deployment.
