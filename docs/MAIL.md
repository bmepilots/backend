# Mail module operations and extension

Updated: 2026-10-10.

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

Start with `pwsh -File scripts/dev.ps1`. To disable local polling, set MAIL_ENABLED=false in the process environment before starting, or edit the local configuration and restart. For a replacement credential, securely replace the contents of the password file, restart, and verify the admin connection/sync status. Never print the password while troubleshooting. Mail is fetched read-only and Gmail flags are unchanged. SMTP forwarding is a separate administrator-controlled feature, disabled by default; see below.

## Ubuntu VM configuration and evidence

The first VM deployment intentionally left mail disabled because the ignored workstation credential is not distributed through Git or build contexts. On 2026-10-05 the existing operator-provided App Password was installed privately at `/srv/bmepilots/db/deploy/secrets/mail_app_password`, owned by `root:10001` with mode `0640`. The backend reads its Docker secret mount at `/run/secrets/mail_app_password`. The VM's private Compose environment now enables `MAIL_ENABLED=true` and `MAIL_USERNAME=bmepilots2026@gmail.com`; the backend was recreated successfully.

Verified through the authenticated VM admin API on 2026-10-05: connection test returned HTTP 204, `lastSuccessAt` was `2026-10-05T13:36:37` UTC, `lastError` was null and `failedMessages` was zero. This is VM evidence, distinct from the earlier local import. Credential contents were neither printed nor added to Git. Source defaults remain disabled for a fresh checkout until its operator installs a private credential. The backend needs outbound TLS to Gmail IMAP on port 993 and, when forwarding is enabled, STARTTLS to Gmail SMTP on port 587; its dedicated egress network provides this while API and database ports remain unpublished.

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

## Automatic forwarding to members

The administrator enables this separately in **Admin → Email connection → Automatic forwarding**. Flyway V10 installs the setting OFF and never creates deliveries for existing imported messages. Forwarding uses the same `MAIL_USERNAME` and readable `MAIL_PASSWORD_FILE` as IMAP and requires `MAIL_ENABLED=true`; an existing configured deployment needs no new environment variable or secret. The outgoing connection is fixed to `smtp.gmail.com:587`, requires STARTTLS with server identity validation, and uses 10-second connect and 20-second read/write timeouts. There is no clear-text fallback or configurable arbitrary SMTP destination. **Test connection** still checks IMAP, not SMTP, and `available` only reports local configuration readiness.

### Which messages and members participate

- Only a newly imported message whose Gmail IMAP received date is at or after the current enable cutoff can enqueue. Enabling or re-enabling does not replay historical messages, messages received during an earlier disabled window, or the local inbox. The IMAP INTERNALDATE/received date is used, not the sender-controlled Date header. A message without a reliable received date may still import but is ineligible for forwarding.
- Recipients are selected from current `users.status='ACTIVE'` during that import, without a static mailing list. A newly approved/activated member participates in subsequent eligible imports automatically. Previous posts/messages are not sent retroactively. Both administrators and regular members participate while ACTIVE; pending, suspended, disabled and rejected users do not.
- The worker rechecks the recipient's current ACTIVE status and registered address when claiming a delivery. Inactive recipients are cancelled. A status change after the SMTP attempt begins cannot recall that in-flight mail.
- The configured shared mailbox is never a recipient or eligible source, including its Gmail dot/plus/googlemail.com aliases. Explicit `X-BME-Pilots-Forwarded` markers, `Auto-Submitted: auto-replied`, empty envelope Return-Path and delivery-status reports suppress automatic reply/bounce loops. Legitimate `Auto-Submitted: auto-generated` school/system notifications remain eligible. This is loop prevention, not proof of sender authenticity.
- Each recipient receives a separate envelope with exactly one visible To address. Other members' addresses and the original To/Cc routing are not copied. The authenticated mailbox is From; original sender, subject and receive date are included in the body. The first stored Reply-To is used if valid, otherwise the original sender is used when valid. Replies therefore normally go to the original conversation address.
- The subject gains `[BME Pilots]`. Plain text, sanitized HTML and imported attachments are included. Attachments are forced to attachment disposition and application/octet-stream instead of trusting source MIME types. Original arbitrary transport/authentication headers are not copied. Forwarding preserves the already imported content limits; it is not a raw `.eml` archive. Outgoing loop headers and a stable per-delivery Message-ID are generated.

### Queue and outcomes

`mail_forwarding_outbox` is stored in MariaDB and committed in the same transaction as each new import. Its unique `(message_id,user_id)` key prevents ordinary duplicate enqueue. Deduplication across Gmail UIDVALIDITY changes relies on the existing X-GM-MSGID provider identifier; without that identifier a rescan can import a distinct local copy. Source Message-ID alone is not trusted as globally unique. Disabling cancels all PENDING rows; an already SENDING item can finish. A fresh enable generation prevents an in-flight safe-to-retry failure from recreating an old backlog after disable/re-enable.

The single-process worker starts after 20 seconds and attempts at most one recipient every five seconds, waiting for the previous attempt to finish. A message to 30 members therefore takes at least roughly 2.5 minutes after import to submit fully, and a backlog takes longer. There are no parallel recipients. Tests override the internal `portal.mail.forwarding.initial-delay-ms` Spring property to keep scheduling deterministic; no new deployment variable is required.

| State | Meaning |
|---|---|
| PENDING | Awaiting its initial attempt or a definite safe retry |
| SENDING | Claim committed before contacting SMTP |
| SENT | Gmail accepted submission; final recipient delivery/read is unverified |
| FAILED | Definitive configuration, recipient, preparation or SMTP rejection, or exhausted safe retries |
| UNCERTAIN | SMTP may have accepted the message; deliberately not retried automatically |
| CANCELLED | Disabled window or recipient no longer eligible; never resumed automatically |

Connection failures before submitting and explicit SMTP 4xx rejections permit at most three total attempts, delayed by 60 then 300 seconds. Authentication failures, explicit 5xx, rejected recipients and preparation failures become FAILED. Exceptions during submission without authoritative rejection become UNCERTAIN. The transport's close/QUIT failure cannot erase a successful DATA acceptance. A process restart converts unfinished SENDING rows to UNCERTAIN. A database error after SMTP acceptance can leave SENDING until restart/operator investigation. No exactly-once SMTP guarantee is made; stable IDs do not force external servers to deduplicate.

The admin page displays all retained per-recipient counts and a safe latest error. Errors may remain visible for historical failures after other deliveries succeed. There is deliberately no blind retry action for FAILED/UNCERTAIN rows; the existing IMAP **Retry failed messages** button only retries imports. Investigate queue IDs and the mailbox's Sent folder privately before any operator-directed recovery; never bulk-reset uncertain outcomes to PENDING. Forwarding errors do not remove the source mail from the portal. Queue/audit history currently has no automatic retention job.

### Gmail quotas and operational verification

A personal Gmail account can block sending after more than 500 messages in a day, and the block may last 1–24 hours; forwarding one incoming message to 30 members consumes 30 separate submissions. The rate limit above controls bursts but does not reserve capacity or bypass Google's account-wide daily quota. Other sends from the same mailbox share that quota. If the community grows beyond it, use an appropriate transactional mail service with its own reviewed adapter rather than raising concurrency. See [Google's sending limits](https://support.google.com/mail/answer/22839?hl=en) and [Gmail mail-client/App Password guidance](https://support.google.com/mail/answer/7126229?hl=en). Limits and account policies may change.

The existing backend egress network is suitable for SMTP, but ISP/firewall reachability and Google acceptance still require operational verification. Source configuration readiness, previous IMAP success and successful local tests do not prove real SMTP delivery. Verification uses fake transports and synthetic recipients; never enable forwarding on real community users merely for a smoke test.

### Backup and restore

The settings/outbox are included in the ordinary full MariaDB backup; forwarding reuses existing attachment files, so no additional volume is introduced. Restore together with both private file stores. **Before starting a restored backend, set MAIL_ENABLED=false in the restore environment, then disable forwarding and cancel old PENDING rows in the restored database.** A restored snapshot may contain PENDING rows that were sent after the backup: replaying them would duplicate real external mail. Follow the sibling db restore runbook for the exact reviewed procedure. SENT/FAILED/UNCERTAIN/CANCELLED rows are history and must not be blindly reset. Keep forwarding disabled until the operator has reconciled the restored outbox and deliberately enables a fresh window.

## Personal read and important flags

Read/important flags belong to each member's local `user_mail_state` row, independently of other members and Gmail. Repeated imports, provider-ID deduplication and UIDVALIDITY resets retain that local state. A missing row starts with both flags false.

Reading `GET /mail/messages/{id}` alone does not mutate state. Once the frontend successfully loads an opened message, it sends `PATCH /mail/messages/{id}/state` with `{isRead: true}`. It does this once per opening. A manual **Mark as unread** stays unread while the message remains open; selecting/reopening it marks read again. Failed detail requests do not mark read, and failed state requests remain visible as errors. List and dashboard queries are refreshed after successful changes.

PATCH accepts either or both boolean fields; an omitted/null field preserves its current value. PUT is retained as a compatible alias with the same partial semantics. This prevents a star toggle from resetting a concurrently changed read flag, and prevents marking read from clearing important. An empty change is rejected. State mutations are CSRF-protected and audited as `MAIL_STATE_CHANGED`; none is propagated to Gmail.

## HTML and attachments
Jsoup allowlist strips scripts, forms, styles, images and unsafe URLs. Client iframe has no scripts/same-origin permission and a restrictive CSP. External images and tracking pixels never load. Plain text is available for search/fallback. Inline images currently download as attachments rather than rendering by CID.

Attachments use random storage keys under MAIL_STORAGE, independent of filenames; write via temporary file then atomic move. If the database insert rolls back, newly written files are discarded. A process crash between file write and database commit can leave harmless orphan files; reference-based cleanup is a future maintenance operation. Do not delete referenced files. Antivirus scanning is not implemented; downloads are not guaranteed safe and are never executed/previewed by the server.

Back up MariaDB together with both backend file stores: `storage/attachments` (or MAIL_STORAGE) and `storage/documents` (or DOCUMENT_STORAGE). The latter belongs to community uploads and is not governed by mail attachment limits. See the sibling db repository's `docs/OPERATIONS.md` for coordinated snapshot/restore guidance.

## Provider extension
Implement the application `MailProvider` interface using provider-neutral Message/Batch/Attachment records. Provider-specific IDs remain opaque strings. Keep all protocol objects in infrastructure. Incoming providers must set Message.forwardable conservatively from reliable receive timestamps and loop headers. Outgoing adapters implement MailDelivery and distinguish safe retry from uncertain SMTP acceptance; never infer exactly-once delivery. Tests can supply a deterministic provider without network or real credentials.

## Verification boundary
Live Gmail access was verified on 2026-10-03 through the running backend scheduler: the first successful import stored 3 messages, with no account error and no durable message failures. Verification inspected operational timestamps and counts only, without exposing message contents or credentials. Local provider fixture tests separately cover idempotence and personal state. Browser rendering of the real inbox remains unverified because browser access was denied; see STATUS.

The current test suite includes partial read/important state changes and preservation across repeated imports. Frontend component tests exercise successful-open marking, manual unread and failure behavior. Test source coverage is distinct from executed verification: consult each repository's STATUS for the latest actual run.
