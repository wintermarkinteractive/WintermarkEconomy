# Changelog

## 1.0.0 - 2026-11-25
First public release. Everything from 0.1.0 and 0.2.0:

- Balances, `/pay`, `/baltop` (cached, pages, own rank, server total), `/eco give|take|set|reset|history|reload` (console-friendly, offline players, `give *`).
- Abbreviations in input (`5k`, `2.5m`, `1b`, `1t`), exact and compact display, currency name/symbol/format/decimals.
- Anti-dupe banknotes (`/withdraw`): unique id per note in the database, one redemption, copies removed.
- Transaction history for players and staff (PAY, ADMIN, NOTE, API, VAULT, BOUNTY, AUCTION).
- Pay safety: click-to-confirm, `/paytoggle`, cooldown, minimum amount, optional offline payments.
- SQLite storage, in-memory cache, background writes, whole-cent amounts.
- Optional Vault/VaultUnlocked economy provider and a Java API (`EconomyAPI`).

## 0.1.0 (2026-11-25)
- Accounts in SQLite (`plugins/WintermarkEconomy/economy.db`), all balances cached in memory, writes on a background thread.
- Money stored as whole minor units (no rounding bugs). `currency.decimals` 0-4; changing it converts balances on the next start.
- `/balance [player]` (`/bal`, `/money`), `/pay <player> <amount>`, `/baltop [page]` (cached, refreshed every 5 min).
- `/eco give|take|set|reset <player> <amount>` (console-friendly, offline players, `give *` = everyone online), `/eco reload`.
- Abbreviations in input (`5k`, `2.5m`, `1b`, `1t`), exact and compact display (`$1.2M`).
- Java API `EconomyAPI` registered in Bukkit's ServicesManager.

## 0.2.0 (2026-11-25)
- **Banknotes:** `/withdraw <amount>` (`/banknote`) gives a glowing note signed by you. Right-click to deposit, sneak + right-click deposits every note in your inventory. Anti-dupe: each note has a unique id in the database and can be redeemed once; copies are removed and logged. The value is read from the database, not from the item. Notes can't be used in crafting.
- **Transaction history:** `/money history [page]` (own), `/eco history <player> [page]` (staff). Types PAY, ADMIN, NOTE, API, VAULT (+ BOUNTY/AUCTION via the API reason prefix). Old entries are cleaned up after `history.keep-days`.
- **Pay safety:** click-to-confirm above `pay.confirm-above` (expires after `pay.confirm-seconds`), `/paytoggle`, `pay.cooldown-seconds`.
- **Vault hook:** registers as the Vault economy provider when Vault or VaultUnlocked is installed (`vault.enabled`).
- New permissions: `wintermark.eco.withdraw`, `wintermark.eco.paytoggle` (default true), `wintermark.eco.bypass.cooldown` (op).
