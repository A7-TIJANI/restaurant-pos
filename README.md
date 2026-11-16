# Epic IT POS (v2)

Offline-first desktop point-of-sale system for restaurants, rewritten from a single-file browser prototype
(v1) onto a real SQL database. Zero-install on Windows: one folder, double-click the `.exe`.

> **Status:** early development. Done: foundations (weeks 1-2), order flow (weeks 3-4), owner security and order
> management (week 5), reports (week 6), Excel export, backups, restore, first-run wizard, settings, sound, the
> restore wizard for an unopenable database and menu management (week 7). Feature-complete for v2.0.0: also the cashier
> **Stock & sales today** panel, the add-on line merge fix and the **set stock to an exact number** mode. Next: tagging
> `v2.0.0` and the field test on the owner's laptop (week 8).

## Why v2

v1 kept everything in browser `localStorage`, so data was fragile and wiped nightly. v2 stores everything in an
embedded **H2** database on the laptop, with versioned schema migrations, server-side owner PIN, an audit trail
and automatic local backups.

## Stack

Java 21 · Spring Boot 3.5 · Thymeleaf + vanilla JS · Spring Data JPA · Flyway · H2 (embedded, file mode) ·
JUnit 5 + Spring Boot Test · GitHub Actions. Packaged with `jpackage --type app-image` (Eclipse Temurin 21).

## Architecture notes

- **One process.** `EpicItPosApplication.main()` runs a single-instance check, opens the DB, starts embedded
  Tomcat on `127.0.0.1` with an OS-assigned free port (`server.port=0`), writes the port to `port.txt` and opens
  the browser. A second launch finds the live instance via `port.txt` + `/ping`, opens the browser there and exits.
- **Data lives on the laptop only**: `%LOCALAPPDATA%\EpicIT POS\` (`epicit-pos.mv.db`, `config.properties`,
  `port.txt`, `backups\`). Nothing is read from a USB stick at runtime.
- **Schema is owned by Flyway** (`src/main/resources/db/migration`); Hibernate only validates.
- **Money is `BIGINT` RWF**, integer math only. Order lines snapshot item name and unit price, so menu edits
  never rewrite history. Menu items and add-ons are deactivated, never deleted.
- **Timestamps are UTC instants**; the JVM timezone is pinned to `Africa/Kigali` at startup for report boundaries.
- **Order state machine**: `DRAFT -> PRINTING_CUSTOMER -> PRINTING_KITCHEN -> FINALIZED` (owner may `VOID` later).
  Exactly one in-progress order exists at a time and survives restarts. Stock changes only at finalization, in the
  same transaction as the sale; finalizing twice is a no-op, and if stock ran out meanwhile nothing is decremented
  and the order is reopened as a draft. A crash mid-print leads to a "was the kitchen ticket printed?" prompt on restart.
- **Owner security** (server-side, never just hidden buttons): the PIN is BCrypt-hashed; five wrong attempts lock it
  (30 s, doubling to a 15 min cap) with the counters persisted in the database; entering the PIN opens a session with
  a 10-minute idle timeout and an explicit Lock; everything under `/api/owner/**` is refused without that session.
  BCrypt comes from `spring-security-crypto`; the session gate is a small interceptor rather than the full Spring
  Security filter chain (no login page, CSRF or default users to configure).
- **Owner order changes** apply to *today's* finalized orders only. Edits (payment, quantities, extra items) adjust
  stock by the difference and refuse to oversell; a void puts all stock back and keeps the order in the books as
  `VOIDED`; a reprint carries a `** REPRINT **` marker. Each action needs a reason and writes an audit row in the
  same transaction.
- **Audit log is append-only by type**: its repository exposes no delete or update. PINs and hashes are never
  written to it.

## Reports

Owner-only (`/api/owner/reports/{today,days,weeks,months}`). Revenue counts finalized orders only; voided orders are
excluded and shown as a separate note on the Today report. Rows are bucketed in plain Java by
**Africa/Kigali calendar day / Monday-start week / month**, never by the UTC date stored in the database, so a sale at
00:30 Kigali time lands on the new day. The last bucket is the current (partial) period, and empty days, weeks and
months still appear as zero rows. Print uses a stacked 72mm layout. Cashiers see only the Top-5 best sellers by
quantity on the POS screen, never revenue per item.

## Cashier: Stock & sales today

The POS screen shows today's counter strip (orders, total, Cash, MoMo) and, below Today's Orders, a **Stock & sales
today** panel: every active menu item with how many were **sold today** and how many are **left**, out-of-stock items
first, then low stock, then A-Z. It is read-only and needs no PIN (`GET /api/stock/today`), and it carries **quantities
only**: the response has no price or revenue field, so item revenue stays owner-only. Only finalized orders count, so a
void removes the sale and puts the stock back. Restocking stays an owner action.

Tapping a menu item increments that item's plain line (no add-ons); once a line has add-ons, the next tap starts a new
plain line, so "Kofta + Onion" and a plain "Kofta" never merge. Stock is checked against the item's total across all of
its lines.

## Excel export and backups

Owner-only **Data** button. *Export to Excel* downloads `EpicIT-Report-YYYY-MM-DD.xlsx` (sheets: Summary, Today,
Last 7 Days, Last 4 Weeks, Last 12 Months; real numeric cells, item breakdown per period) and keeps a copy in the
backup folder. *Back up now* uses H2's online `BACKUP TO`, repackaged as `epicit-pos-backup-YYYY-MM-DD.zip`
(`epicit-pos.mv.db` + `manifest.json`), written under a temporary name then renamed so a crash never leaves a
half-written backup. A silent snapshot runs a few seconds after every start (a failure is only logged). Only the
newest 30 backups are kept; other files in the folder are never touched. The folder comes from `config.properties`.

## Restore

Owner > Data > *Restore* next to a backup. The zip is unpacked into a staging folder and opened read-only to prove it
is a real Epic IT POS database that is not newer than this app; nothing live is touched yet. The owner then closes and
reopens the POS: before the database opens, the chosen file is swapped in and the old database is kept next to it as
`epicit-pos.mv.db.before-restore-<timestamp>`. The next run writes a `RESTORE` audit row. *Cancel restore* drops a
staged restore. Only names from the backup list are accepted (no paths). Restoring a backup from before a PIN change
brings back the old PIN.

## Restore wizard (database will not open)

Opening the database *is* the integrity check (NFR-6). After single-instance check and any pending restore, `main()`
probes the H2 file with plain JDBC **before Spring starts** (`recovery/DatabaseHealth`): `OK`, `LOCKED` (another process
holds it: your data is fine, the app tells you to close the other copy and offers no restore) or `BROKEN` (H2 refused it,
or the file is zero bytes, which H2 would otherwise treat as a brand-new empty database). On `BROKEN` the POS is not
served. A tiny stand-alone server (JDK `HttpServer`, no Spring, no database) opens the **restore wizard** in the browser:
it lists the backups newest first, asks for confirmation, and uses the same validate-and-swap code as Owner > Data >
Restore. The broken file is kept as `epicit-pos.mv.db.before-restore-<timestamp>`; nothing is deleted. The database is
probed again after the swap, so the app never starts on a file that still will not open. No PIN is asked (the PIN lives
in the broken database): the control is physical access to the laptop, as for the PIN reset. The wizard listens on
loopback only, checks the `Host` header, needs a per-run token on every POST and loads no external asset. The next normal
start writes the `RESTORE` audit row. "Close without restoring" leaves everything untouched.

## Menu and stock management

Owner > Menu. Add and edit items (name, price, low-stock level, colour), **restock** in two modes (*Add or remove some*: a
change, never below zero; or *Set to an exact number*: the counted stock, 0 or more, for a physical count and the
day-one cutover; both are one atomic `UPDATE` so they cannot overwrite a sale finalizing at the same moment, and both
write an audit row with the old and new stock) and
**deactivate / bring back** items and add-ons. Nothing is ever deleted: deactivated items leave the POS grid but stay
referenced by past orders and reports. New items take the **next unused colour** of v1's 13-colour palette among *active*
items (a deactivated item frees its colour), never `menu.length % N`. A price change writes a `PRICE_CHANGE` audit row,
any other change a `MENU_CHANGE` row, in the same transaction; past orders keep the price they were sold at. Names must be
unique among active items and add-ons (case-insensitive).

## Packaging (portable Windows app)
The release is a zip with a folder containing `Epic it POS.exe`, the app and a bundled Java 21 runtime (jpackage `--type app-image`: no installer, no admin rights). jpackage cannot cross-compile, so it is built on Windows by `.github/workflows/release.yml`:

- Actions > **Release** > Run workflow (zip appears under the run's Artifacts), or push a tag `v2.0.0` (also creates a GitHub release).
- The workflow runs `mvn verify`, `packaging/build-app-image.ps1`, then `packaging/smoke-test.ps1`, which boots the real exe, checks `/ping` and the POS page, closes it via the owner endpoint and checks it exits cleanly.
- Locally on Windows with JDK 21: `.\packaging\build-app-image.ps1 -Version 2.0.0`.
- The exe is unsigned, so SmartScreen warns once; see `packaging/README-FIRST.txt` and `docs/user-manual.md`.
- The app has no console: logs go to `<data dir>\logs\epicit-pos.log`; start-up times to `startup-times.log`. See `docs/field-test-guide.md`.

## Settings, first run and sound

First launch asks for the restaurant name and owner PIN (no Cancel). Owner > Settings: restaurant name, low-stock
default, backup folder (saved to `config.properties`, validated writable), mute, change PIN. Changes are audited as
`SETTINGS_CHANGE`. Three bundled sounds (item added, blocked action, order finalized after the commit) play
from `/sounds/*.wav`; nothing is fetched from the network.

## Forgotten PIN

Create an empty file named `RESET-PIN` in the data folder (`%LOCALAPPDATA%\EpicIT POS\`) and restart the app. On
startup the PIN is removed, the lockout cleared and the file deleted; the POS shows a red "PIN was reset on ..."
banner until a new PIN is set, and a `PIN_RESET` entry is written to the audit log. The control is physical access
to the laptop; there are no recovery codes, security questions or developer backdoors. Sales continue normally in
cashier mode meanwhile.

## Run locally

Requires JDK 21 and Maven.

```bash
mvn spring-boot:run     # opens your browser on the POS screen
mvn verify              # build + all tests
```

Dev data goes to `%LOCALAPPDATA%\EpicIT POS` on Windows or `~/.epicit-pos` elsewhere. Override with
`-Depicit.data-dir=/some/folder`.

## Project layout

```
src/main/java/com/epicit/pos
  config/    AppPaths, AppConfig (config.properties), bean wiring
  startup/   single-instance guard, port file, browser launcher
  domain/    JPA entities (menu_item, addon, orders, order_line, order_line_addon, audit_log, settings)
  repo/      Spring Data repositories
  service/   OrderService (order flow), OwnerOrderService, ReportService, ExcelExportService, MenuManagementService, PinService, AuditService, today's counter/orders, StockTodayService (cashier stock panel)
  backup/    BackupService (zip snapshots + rotation), StartupSnapshot, RestoreService + RestoreApplier
  recovery/  DatabaseHealth (open-the-file probe), RecoveryServer (restore wizard), SnapshotRecovery, RecoveryLauncher
  web/       POS screen, JSON API (/api/order, /api/today, /api/stock/today, /api/orders/today, /api/auth, /api/owner incl. reports), /ping
src/main/resources
  db/migration/   V1 schema, V2 seed defaults
  templates/      Thymeleaf pages
  static/         css + js
```

## Conventions

Conventional commits (`feat:`, `fix:`, `chore:`…), feature branches + PRs into protected `main`, one GitHub
issue per functional requirement with testable acceptance criteria. Never commit real restaurant data;
screenshots and seed data are synthetic.
