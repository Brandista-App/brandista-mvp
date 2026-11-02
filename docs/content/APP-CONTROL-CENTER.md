# App Control Center — Phase 1

## Architecture

The Spring Boot backend remains the only publisher. Aiven MySQL stores a singleton
homepage draft and a separate published snapshot. Draft saves, publication and
rollback lock the singleton row and check `expectedVersion`; stale editors receive
409 rather than overwriting another operator. Each successful mutation appends an
immutable snapshot, actor account ID, action, version and UTC timestamp in the same
transaction. History is paginated (50 events/page). Rollback restores and publishes
a previous published snapshot, replacing the current draft; the admin UI confirms it.

Flyway V15 creates `app_content`, `app_content_history`, and `app_content_images`.
It changes no existing identity, onboarding, inventory, order or catalogue records.
The runtime database user needs SELECT/UPDATE on `app_content` and SELECT/INSERT on
`app_content_history` and `app_content_images`; migration DDL belongs to the migration
user. History/image registries do not require runtime UPDATE or DELETE permissions.
Categories are Phase 1's collections; there is no second collection catalogue.
Featured references must point to active brands/categories when saved/published.
References deactivated later are omitted from public responses. Duplicate IDs,
unknown sections, invalid schedules and unsupported destinations are rejected.

Banner order is array order. Each has HERO, PROMOTION or COLLECTION placement,
enabled state, optional UTC start/end and BOTH/MOBILE/WEBSITE targeting (BOTH default).
HERO and PROMOTION appear in the HERO section; COLLECTION appears in COLLECTIONS.
Section, brand and collection order is also array order. Turning a banner off in the
draft and publishing unpublishes it. A draft save never changes the published release.

Schedules use `[start, end)` at request time, with no cron or extra worker. Public
responses use `Cache-Control: no-store`. Clients refresh every 60 seconds while the
homepage is mounted, so scheduled changes can take up to one refresh to appear.
Supported sections: HERO, COLLECTIONS, BRANDS and PRODUCTS. Products remain live
catalogue data; this phase does not add a product recommendation editor.

## API contract

All management routes are under `/api/v1/super-admin/content`, protected by both
the existing live account/grant lookup and controller method authorization.

| Method | Suffix | Behavior |
| --- | --- | --- |
| GET | empty | `{version,draft,published}` |
| PUT | empty | save `{expectedVersion,document}` |
| POST | `/publish` | publish saved draft with `{expectedVersion}` |
| GET | `/preview?target=WEBSITE&at=ISO_INSTANT` | eligible saved draft at optional UTC instant |
| GET | `/history?page=0` | metadata for newest 50 events |
| POST | `/history/{id}/rollback` | restore a published revision, `{expectedVersion}` |
| POST | `/images` | multipart `file`; returns `{url}` |

Public GET `/api/v1/content/homepage?target=MOBILE` (or WEBSITE) returns only the
published eligible document. No release gives an empty response; clients retain their
existing homepage. API errors also fall back to the existing homepage. Old clients
continue using existing APIs. The public route accepts no writes and reveals no
draft or history fields. Missing or BOTH audience is rejected.

Destinations are optional and restricted to `/products`, `/brands`,
`/products?brandId=ID` or `/products?categoryId=ID`. Both clients check the vocabulary
again; native maps products to its catalogue route. External destinations and script
schemes are intentionally unsupported in this phase. Text is rendered as text,
never HTML; management input also rejects HTML opening brackets.

## Durable images

Images are stored in an operator-provided S3-compatible bucket, outside Render's
ephemeral disk. Upload is disabled with 503 until all required settings are present:

| Setting | Meaning |
| --- | --- |
| `CONTENT_S3_BUCKET` | Existing bucket |
| `CONTENT_S3_REGION` | Region (`auto` for providers requiring it) |
| `CONTENT_S3_ACCESS_KEY` | Backend-only restricted write credential |
| `CONTENT_S3_SECRET_KEY` | Backend-only secret |
| `CONTENT_IMAGE_BASE_URL` | HTTPS CDN/read origin, optionally including a prefix |
| `CONTENT_S3_ENDPOINT` | Optional HTTPS S3-compatible endpoint (path style) |

Configure CDN/bucket read access for `app-content/*`, a correct image content type,
and `X-Content-Type-Options: nosniff`. Keep upload/write permissions server-side.
No credentials are sent to the admin browser. Upload accepts PNG/JPEG up to 5 MiB,
checks actual decoding and pixel count before allocating the image, strips metadata
and appended data by PNG re-encoding, and generates random immutable object keys.
The SDK uses a bounded URLConnection HTTP transport; unused Apache/Netty transports
are excluded so they do not alter the application's other HTTP client behavior.
Only successfully stored, registered image URLs can be referenced by content.

No image deletion is offered because historical revisions may still reference it.
Failed DB registration after an object upload can leave an unreferenced object;
reconcile these offline against `app_content_images` before any future cleanup.
Do not expire or overwrite objects referenced by published/history snapshots.

## Deployment sequence — requires separate approval

1. Review the four repository PRs and configure a staging bucket/CDN with restricted
   credentials. Verify an upload and public read in staging; no provider is provisioned
   by this implementation.
2. Back up the database using the existing operational process. Deploy the backend
   first so Flyway V15 completes; confirm existing login/onboarding and commerce probes.
3. Configure the storage settings on the backend and verify anonymous writes fail,
   ordinary accounts get 403, and the Super Admin can upload/save/preview/publish.
4. Deploy the admin portal. It needs only its existing `VITE_API_BASE_URL`; secrets
   remain on the backend.
5. Deploy the customer website, then release the Expo mobile build through the
   existing release process. Existing installed app versions remain compatible.
6. Publish a staged banner and check target, order, start and expiry across both clients.

Code delivery does not authorize merging, deployment or production content writes.

## Publish the first banner

1. Sign in at the Super Admin portal and open App Control Center → Banners & Posters.
2. Add banner, upload a PNG/JPEG, enter title/description and choose an optional
   catalogue destination. Choose placement and visibility (Both by default).
3. Check Include in next release; set UTC start/end if desired. Dates in this editor
   are explicitly UTC, regardless of the operator's computer timezone.
4. Save draft. Preview Website and Mobile, optionally at the scheduled time.
5. Publish saved draft. This publishes the entire saved homepage configuration.
6. To remove it, clear Include, save and publish. Use Publishing History → Load history
   → Restore release to roll back a previous published snapshot.

## Validation

`mvnw.cmd verify` runs backend tests. `ContentIntegrationTests` exercises draft
isolation, optimistic concurrency, publication/rollback auditing, date boundaries,
audiences/default Both, unsafe URLs, image decoding, anonymous/customer rejection,
Super Admin writes, and public draft isolation. Existing authentication/onboarding
and commerce tests remain enabled. See the PR for final run results and limitations.
Live provider upload/CDN validation remains a staging check after provider configuration.
