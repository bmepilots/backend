# Verification guide

Updated: 2026-10-09. This document describes checks and test coverage; actual execution results belong in `STATUS.md`.

## Dedicated real MariaDB tests
From this repository: `pwsh -File scripts/test.ps1`. It reads the private sibling db `.env` without printing credentials, starts `db/compose.test.yml` and runs Maven verify. This creates a separate tmpfs MariaDB at 127.0.0.1:3308, database/user bmepilots_test. It never points at the persistent development volume on 3307. Tests use a real embedded HTTP server on a random port and Java HttpClient cookie jars. Test-only passwords in source apply only to this disposable localhost database.

For non-PowerShell environments: start `docker compose -f compose.test.yml up -d --wait` in db, set TEST_DB_PASSWORD to that database's application password, then `./mvnw verify`. Stop/remove the disposable container afterward with `docker compose -f compose.test.yml down`; the tmpfs test data is intentionally ephemeral.

## Coverage
- Anonymous API rejection and missing-CSRF rejection.
- Bootstrap admin, last-active-admin guard, registration toggle and pending login rejection.
- Approval -> member login -> admin endpoint denial.
- Argon2 storage, content CRUD, stale version rejection, category FK protection, unsafe URL rejection.
- Suspension invalidates an already logged-in user's session.
- Audit events and logout invalidation.
- Email sanitization and attachment path traversal/atomic storage.
- Multipart MIME body/attachment extraction and nesting limits.
- Repeated mail import, UIDVALIDITY reset with provider-ID deduplication, preserved per-user flags, unchanged cursor on connection failure and durable message failure records.
- Anonymous attachment denial, message/attachment relation checking and forced download headers.
- Partial mail-state PATCH preserves the other flag and private state across repeated imports.
- Shared document posts with 1–5 files, author attribution, peer comments, author/admin moderation, stale versions and post/file/comment association checks.
- Exact 50 MiB upload acceptance; oversized, empty and six-file rejection; stream-level byte counting, storage traversal prevention and rollback cleanup.
- Staged per-file uploads: private references, five-file atomic publication/order, owner-only publish/discard including admin isolation, 24-hour expiration, ten-pending-file quota, exact 50 MiB/oversize boundaries, single-file requests and CSRF.
- Failed staged publication preserves valid references and creates no partial post/audit. Cleanup removes expired metadata and old crash/partial files while retaining published, active staged and recently created files.
- Community link and calendar contributions, nonowner/admin permissions, preserved authorship, safe links, CSRF and audit actors.
- Calendar range overlap, exclusive all-day ends, null-end deadlines and Budapest civil-time preservation across a daylight-saving transition.
- Named login/logout activity and separate request metadata, including denied requests and excluding raw query strings/credential identifiers.
- Administrator password reset: server-side role and CSRF protection; required/nonnegative optimistic version; password length/blank validation; stale/missing account rejection; Argon2 storage; successful actor/target audit without credentials; old-password rejection, new-password login, revocation of multiple target sessions and preservation of the unrelated administrator session.
- Self-reset requires a new login, while reset of pending/suspended/disabled/rejected accounts leaves their lifecycle status unchanged and does not manufacture a successful sign-in.
- `lastLoginAt` is null without successful history, advances only on successful active login, appears in admin responses and leaves the optimistic version unchanged. Failed attempts, reading the session and resetting a password preserve the previous date.
- Deterministic concurrent HTTP tests pause real password verification: a reset that wins during login rejects the old credentials without login history; a reset that wins during self-service password change cannot be overwritten. The encoder remains the real password implementation, with a spy latch only around the comparison timing.
- The exact V9 SQL migration runs against connection-local temporary V8-shaped tables in MariaDB, verifying latest successful actor/target backfill, exclusion of failed/unrelated activity and null history without touching application tables.

`SecurityContentIntegrationTest`, `AdminPasswordIntegrationTest`, `DocumentsIntegrationTest` and `CommunityIntegrationTest` run against real HTTP and the dedicated MariaDB. Document tests use isolated temporary file storage and synthetic byte streams; no real shared files or Gmail contents are needed. Administrator password tests use synthetic fixture accounts only and disable fixture administrators after each test to preserve other suites' last-admin assertions. The API integration suite also checks legacy knowledge category write retirement and authenticated dashboard access; inspect individual test assertions when extending these contracts.

Actual run outcomes belong in STATUS. Unit tests may be run alone with `./mvnw -Dtest=ContentSafetyTest test` without Docker, but that does not replace integration verification. A successful local fixture test does not prove real Gmail connectivity.

## Manual checks still required
Browser desktop/mobile visual QA and accessibility keyboard navigation remain distinct from API tests. With an authorized browser session, check login redirect, successful-open mail read state, manual unread, five-file document upload, comments, member/admin controls, calendar month/agenda navigation and link contribution using disposable content. Do not expose credentials or real mail in screenshots/logs.

For administrator password recovery, use disposable accounts: verify the Users list's Budapest sign-in time and empty-history label, opening/canceling a target-specific reset form, password confirmation and validation, success/error notices, stale-form recovery and keyboard focus. Verify self-reset clears the authenticated UI and returns to sign-in. Never reset a real community member's password solely for testing.

Live Gmail connectivity and the first local import were verified on 2026-10-03; that evidence is recorded in STATUS and MAIL. A new machine needs its own operator-provided configuration and connectivity check. Production security and a restore rehearsal covering MariaDB plus both private file stores remain required when deployment is requested.
