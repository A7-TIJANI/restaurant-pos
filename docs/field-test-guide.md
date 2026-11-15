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

## 4. Record
| Item | Result |
|---|---|
| Windows version / RAM / disk type | |
| SmartScreen shown? Antivirus complaint? | |
| Cold start ms / stopwatch s | |
| Warm starts ms (5) | |
| Receipt printed on the right printer? | |
| Close POS and restart OK? | |
| Restore of a backup OK? | |
