# Field test guide (Week 8)

Goal: prove on a **fresh Windows laptop** that the portable app starts, is fast enough (NFR-11: under 5 s), and is usable by the owner without help.

## 1. Get the build
GitHub > Actions > **Release** > *Run workflow* (or push a tag like `v2.0.0`). The run builds, runs all tests, packages the app, and **smoke-tests the real .exe**. Download the zip from the run's Artifacts (or the release page).

## 2. Fresh-box test (no Java, no admin rights)
1. Copy the zip to the laptop, right-click > Extract All.
2. Double-click `Epic it POS.exe`.
3. Expect SmartScreen: *More info* > *Run anyway*. Note whether antivirus complains (write down which product).
4. The browser should open the POS. Create the owner PIN.
5. Set the receipt printer as default, make a test sale, print the receipt.
6. Owner > Close POS. Confirm the window closes and starting again works.

## 3. Measure start-up (do not trust a guess)
Every start appends a line to `%LOCALAPPDATA%\EpicIT POS\startup-times.log`: milliseconds from JVM start to ready.
- Do **1 cold start** (right after a reboot, first run ever) and **5 warm starts**. Record all six.
- Also time with a stopwatch from double-click to the POS page visible; that is what the owner feels.
- Target: under 5 s warm. If cold is slower, report both numbers.
- If it misses the target, send the log. Do not tune JVM options before measuring.

## 4. Feature check on the owner's laptop
Use synthetic test orders, then clear them (see section 6). Tick each line.
- [ ] Menu shows the real items and prices; the colours are readable on the owner's screen.
- [ ] Tap an item twice: one line, quantity 2.
- [ ] Add an add-on to a line, then tap the same item again: a **new plain line** appears and the add-on line is unchanged (FR-1.1).
- [ ] **Stock & sales today** (below Today's Orders) lists every active item with sold and left; out-of-stock items first.
- [ ] Finalize an order of 2: sold goes up by 2 and left goes down by 2 after the page reloads.
- [ ] The panel and `http://127.0.0.1:<port>/api/stock/today` show no price or revenue anywhere (the port is in `port.txt`).
- [ ] Owner > Restock > **Set to an exact number**: stock becomes exactly that number; a negative number is refused.
- [ ] Owner > Restock > **Add or remove some**: `+5` and `-2` work; removing more than is in stock is refused.
- [ ] Void a test order (Owner, with a reason): its stock comes back and sold-today drops.
- [ ] Out-of-stock item is greyed on the grid and cannot be added.
- [ ] Receipt and kitchen ticket print on the right printer; the add-on line prints under its item.
- [ ] Owner > Data > Back up now creates a zip; Restore of it works after Close POS and restart.
- [ ] Wrong PIN five times locks the owner area; the right PIN works after the wait.

## 5. Day-one cutover (only when the owner is ready to go live)
Follow "Day one: setting up the real stock" in `docs/user-manual.md`. In short: archive the old system's export, count the shelf after closing, add missing items and add-ons, **Set to an exact number** for every item (the seeded items start at 100), and confirm the panel shows 0 sold and the counted stock.
- [ ] Old system export saved somewhere safe (the old data is not imported).
- [ ] Every item's stock matches the physical count.
- [ ] Owner has set their own PIN and knows about the `RESET-PIN` file.
- [ ] Owner pressed **Back up now** after the setup.

## 6. Clear the test data before going live
Test orders and test stock changes are real rows. Either (a) do the field test on a throwaway copy, then delete the data folder `%LOCALAPPDATA%\EpicIT POS` before the real first launch (the first-run wizard starts again), or (b) accept that the test orders stay in the reports. Decide **before** the test, and write down which.

## 7. Record
| Item | Result |
|---|---|
| Windows version / RAM / disk type | |
| SmartScreen shown? Antivirus complaint? | |
| Cold start ms / stopwatch s | |
| Warm starts ms (5) | |
| Receipt printed on the right printer? | |
| Close POS and restart OK? | |
| Restore of a backup OK? | |
| Stock panel, add-on lines and Set-to-exact OK? | |
| Test data cleared / kept? | |
| Owner can run a sale unaided? Questions they asked | |
