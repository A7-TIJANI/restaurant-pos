EPIC IT POS - READ THIS FIRST
=============================

STEP 1 - BEFORE YOU EXTRACT THE ZIP: ADD A WINDOWS DEFENDER EXCLUSION (once)
  This program is new and not digitally signed yet. Windows Defender scans the Java runtime inside it
  while it starts, can lock or quarantine those files, and the program then says "Failed to launch JVM".
  1. Create an empty folder for the POS, for example  D:\EpicPOS
  2. Open Windows Security > Virus & threat protection > Manage settings.
  3. Scroll to Exclusions > "Add or remove exclusions" > "Add an exclusion" > Folder.
  4. Choose the folder from step 1 and accept the Windows permission prompt.
  5. Only now extract the zip into that folder.
  Already extracted and seeing "Failed to launch JVM"? Add the exclusion, delete the extracted folder and
  extract the zip again.

TO START
  Double-click  "Epic it POS.exe"  in this folder. The POS opens in your web browser.
  (No installation. No internet needed.)

IF WINDOWS SAYS "Windows protected your PC"
  This is normal for a new program that Microsoft has not seen before. It is not a virus.
  1. Click  "More info"
  2. Click  "Run anyway"
  You only need to do this the first time.

BEFORE YOU TAKE ORDERS
  The receipt printer (72 mm) must be the Windows DEFAULT printer:
  Windows Settings > Bluetooth & devices > Printers & scanners > choose the printer > "Set as default".
  (If it is not the default, receipts print on the wrong printer or do not print.)

TO CLOSE
  Unlock the owner area and press "Close POS". Wait for "Epic IT POS is closed" (the screen first says
  "Shutting down safely..."). Closing the browser tab does NOT close the POS.

YOUR DATA
  Your sales are saved on this laptop in:  %LOCALAPPDATA%\EpicIT POS
  Backups are made automatically every time the POS starts.
  Do not delete that folder.
