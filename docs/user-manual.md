# Epic IT POS: user manual

## Starting
Double-click **Epic it POS.exe**. The POS opens in your browser. No installation and no internet are needed.

The first time, Windows may show **"Windows protected your PC"** (SmartScreen). The program is new to Microsoft, it is not a virus.
Click **More info**, then **Run anyway**. This is needed only once.

## Before taking orders
Set the 72 mm receipt printer as the Windows **default printer** (Settings > Bluetooth & devices > Printers & scanners > the printer > *Set as default*). Receipts print to the default printer.

## Closing
Unlock the owner area and press **Close POS**. Closing the browser tab does not stop the POS; if you closed the tab by mistake, double-click **Epic it POS.exe** again and it reopens the same window.

## Menu and stock (owner)
Owner area > **Menu**: add items, change prices, restock (use a minus sign to remove stock), and deactivate items. Nothing is ever deleted, so old receipts stay correct; a deactivated item can be reactivated.

## Backups and restore
A backup is made every time the POS starts, plus the ones you create from the owner area. To go back to an earlier backup use Owner area > Restore, then **Close POS** and start it again: the restore is applied at start.

If the POS cannot open its database, it shows a recovery page instead of the POS. Pick a backup from the list and follow the steps.

## Where is my data?
`%LOCALAPPDATA%\EpicIT POS` (paste that into the File Explorer address bar). It holds the database, the backups and `logs\epicit-pos.log`. Do not delete this folder. To move to another laptop, copy it.

## If something goes wrong
Send `logs\epicit-pos.log` and `startup-times.log` from the data folder.
