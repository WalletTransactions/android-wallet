# Триггеры

## `trg_sync_dirty.sql`

**Первые настоящие триггеры в этой схеме** (v72, servers/TODO_mobile_sync.md,
Фаза 3) — до этого в клиентской базе не было ни одного `CREATE TRIGGER`,
который бы реально выполнялся приложением (см. черновик `trg_operation.sql`
ниже — единственный, что был, и тот не создаётся).

По одному триггеру `AFTER UPDATE` на каждую из 9 синхронизируемых таблиц
(`account`, `account_operating`, `currency`, `currency_pair`,
`currency_pair_rate`, `currency_conversion`, `icon`, `operation`,
`operation_link`) — форсит `dirty = 1`, если сам `UPDATE` не тронул колонку
`dirty`. Решение принято намеренно триггером, а не правкой каждого места
записи в Java: единого узкого CRUD-слоя в этой схеме на самом деле нет —
минимум 4 места пишут в обход `WalletDbCRUD` напрямую
(`TransactionActivity`, `IconEditDialog`, `RateEditDialog`, `MoreActivity`),
и патчить `dirty=1` в каждом из них означало бы держать полный список мест
записи в голове при каждой новой правке. Подробности механизма (`WHEN
NEW.dirty = OLD.dirty`, защита от рекурсии) — в комментарии самого файла.

## `trg_operation.sql`

**Черновик, ничем не создаётся** — обслуживает несуществующую таблицу
`account_balance` (см. `../table/README.md`, «Черновики»), от идеи
материализованного баланса по месяцам отказались в пользу расчёта на лету
(`WalletDbLoader.LoadBalancesPerWallet`). Оставлен как исторический контекст.
