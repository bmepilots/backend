# REST API contract

Updated: 2026-10-04.

Base `/api/v1`. JSON request/response, UTF-8, except document creation uses multipart and file downloads use binary responses. Authentication is a cookie session. All POST/PATCH/PUT/DELETE requests, including login, registration and multipart uploads, require CSRF. Call `GET /auth/csrf`, preserve its session cookie and send `token` in the returned `headerName`. After login/logout fetch a fresh token. Errors are `application/problem+json` with `status`, `detail`, and stable `code`; no stack traces or credentials.

A revoked/expired authenticated session is cleared before public auth/config routes continue, so a client can obtain a fresh CSRF token and sign in again. Private API requests still return 401. A successful login returns the user immediately; clients should publish that session state without depending on a second token request, and obtain a fresh token before their next mutation.

## Public
- GET `/public/config`: registrationEnabled, portalName.
- GET `/auth/csrf`: token, headerName; token is not an authentication credential.
- POST `/auth/register`: email, displayName (2..100), password (12..128); 202 generic acknowledgement, 403 if registration closed. No auto-login.
- POST `/auth/login`: email, password -> user view. 401 generic invalid/inactive credentials, 429 throttled.
- POST `/auth/logout`: CSRF-protected session logout, 204.
- GET `/actuator/health` exists outside API base, exposes no details.

## Member
- GET `/users/me`: id, email, displayName, status, effective role, version, createdAt.
- PUT `/users/me/password`: currentPassword, newPassword; revokes existing sessions.
- GET `/dashboard`: announcements (up to 3), documents (up to 4), upcomingEvents (up to 5 overlapping the next 31 days, starting today in Europe/Budapest), links (up to 4), personal unreadMailCount. The former articles field is replaced by documents.
- GET `/announcements?page=0`: up to 20, important first. GET `/announcements/{id}`.
- GET `/knowledge/categories`: ordered category list from the read-only historical archive.
- GET `/knowledge/articles?category={id}&page=0`: up to 20 historical articles, updated first. GET `/knowledge/articles/{id}`. V5 preserves existing articles in Shared Documents; the archive is not synchronized with later document edits. Legacy knowledge writes return 410 `KNOWLEDGE_REPLACED` after normal authorization/validation.
- GET `/links`: up to 500 ordered by category and link order. GET `/links/categories`.
- GET `/mail/messages?q=&unread=false&page=0`: up to 30 newest messages; personal isRead/isImportant and attachmentCount. LIKE search on subject/sender/plain body.
- GET `/mail/messages/{id}`: message body, recipient addresses and attachment metadata. Does not mutate read state.
- PATCH `/mail/messages/{id}/state`: optional isRead and/or isImportant booleans; at least one must be supplied. Omitted/null fields retain the existing value; a newly created state row defaults unspecified flags to false. PUT remains a compatible alias with the same partial-update semantics. Returns 204 and modifies only the current user's row. The frontend marks read after a successful message open; manual unread remains until the next opening.
- GET `/mail/messages/{messageId}/attachments/{attachmentId}/download`: authenticated binary download, no shared caching.

Lists are arrays, not total-count envelopes. Offset page is zero-based and bounded. A full page may be followed by an empty page. UUID path identifiers are opaque strings. Activity timestamps, including createdAt/updatedAt, are timezone-less ISO strings representing UTC. Calendar startsAt/endsAt are the explicit exception: Europe/Budapest civil times, without UTC conversion. Binary files are not Base64 in JSON.

## Shared Documents

Every active member may create posts and comments. Authors may edit/delete their own entries; ADMIN may manage all entries. A post author cannot modify another member's comment. Original authorship is retained when an admin edits content.

- GET `/documents?q=&page=0`: up to 20 posts, most recently updated first; searches title/description.
- GET `/documents/{id}`: `{post, files, comments}`, including author IDs/names and optimistic versions.
- POST `/documents`: multipart fields `title`, `description`, repeated `files`; 201 with the saved post. Requires 1–5 nonempty files, each at most 50 MiB (52,428,800 bytes, displayed as 50 MB). Total request limit is 251 MiB including multipart overhead. Send CSRF as a header; let the browser supply the multipart boundary.
- PATCH `/documents/{id}`: `{title, description, version}`; edits metadata only, not the file set.
- DELETE `/documents/{id}?version=N`: 204; removes the post, its comments and file references.
- GET `/documents/{postId}/files/{fileId}/download`: authenticated forced attachment, no shared caching, verifies post/file association.
- POST `/documents/{id}/comments`: `{body}`; 201.
- PATCH `/documents/{id}/comments/{commentId}`: `{body, version}`.
- DELETE `/documents/{id}/comments/{commentId}?version=N`: 204.

Title is nonblank, at most 180 characters; description may be empty, at most 100,000; comments are nonblank, at most 5,000. Oversized uploads return 413 `UPLOAD_TOO_LARGE`; empty files or invalid counts return 400. Migrated knowledge posts may have zero files, preserving historical text. See `DOCUMENTS.md` for all DTO fields, storage boundaries and lifecycle details.

## Community links and calendar

Every active member can contribute through these routes. Owners and admins may edit/delete; other members receive 403. Authorship comes from the session and cannot be assigned by the request. Send the latest version on edits/deletes.

- POST `/links`: `{categoryId, name, url, description, sortOrder, version: 0}`; 201.
- PATCH `/links/{id}`: the same fields with the current version.
- DELETE `/links/{id}?version=N`: 204.
- Link responses include authorId, authorName and createdAt. Legacy links have no inferred author and display `Original content`. V6 creates a General category if categories are empty; category management remains admin-only.
- GET `/calendar/events?from=YYYY-MM-DD&to=YYYY-MM-DD`: entries overlapping `[from, to)`, positive range up to 366 days. More than 2,000 results requires a shorter range.
- GET `/calendar/events/{id}`: one event.
- POST `/calendar/events`: `{title, description, type, startsAt, endsAt, allDay, location, version: 0}`; 201. Type is EXAM, EVENT or DEADLINE.
- PATCH `/calendar/events/{id}`: the same fields with the current version.
- DELETE `/calendar/events/{id}?version=N`: 204.

Calendar startsAt/endsAt use Europe/Budapest wall time with no offset or Z. An optional end must follow start. All-day start/end values are midnight, with an exclusive end date; a null end means one day. A timed entry with no end is a point in time. Authorship and createdAt/updatedAt are included in responses; these activity timestamps remain UTC. See `COMMUNITY.md` for exact validation limits, overlap examples and time semantics.

## Admin
- GET `/admin/dashboard`: exact pendingRegistrations count.
- GET `/admin/users?status=&page=0`, GET `/admin/registrations?page=0`: 50-row pages.
- POST `/admin/registrations/{id}/approve` or `/reject`: `{version}`.
- POST `/admin/users/{id}/suspend`, `/disable`, `/activate`: `{version}`.
- PUT `/admin/users/{id}/roles`: `{role: "USER" | "ADMIN", version}`. Revokes target sessions.
- GET/PATCH `/admin/settings`: registrationEnabled, portalName, version.
- GET `/admin/audit-log?page=0`: 50 named activity entries, newest first, with actor display name.
- GET `/admin/audit-log?requests=true&page=0`: separate API request view with method, safe route template, response status and duration in milliseconds. Request payloads, query strings, credentials and content are not logged. See `AUDIT.md` for coverage and durability.

Content writes:
- POST `/admin/announcements`; PATCH `/admin/announcements/{id}`: title, bodyMarkdown, important, version.
- POST `/admin/links`; PATCH `/admin/links/{id}`: categoryId, name, url, description, sortOrder, version. Only absolute HTTP(S), no embedded URL credentials.
- DELETE the respective `/{id}?version=N`; stale/missing version is rejected.
- POST `/admin/links/categories`: name, sortOrder.
- PATCH the respective `/categories/{id}`: name, sortOrder.
- DELETE the respective `/categories/{id}`: conflict if referenced.

Documents and calendar use their member endpoints with server-enforced admin moderation; no duplicate admin write contract is required. Legacy `/admin/knowledge/**` write mappings are retired (410 for authorized valid requests), preserving the historical read API while preventing diverging copies after V5.

Mail operations:
- GET `/admin/mail/status`: enabled, running, accounts, failedMessages. Never returns credentials.
- POST `/admin/mail/sync`: 202 accepted; 409 disabled/already running.
- POST `/admin/mail/test-connection`: 204 if connection works, 502 otherwise.
- POST `/admin/mail/retry`: resets durable failed-message retry budgets; scheduled sync picks them up.

## Concurrency and errors
Send the last received version for optimistic writes. 409 CONFLICT means reload before retry; do not blindly overwrite. LAST_ADMIN protects the only active admin. Unauthorized and expired sessions return 401, role/CSRF errors 403, missing resources 404, validation 400. Errors do not expose whether an email exists at login. See tests for executable examples.
