# Epic IT POS (v2)

Offline-first desktop point-of-sale system for restaurants, rewritten from a single-file browser prototype
(v1) onto a real SQL database. Zero-install on Windows: one folder, double-click the `.exe`.

> **Status:** early development. Done: foundations (weeks 1-2), order flow (weeks 3-4), owner security and order
> management (week 5), reports (week 6: today with item breakdown, 7 days, 4 weeks, 12 months, 72mm print,
> cashier Top-5). Next: Excel export, backup/restore, first-run wizard, settings, sound.

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
  service/   OrderService (order flow), OwnerOrderService, ReportService, PinService, AuditService, today's counter/orders
  web/       POS screen, JSON API (/api/order, /api/today, /api/orders/today, /api/auth, /api/owner incl. reports), /ping
src/main/resources
  db/migration/   V1 schema, V2 seed defaults
  templates/      Thymeleaf pages
  static/         css + js
```

## Conventions

Conventional commits (`feat:`, `fix:`, `chore:`…), feature branches + PRs into protected `main`, one GitHub
issue per functional requirement with testable acceptance criteria. Never commit real restaurant data;
screenshots and seed data are synthetic.
