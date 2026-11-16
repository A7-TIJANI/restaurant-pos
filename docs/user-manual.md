# Epic IT POS: user manual

## Step 1: Add the Windows Defender exclusion (do this BEFORE extracting the zip)
The program is new to Microsoft and is not digitally signed yet. Windows Defender scans the Java runtime bundled inside it while it starts, can lock or quarantine those files, and then the program stops with **"Failed to launch JVM"**. Tell Defender to leave the program's folder alone, **once, before you extract the zip**:
1. Create an empty folder where the POS will live, for example `D:\EpicPOS` (or `C:\EpicPOS`).
2. Open **Windows Security** (Start menu, type "Windows Security") > **Virus & threat protection**.
3. Under *Virus & threat protection settings* click **Manage settings**, then scroll to **Exclusions** and click **Add or remove exclusions**.
4. Click **Add an exclusion** > **Folder** and choose the folder from step 1. Accept the Windows permission prompt.
5. Only now, right-click the zip > **Extract All**, and pick that same folder.

Only that folder is excluded. If you already extracted the zip and see "Failed to launch JVM", add the exclusion for the folder, then delete the extracted program folder and extract the zip again, so no file stays quarantined. Your sales data is stored elsewhere (`%LOCALAPPDATA%\EpicIT POS`) and is not touched.

A later version will be digitally signed, which will remove the need for this step.

## Starting
Double-click **Epic it POS.exe**. The POS opens in your browser. No installation and no internet are needed.

The first time, Windows may show **"Windows protected your PC"** (SmartScreen). The program is new to Microsoft, it is not a virus.
Click **More info**, then **Run anyway**. This is needed only once.

## Before taking orders
Set the 72 mm receipt printer as the Windows **default printer** (Settings > Bluetooth & devices > Printers & scanners > the printer > *Set as default*). Receipts print to the default printer.

## Taking an order
Tap a menu item to add it; tap it again to add another. To give an item an add-on, tap its line in the order, then tap the add-on. If you then tap the same item again, it starts a **new line without add-ons**, so "Kofta + Onion" and a plain "Kofta" stay separate on the receipt and the kitchen ticket. Pressing **PRINT INVOICE** prints the customer copy, then the kitchen ticket, and finishes the order.

## Stock & sales today (everyone)
Scroll down on the POS screen, below **Today's Orders**, to **Stock & sales today**. For every item on the menu it shows how many were **sold today** and how many are **left**. Items that are **out** of stock are listed first, then the ones running low. The panel updates after each finished order. You cannot change stock from here, only the owner can. It never shows money per item; the total for the day is in the strip at the top.

## Closing
Unlock the owner area and press **Close POS**. The screen shows **"Shutting down safely..."** with a spinner while the POS saves and releases its data. Wait for **"Epic IT POS is closed"** before you open it again; this normally takes a few seconds. If you do double-click the program too early, it waits a few seconds for the data to be released before it starts. Closing the browser tab does not stop the POS; if you closed the tab by mistake, double-click **Epic it POS.exe** again and it reopens the same window.

## Menu and stock (owner)
Owner area > **Menu**: add items, change prices, restock, and deactivate items. Nothing is ever deleted, so old receipts stay correct; a deactivated item can be reactivated.

**Restock** has two modes:
- **Add or remove some**: type a number to add stock, or use a minus sign to remove some (for example `-3` for spoilage). Stock never goes below zero.
- **Set to an exact number**: type the number you counted on the shelf and the stock becomes exactly that (0 or more). Use it after a stock count.

Both are recorded in the audit log with the old and the new stock.

## Day one: setting up the real stock
Do this once, after closing on the last day you use the old system. Keep the old system's exported data file as an archive; nothing is imported automatically.
1. Count what is physically left of each menu item.
2. Open the new POS, unlock the owner area and go to **Menu**.
3. Add any menu items that are missing and set their prices. The items that come with the program have a placeholder stock of 100, so they must be corrected too.
4. For every item press **Restock**, choose **Set to an exact number**, type the counted number and save.
5. Add your add-ons if they are not there yet.
6. Check **Stock & sales today** on the POS screen: every item should show the counted number as **left** and **0** sold.
From the next day, use **Add or remove some** for deliveries and small corrections.

## Backups and restore
A backup is made every time the POS starts, plus the ones you create from the owner area. To go back to an earlier backup use Owner area > Restore, then **Close POS** and start it again: the restore is applied at start.

If the POS cannot open its database, it shows a recovery page instead of the POS. Pick a backup from the list and follow the steps.

## Where is my data?
`%LOCALAPPDATA%\EpicIT POS` (paste that into the File Explorer address bar). It holds the database, the backups and `logs\epicit-pos.log`. Do not delete this folder. To move to another laptop, copy it.

## If something goes wrong
Send `logs\epicit-pos.log` and `startup-times.log` from the data folder.
