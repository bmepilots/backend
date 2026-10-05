# Mail module operations and extension

Updated: 2026-10-05.

## Enable a real Gmail account
Requires Gmail IMAP access and an App Password, which generally requires Google two-step verification and is subject to account restrictions. Never provide the normal Gmail password. Place the App Password alone in a private UTF-8 file outside Git with access restricted to the service account. Set MAIL_USERNAME, MAIL_PASSWORD_FILE (absolute path), and MAIL_ENABLED=true; restart the backend. The admin status page can test connectivity and enqueue a sync. Do not paste secrets into chat, source, workflow logs or browser configuration.

## Local launcher configuration

This workspace uses `bmepilots2026@gmail.com`. Its ignored `.env.mail.local` contains:

```dotenv
MAIL_ENABLED=true
MAIL_USERNAME=bmepilots2026@gmail.com
MAIL_PASSWORD_FILE=.secrets/gmail-app-password
MAIL_INITIAL_DAYS=90
MAIL_INTERVAL_MS=60000
```

The App Password itself is stored only in `.secrets/gmail-app-password`, with Windows access limited to the current user and SYSTEM. Neither file is tracked. On another machine, create your own private password file and local configuration; do not copy secrets into documentation. Use plain unquoted values; the launcher resolves relative file/storage paths against the backend repository. Explicit process environment values override this local file. `dev.ps1 -Test` does not load it.

Start with `pwsh -File scripts/dev.ps1`. To disable local polling, set MAIL_ENABLED=false in the process environment before starting, or edit the local configuration and restart. For a replacement credential, securely replace the contents of the password file, restart, and verify the admin connection/sync status. Never print the password while troubleshooting. Mail is fetched read-only; no SMTP sending or Gmail flag changes are implemented.

## Ubuntu VM configuration and evidence

The first VM deployment intentionally left mail disabled because the ignored workstation credential is not distributed through Git or build contexts. On 2026-10-05 the existing operator-provided App Password was installed privately at `/srv/bmepilots/db/deploy/secrets/mail_app_password`, owned by `root:10001` with mode `0640`. The backend reads its Docker secret mount at `/run/secrets/mail_app_password`. The VM's private Compose environment now enables `MAIL_ENABLED=true` and `MAIL_USERNAME=bmepilots2026@gmail.com`; the backend was recreated successfully.

Verified through the authenticated VM admin API on 2026-10-05: connection test returned HTTP 204, `lastSuccessAt` was `2026-10-05T13:36:37` UTC, `lastError` was null and `failedMessages` was zero. This is VM evidence, distinct from the earlier local import. Credential contents were neither printed nor added to Git. Source defaults remain disabled for a fresh checkout until its operator installs a private credential. The backend needs outbound TLS to Gmail IMAP on port 993; its dedicated egress network provides this while API and database ports remain unpublished.

## Processing flow
Scheduler -> MailSyncService -> MailProvider -> GmailImapMailProvider -> local MariaDB + private attachment storage -> REST -> React. IMAP is fixed to imap.gmail.com:993 with TLS server identity checking and connection/read/write timeouts. Folders are opened read-only and peek mode is requested. User browsing never connects to Gmail.

## Current behavior
- One configured account and INBOX, polling once per minute by default, one worker per backend instance.
- Import window defaults to the last 90 days based on received date. Imported messages remain locally indefinitely in this version; no automatic deletion/retention job yet.
- Up to 100 UID positions considered per batch and up to 5 selected messages, including up to 2 retries. This bounds memory and each scheduling pass. Large histories can take many passes.
- The source key is folder + UIDVALIDITY + UID. Sequence numbers are not persisted.
- Gmail X-GM-MSGID is used for account-level deduplication across validity changes. Message-ID is metadata only, not an enforced unique identifier.
- Changed UIDVALIDITY restarts the scan; existing message IDs preserve user state when provider IDs match. Source deletions/moves are not yet reconciled; local messages stay readable.
- Durable processing failures retry at most 3 times. Admin retry resets the budget. Connection failures keep the previous cursor and retry at the next interval; exponential backoff is a follow-up.
- A cursor advances only after messages/failures have durable records. An interrupted import retries idempotently.
- Max raw message 15 MiB, individual attachment 10 MiB, 20 attachments, 100 MIME parts, depth 10; text/HTML caps. Over-limit messages become durable failures.
- Recipients TO/CC/REPLY_TO stored separately, bounded to 200. Unavailable BCC information is not invented.

## Personal read and important flags

Read/important flags belong to each member's local `user_mail_state` row, independently of other members and Gmail. Repeated imports, provider-ID deduplication and UIDVALIDITY resets retain that local state. A missing row starts with both flags false.

Reading `GET /mail/messages/{id}` alone does not mutate state. Once the frontend successfully loads an opened message, it sends `PATCH /mail/messages/{id}/state` with `{isRead: true}`. It does this once per opening. A manual **Mark as unread** stays unread while the message remains open; selecting/reopening it marks read again. Failed detail requests do not mark read, and failed state requests remain visible as errors. List and dashboard queries are refreshed after successful changes.

PATCH accepts either or both boolean fields; an omitted/null field preserves its current value. PUT is retained as a compatible alias with the same partial semantics. This prevents a star toggle from resetting a concurrently changed read flag, and prevents marking read from clearing important. An empty change is rejected. State mutations are CSRF-protected and audited as `MAIL_STATE_CHANGED`; none is propagated to Gmail.

## HTML and attachments
Jsoup allowlist strips scripts, forms, styles, images and unsafe URLs. Client iframe has no scripts/same-origin permission and a restrictive CSP. External images and tracking pixels never load. Plain text is available for search/fallback. Inline images currently download as attachments rather than rendering by CID.

Attachments use random storage keys under MAIL_STORAGE, independent of filenames; write via temporary file then atomic move. If the database insert rolls back, newly written files are discarded. A process crash between file write and database commit can leave harmless orphan files; reference-based cleanup is a future maintenance operation. Do not delete referenced files. Antivirus scanning is not implemented; downloads are not guaranteed safe and are never executed/previewed by the server.

Back up MariaDB together with both backend file stores: `storage/attachments` (or MAIL_STORAGE) and `storage/documents` (or DOCUMENT_STORAGE). The latter belongs to community uploads and is not governed by mail attachment limits. See the sibling db repository's `docs/OPERATIONS.md` for coordinated snapshot/restore guidance.

## Provider extension
Implement the application `MailProvider` interface using provider-neutral Message/Batch/Attachment records. Provider-specific IDs remain opaque strings. Keep all protocol objects in infrastructure. Add capability methods only when a concrete feature needs them; do not fake support for SMTP/calendar. Tests can supply a deterministic provider without network or real credentials.

## Verification boundary
Live Gmail access was verified on 2026-10-03 through the running backend scheduler: the first successful import stored 3 messages, with no account error and no durable message failures. Verification inspected operational timestamps and counts only, without exposing message contents or credentials. Local provider fixture tests separately cover idempotence and personal state. Browser rendering of the real inbox remains unverified because browser access was denied; see STATUS.

The current test suite includes partial read/important state changes and preservation across repeated imports. Frontend component tests exercise successful-open marking, manual unread and failure behavior. Test source coverage is distinct from executed verification: consult each repository's STATUS for the latest actual run.
