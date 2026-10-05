# Activity and operational logging

## Two views of activity
`GET /api/v1/admin/audit-log?page=0` shows named activity: login success/failure/throttling, logout, registration requests, session revocation, membership and role/password/settings changes, announcements, document posts/files/comments, calendar events, links/categories, mail state and mail operations. The UI shows actor display name, timestamp, action and affected entity. Original authorship stays on community content even when an admin edits it.

`GET /api/v1/admin/audit-log?requests=true&page=0` shows API requests separately: HTTP method, Spring route template, response status and elapsed milliseconds. This includes reads, failed validation, denied access and downloads. Routes rejected before controller mapping use a fixed unmatched label; authentication routes have explicit safe labels. The API returns 50 entries per page, newest first. The frontend displays full timestamps in Europe/Budapest and refreshes every 30 seconds.

## Privacy boundary
Never record passwords, Gmail credentials, cookies, CSRF tokens, request/response bodies, query strings, uploaded bytes, email bodies or document/comment text. Failed logins are anonymous activity; the submitted email/password is not logged. Actor IDs reference the authenticated user; an anonymous/system actor is explicitly labeled. API route templates omit user-supplied resource IDs and search strings. Domain entries separately reference opaque entity IDs for attribution.

## Durability and failure behavior
Domain writes and their audit entries share a database transaction. Operational request logging happens after the handler, including rejected requests, and is best effort: if the database cannot store it, a safe error is written to the operational log without changing the original API result. Login success is recorded before the session is established; logout records its supplied authenticated principal after invalidation. Idle container timeout without another request cannot identify a logout event, and process crashes may prevent recording a final request. No audit edit/delete endpoint exists. The runtime DB account still shares migration privileges, so SQL-level immutability is deferred until deployment hardening.

## Staged document activity
Staged document uploads record `DOCUMENT_UPLOAD_STAGED` and `DOCUMENT_UPLOAD_DISCARDED` with the uploading actor. The expiry scheduler records `DOCUMENT_UPLOAD_EXPIRED` with a system actor. Publication records the existing `DOCUMENT_FILE_UPLOADED` actions in the same transaction as the post. Private upload contents and filenames are never audit payloads.

## Files and retention
Operational logs default to ignored `logs/portal.log` (`APP_LOG_FILE` override). Rotation uses 10 MB files, 14 days of history and a 200 MB archive cap. Request metadata also appears in that log. Database audit entries currently have no automatic purge; API traffic grows this table. Define a retention/export policy before production. Backup the audit table with the rest of MariaDB; never commit logs or dumps. Log output is intentionally metadata-only, and generic exception reports include an opaque reference and exception type rather than sensitive payloads.

## Extension rule
Add a descriptive domain action to every new mutating service and verify it shares the service transaction. HTTP metadata comes from RequestAuditFilter automatically. Include meaningful audit assertions in integration tests; do not test by logging real credentials or personal message contents.
