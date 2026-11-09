# Epic IT POS (v2)

Offline-first desktop point-of-sale system for restaurants, rewritten from a single-file browser prototype
(v1) onto a real SQL database. Zero-install on Windows: one folder, double-click the `.exe`.

> **Status:** early development. Done: foundations (weeks 1-2) and the order flow (weeks 3-4: cart, add-ons,
> two-step print, atomic idempotent finalize, discard, crash-resume). Next: today's orders, owner PIN/session,
> edit/void/reprint, audit log, reports, Excel export, backup/restore.

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
- **Audit log is append-only by type**: its repository exposes no delete or update.

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
  service/   OrderService (order flow), TodayCounterService
  web/       POS screen, JSON API (/api/order, /api/today), /ping
src/main/resources
  db/migration/   V1 schema, V2 seed defaults
  templates/      Thymeleaf pages
  static/         css + js
```

## Conventions

Conventional commits (`feat:`, `fix:`, `chore:`…), feature branches + PRs into protected `main`, one GitHub
issue per functional requirement with testable acceptance criteria. Never commit real restaurant data;
screenshots and seed data are synthetic.
