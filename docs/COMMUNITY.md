# Community links and calendar

Updated: 2026-10-03. These modules let every active member contribute. Shared document posts and comments have their own document contract; this file owns links and calendar behavior.

## Authorization and authorship

All APIs below require an active cookie session. Mutations require CSRF. Any active member can create a link or calendar event. The creator can edit or delete their own entries; an administrator can manage every entry. Other members have read access. The services enforce ownership before writing, and the existing security filter protects all `/admin/**` aliases. Suspended or disabled accounts cannot contribute using an old session.

The server supplies author ID/name from the authenticated principal and user table. Clients cannot choose or change an author. An administrator's edits preserve original authorship. Every successful create, update and delete is audited with the acting user, action, entity type and ID in the same database transaction. Entries contain versions; send the last version for edits/deletes. Missing versions in JSON fail validation, and stale versions return 409. A denied ownership check returns 403. Display names resolve to current user names.

## Useful links

- `GET /api/v1/links`: up to 500 links ordered by category/order/name/ID.
- `GET /api/v1/links/categories`: category IDs, names and sortOrder.
- `POST /api/v1/links`: create, returns 201 and the saved link.
- `PATCH /api/v1/links/{id}`: owner/admin update, returns 200.
- `DELETE /api/v1/links/{id}?version=N`: owner/admin delete, returns 204.
- Existing `/api/v1/admin/links` create/update/delete aliases remain, and require ADMIN.
- Category create/update/delete remain under `/api/v1/admin/links/categories` and require ADMIN.

The write body is `{categoryId, name, url, description, sortOrder, version}`. Use version 0 on creation. Name is required and at most 120 characters, description at most 500, URL at most 2048, and sortOrder/version cannot be negative. URLs must be absolute HTTP(S) with a host and without embedded credentials. A missing/referenced category returns conflict through FK enforcement.

The response retains `id, categoryId, categoryName, name, url, description, sortOrder, version` and adds `authorId, authorName, createdAt`. V6 introduces author tracking. Earlier links have nullable authorId, display `Original content`, and can be managed by administrators. Their createdAt is the migration time because their actual creation time was never recorded. Do not present these rows as authored by a guessed user.

V6 seeds one `General` category if no link categories exist, allowing a member to add the first link without an administrator's preparatory category action. Category editing remains administrative; deleting a referenced category fails. Audit actions are `LINK_CREATED`, `LINK_UPDATED`, `LINK_DELETED`, `LINK_CATEGORY_SAVED`, and `LINK_CATEGORY_DELETED`.

## Calendar API

- `GET /api/v1/calendar/events?from=YYYY-MM-DD&to=YYYY-MM-DD`: events overlapping the date range, ordered by start time, allDay, then ID.
- `GET /api/v1/calendar/events/{id}`: one event.
- `POST /api/v1/calendar/events`: create, 201.
- `PATCH /api/v1/calendar/events/{id}`: owner/admin update, 200.
- `DELETE /api/v1/calendar/events/{id}?version=N`: owner/admin delete, 204.

Create/update body:

```json
{
  "title": "Principles of flight exam",
  "description": "Bring a calculator.",
  "type": "EXAM",
  "startsAt": "2026-10-19T09:00:00",
  "endsAt": "2026-10-19T10:30:00",
  "allDay": false,
  "location": "Building Q",
  "version": 0
}
```

`type` is exactly `EXAM`, `EVENT`, or `DEADLINE`. Required title has 1–180 characters (nonblank). Description is required but can be empty, up to 10,000 characters. Location is required but can be empty, up to 200 characters. startsAt and version are required. endsAt may be null. The response adds `id, authorId, authorName, createdAt, updatedAt`; all write fields are also present.

### Time semantics

The community calendar uses **Europe/Budapest wall time**. startsAt/endsAt are local ISO datetime strings without an offset or `Z`, stored directly in DATETIME. They are an intentional exception to the API's UTC activity timestamps: createdAt/updatedAt remain UTC. Frontends must not append `Z` to calendar times or convert them based on a viewer's computer timezone. A 09:00 Budapest class remains displayed as 09:00 even across daylight-saving changes.

The query range is half-open: from at midnight is included, to at midnight is excluded. It must be positive and no longer than 366 days. The backend includes events that started before from but continue into the range, and excludes events ending exactly at from. More than 2,000 matching events returns 400 asking for a shorter date range, rather than silently truncating.

An end, when supplied, must be strictly after start. All-day entries require midnight start/end. Their end date is exclusive: an event covering 19–20 October has startsAt `2026-10-19T00:00:00` and endsAt `2026-10-21T00:00:00`. Null end on an all-day event means that start date only. Null end on a timed event is a point in time, suitable for a homework deadline.

Calendar times are intended for shared classroom scheduling, not aviation operational dispatch. This release does not include recurrence, reminders, external calendar sync, event subscriptions, or timezone conversion. Ambiguous/missing clock times at DST changes are retained as the entered civil time; no UTC instant is inferred.

Audit actions are `CALENDAR_EVENT_CREATED`, `CALENDAR_EVENT_UPDATED`, and `CALENDAR_EVENT_DELETED`.

## Implementation and verification

- `links/api` maps existing and community endpoints; `links/application` validates URLs, author ownership and optimistic writes.
- `calendar/api` validates request records/date query conversion; `calendar/application` owns date overlap, semantics and permissions.
- `V6__calendar_and_community_links.sql` adds link authors and the calendar schema. No older migrations are edited.
- `CommunityIntegrationTest` uses real random-port HTTP and the dedicated Docker MariaDB on 3308. It verifies member contribution, authorship, CSRF, admin URL guards, nonowner rejection, admin moderation, stale writes, safe URLs, date overlap/exclusive ends, all-day dates, null-end deadlines, DST-day civil-time preservation, validation and audit actors. Actual test execution outcomes belong in `STATUS.md`.
