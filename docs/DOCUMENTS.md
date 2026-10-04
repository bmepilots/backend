# Shared documents

The Shared documents area replaces the Knowledge base in the community interface. Every ACTIVE member can create a post with a title, a description and one to five files, read/download any community document, and comment on any post. The original author is always shown. A post or comment can be edited or deleted by its author or an ADMIN. A post author cannot edit another member's comment. ADMIN uses the same endpoints with server-enforced moderation rights.

## Source map

- `documents/api/DocumentsController.java`: HTTP and multipart bindings, validation, download headers.
- `documents/api/DocumentUploadErrors.java`: stable 413 upload-limit and 400 malformed-upload responses before the generic error handler.
- `documents/application/DocumentsService.java`: authorization, parameterized SQL, optimistic locking, audit and file/transaction coordination.
- `documents/infrastructure/DocumentStorage.java`: bounded streaming to private storage with random UUID keys.
- `V5__shared_documents.sql`: posts, files, comments and preservation of existing knowledge articles.
- `DocumentsIntegrationTest.java`: real HTTP/MariaDB authorization, limits, comments, moderation, audit and rollback coverage.

## API contract

All routes below are under `/api/v1` and require an ACTIVE authenticated session. Every write requires the CSRF header returned by `/auth/csrf`; a multipart upload carries the token in that header, never in a file or URL.

| Method and path | Request | Response |
| --- | --- | --- |
| GET `/documents?q=&page=0` | Search up to 200 characters; zero-based page | At most 20 posts, most recently updated first |
| GET `/documents/{id}` | None | `{post, files, comments}` |
| POST `/documents` | `multipart/form-data`: `title`, `description`, repeated `files` | 201, post |
| PATCH `/documents/{id}` | `{title, description, version}` | Updated post |
| DELETE `/documents/{id}?version=N` | None | 204 |
| GET `/documents/{postId}/files/{fileId}/download` | None | Forced binary attachment, `Cache-Control: no-store` |
| POST `/documents/{id}/comments` | `{body}` | 201, comment |
| PATCH `/documents/{id}/comments/{commentId}` | `{body, version}` | Updated comment |
| DELETE `/documents/{id}/comments/{commentId}?version=N` | None | 204 |

Post fields: `id`, `title`, `description`, `authorId`, `authorName`, `createdAt`, `updatedAt`, `version`, `fileCount`, `commentCount`. File fields: `id`, `filename`, `sizeBytes`, `contentType`. Comment fields: `id`, `body`, `authorId`, `authorName`, `createdAt`, `updatedAt`, `version`. Internal storage keys and paths are never returned in API metadata. Dates follow the existing API UTC convention. The original author remains unchanged after moderator edits; `updated_by` records the editing actor privately. A post or comment PATCH must explicitly include a nonnegative `version`; missing or null versions return 400 rather than implicitly editing revision zero.

Titles are nonblank and at most 180 characters. Descriptions may be empty and are at most 100,000 characters; comments are nonblank and at most 5,000 characters. Both are stored as user text, not trusted HTML. Search matches title/description with escaped SQL wildcards. Optimistic edits/deletes require the latest version and return 409 on stale data. A missing resource or mismatched post/file/comment pair returns 404; editing another member's item returns 403. Editing post metadata does not replace its files; create a new post to share a different set of files.

## Upload limits and private storage

Each file may contain at most **50 MiB (52,428,800 bytes)**, displayed as 50 MB in the interface. A new post requires 1–5 nonempty files. Spring multipart limits are 50 MB per file and 251 MB for the complete request, allowing all five maximum-size files plus form overhead. Multipart temporary files are written to disk (`file-size-threshold: 0`). The application independently enforces file count and size and counts streamed bytes instead of trusting client metadata. Oversized uploads return 413 with code `UPLOAD_TOO_LARGE`; empty files or an invalid count return 400 `VALIDATION`.

`DOCUMENT_STORAGE` configures the private directory, default `./storage/documents` relative to the backend working directory. The existing ignored `storage/` tree covers this directory. It must be readable/writable by the application account and kept outside any public/static web root. Original filenames are display metadata only: path segments and control characters are removed, length is bounded, and generated UUID keys name files on disk. Content type metadata is restricted to MIME-like tokens; actual downloads always use `application/octet-stream` and `Content-Disposition: attachment`. The application does not render, execute or trust uploaded content.

Each file streams through a private `.part` file, then moves to its final key (atomic move where supported). Failed streams remove temporary files. A failed database transaction removes all files stored during that transaction. Post deletion commits database removal before removing its physical files, so a rollback cannot destroy committed data. The database cascades file metadata and comments when a post is deleted.

Filesystem and MariaDB cannot share an atomic transaction. A process crash between disk and database operations, or failed cleanup, can leave an orphan file. No scheduled orphan reconciliation, antivirus scanning, user quotas, revisioned attachments or backup automation is implemented. Cleanup failures produce an operational warning without a filesystem path or user content. Operators must back up database and document storage together before treating this as the only copy of important materials. Keep free space available for up to the multipart temporary copy and the stored copy of an in-progress request.

## Legacy knowledge preservation

V5 copies every existing knowledge article into a post with the same ID, title, complete markdown body as its description, original creator/updater, timestamps and version. `legacy_article_id` and `legacy_category_name` preserve provenance and category metadata. These migrated posts intentionally have zero files; the 1-file minimum applies to new multipart posts only. Original knowledge tables remain intact as an archive. There is no ongoing synchronization between the old tables and Shared documents, so legacy writes must not be exposed by the replacement interface. Migration never invents files or truncates legacy article text.

## Audit and operational logs

Post create/edit/delete, each uploaded/downloaded file, and comment create/edit/delete append audit events containing actor, action, entity type and ID. Writes share the content transaction; failed transactions do not leave success events. Download audit records authorized delivery initiation, not a guarantee that the browser saved every byte. Deleting a post records one post deletion event; its file/comment removal is a database cascade. User content, filenames, uploaded bytes and credentials are excluded from audit rows.

The application also writes rolling operational logs to `APP_LOG_FILE` (default `./logs/portal.log`), with 10 MB rollover, 14 history periods and a 200 MB total cap. These limits cover operational files, not the database audit table. Request-level logging is owned by the shared security/request infrastructure rather than this module.

## Verification and extension notes

Run the real dedicated MariaDB suite described in `TESTING.md`; do not target the persistent development database. `DocumentsIntegrationTest` covers member creation with five files, original authorship, peer reading/comments, author/admin rules, anonymous download and missing-CSRF rejection, file/post pairing, forced download headers, optimistic versions, exact 50 MiB acceptance, 50 MiB + 1 rejection, six-file/empty-file rejection, path traversal prevention, cleanup after a later file fails, and stream-level oversize detection when declared size is false. Actual executed results are recorded in `STATUS.md` by the integrating agent.

Current responses include all comments for one post without comment pagination. Document lists use bounded offset pagination and inline descriptions; very large communities may need summary DTOs, comment pagination and full-text search. File replacement is deliberately not part of metadata PATCH; future attachment editing must preserve the per-post limit, owner/admin checks, audit and rollback cleanup invariants. Session validation and authorization are supplied by the common API security chain.
