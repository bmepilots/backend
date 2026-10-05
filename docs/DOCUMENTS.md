# Shared documents

The Shared documents area replaces the Knowledge base in the community interface. Every ACTIVE member can create a post with a title, a description and one to five files, read/download any community document, and comment on any post. The original author is always shown. A post or comment can be edited or deleted by its author or an ADMIN. A post author cannot edit another member's comment. ADMIN uses the same endpoints with server-enforced moderation rights.

## Source map

- `documents/api/DocumentsController.java`: HTTP and multipart bindings, validation, download headers.
- `documents/api/DocumentUploadErrors.java`: stable 413 upload-limit and 400 malformed-upload responses before the generic error handler.
- `documents/application/DocumentsService.java`: authorization, parameterized SQL, optimistic locking, audit and file/transaction coordination.
- `documents/application/DocumentUploadCleanup.java`: hourly expiry cleanup and crash-orphan reconciliation.
- `documents/infrastructure/DocumentStorage.java`: bounded streaming to private storage with random UUID keys.
- `V5__shared_documents.sql`: posts, files, comments and preservation of existing knowledge articles.
- `V8__staged_document_uploads.sql`: private owner-bound uploads with 24-hour expiration.
- `DocumentsIntegrationTest.java`: real HTTP/MariaDB authorization, limits, comments, moderation, audit and rollback coverage.

## API contract

All routes below are under `/api/v1` and require an ACTIVE authenticated session. Every write requires the CSRF header returned by `/auth/csrf`; a multipart upload carries the token in that header, never in a file or URL.

| Method and path | Request | Response |
| --- | --- | --- |
| GET `/documents?q=&page=0` | Search up to 200 characters; zero-based page | At most 20 posts, most recently updated first |
| GET `/documents/{id}` | None | `{post, files, comments}` |
| POST `/documents/uploads` | `multipart/form-data`: exactly one `file` | 201, private upload reference |
| DELETE `/documents/uploads/{id}` | None | 204; only the uploading member may discard it |
| POST `/documents` | JSON `{title, description, uploadIds}` | 201, post; consumes 1–5 staged upload IDs atomically |
| POST `/documents` (legacy) | `multipart/form-data`: `title`, `description`, repeated `files` | 201, post; retained for older private clients |
| PATCH `/documents/{id}` | `{title, description, version}` | Updated post |
| DELETE `/documents/{id}?version=N` | None | 204 |
| GET `/documents/{postId}/files/{fileId}/download` | None | Forced binary attachment, `Cache-Control: no-store` |
| POST `/documents/{id}/comments` | `{body}` | 201, comment |
| PATCH `/documents/{id}/comments/{commentId}` | `{body, version}` | Updated comment |
| DELETE `/documents/{id}/comments/{commentId}?version=N` | None | 204 |

Post fields: `id`, `title`, `description`, `authorId`, `authorName`, `createdAt`, `updatedAt`, `version`, `fileCount`, `commentCount`. File fields: `id`, `filename`, `sizeBytes`, `contentType`. Comment fields: `id`, `body`, `authorId`, `authorName`, `createdAt`, `updatedAt`, `version`. Internal storage keys and paths are never returned in API metadata. Dates follow the existing API UTC convention. The original author remains unchanged after moderator edits; `updated_by` records the editing actor privately. A post or comment PATCH must explicitly include a nonnegative `version`; missing or null versions return 400 rather than implicitly editing revision zero.

Upload references contain `id`, `filename`, `sizeBytes`, `contentType` and `expiresAt` (ISO local-date-time representing UTC). Uploads remain private and do not appear in document lists or downloads before publication. Even administrators cannot consume another member's staging references; moderation applies after publication. Missing, expired, consumed and other-member upload IDs return 404 without disclosing which case applies.

Upload files sequentially, one request per file, then publish the title, description and ordered distinct upload IDs as JSON. Every individual request fits under a 100 MB proxy request limit while still permitting five 50 MiB attachments in one post. Failed staging requests leave earlier successful references usable; retry only unfinished files. Explicit cancellation should DELETE known references as a best effort. A publish failure rolls back the complete post and retains valid staging references. If the publish response is lost, the server might already have committed: check the shared document list before starting a new upload. Reusing consumed IDs returns 404; the API does not implement a separate idempotency key.

Titles are nonblank and at most 180 characters. Descriptions may be empty and are at most 100,000 characters; comments are nonblank and at most 5,000 characters. Both are stored as user text, not trusted HTML. Search matches title/description with escaped SQL wildcards. Optimistic edits/deletes require the latest version and return 409 on stale data. A missing resource or mismatched post/file/comment pair returns 404; editing another member's item returns 403. Editing post metadata does not replace its files; create a new post to share a different set of files.

## Upload limits and private storage

Each file may contain at most **50 MiB (52,428,800 bytes)**, displayed as 50 MB in the interface. A new post requires 1–5 nonempty files. Staging accepts exactly one file per request, expires after 24 hours and allows at most ten unexpired references per member; exceeding that quota returns 409 `UPLOAD_LIMIT`. The user row is locked during stage/discard/publication to serialize concurrent quota and ownership checks. Publication locks the selected staging rows, records all file references, consumes the uploads and writes audit entries in one database transaction. File order matches `uploadIds`.

For backward compatibility, Spring multipart limits remain 50 MiB per file and 251 MiB for a complete legacy request. Large legacy multi-file requests can be rejected by upstream proxies; new clients must use staging. Multipart temporary files are written to disk (`file-size-threshold: 0`). The application independently enforces file count and size and counts streamed bytes instead of trusting client metadata. Oversized uploads return 413 with code `UPLOAD_TOO_LARGE`; empty files, duplicate publication IDs or an invalid count return 400 `VALIDATION`.

`DOCUMENT_STORAGE` configures the private directory, default `./storage/documents` relative to the backend working directory. The existing ignored `storage/` tree covers this directory. It must be readable/writable by the application account and kept outside any public/static web root. Original filenames are display metadata only: path segments and control characters are removed, length is bounded, and generated UUID keys name files on disk. Content type metadata is restricted to MIME-like tokens; actual downloads always use `application/octet-stream` and `Content-Disposition: attachment`. The application does not render, execute or trust uploaded content.

Each file streams through a private `.part` file, then moves to its final key (atomic move where supported). Failed streams remove temporary files. A failed database transaction removes all files stored during that transaction. Post deletion commits database removal before removing its physical files, so a rollback cannot destroy committed data. The database cascades file metadata and comments when a post is deleted.

Filesystem and MariaDB cannot share an atomic transaction. A process crash between disk and database operations, or failed cleanup, can leave an orphan file. Cleanup starts one minute after application startup and runs hourly. It locks and removes expired stage rows in batches of 200 (at most 2,000 per pass), commits their deletion and then removes their files. It additionally scans generated UUID objects and `.part` files older than 48 hours and removes only files absent from both staging and published metadata in one consistent SQL query. Referenced files, recently created files, unknown names, directories and symlinks are preserved. Failed removal is retried by later orphan reconciliation; warnings omit paths and user content. This scheduler is single-instance, like the rest of the backend.

There is no antivirus scanning, total published-storage quota or revisioned attachment workflow. Operators must back up MariaDB and document storage together, including staged files. Keep free space available for both multipart temporary files and the stored copy of an in-progress request. The ten-stage quota bounds live staging to 500 MiB per member; recently expired uploads may occupy extra space until cleanup runs.

## Legacy knowledge preservation

V5 copies every existing knowledge article into a post with the same ID, title, complete markdown body as its description, original creator/updater, timestamps and version. `legacy_article_id` and `legacy_category_name` preserve provenance and category metadata. These migrated posts intentionally have zero files; the 1-file minimum applies to new multipart posts only. Original knowledge tables remain intact as an archive. There is no ongoing synchronization between the old tables and Shared documents, so legacy writes must not be exposed by the replacement interface. Migration never invents files or truncates legacy article text.

## Audit and operational logs

Post create/edit/delete, each uploaded/downloaded file, and comment create/edit/delete append audit events containing actor, action, entity type and ID. Writes share the content transaction; failed transactions do not leave success events. Download audit records authorized delivery initiation, not a guarantee that the browser saved every byte. Deleting a post records one post deletion event; its file/comment removal is a database cascade. User content, filenames, uploaded bytes and credentials are excluded from audit rows.

Private staging adds `DOCUMENT_UPLOAD_STAGED` and `DOCUMENT_UPLOAD_DISCARDED` for its owner, and `DOCUMENT_UPLOAD_EXPIRED` for the system cleanup actor. Publication emits the existing `DOCUMENT_FILE_UPLOADED` events only when the files become part of the committed post.

The application also writes rolling operational logs to `APP_LOG_FILE` (default `./logs/portal.log`), with 10 MB rollover, 14 history periods and a 200 MB total cap. These limits cover operational files, not the database audit table. Request-level logging is owned by the shared security/request infrastructure rather than this module.

## Verification and extension notes

Run the real dedicated MariaDB suite described in `TESTING.md`; do not target the persistent development database. `DocumentsIntegrationTest` covers member creation with five files, original authorship, peer reading/comments, author/admin rules, anonymous download and missing-CSRF rejection, file/post pairing, forced download headers, optimistic versions, exact 50 MiB acceptance, 50 MiB + 1 rejection, six-file/empty-file rejection, path traversal prevention, cleanup after a later file fails, and stream-level oversize detection when declared size is false. Actual executed results are recorded in `STATUS.md` by the integrating agent.

Staging coverage additionally verifies five separate uploads remain invisible until one atomic publication, ordered downloads, owner-only consumption/discard (including admin isolation), reuse rejection, single-file count, exact size boundaries, quota, anonymous/CSRF rejection, failed-publish rollback, expiry, and orphan reconciliation preserving active/published/recent files.

Current responses include all comments for one post without comment pagination. Document lists use bounded offset pagination and inline descriptions; very large communities may need summary DTOs, comment pagination and full-text search. File replacement is deliberately not part of metadata PATCH; future attachment editing must preserve the per-post limit, owner/admin checks, audit and rollback cleanup invariants. Session validation and authorization are supplied by the common API security chain.
