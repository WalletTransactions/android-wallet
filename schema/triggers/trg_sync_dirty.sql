-- Первые НАСТОЯЩИЕ триггеры в клиентской схеме (v72, org/docs/TODO_mobile_sync.md,
-- Фаза 3) — trg_operation.sql рядом остаётся черновиком, ничем не создаётся.
--
-- Гарантируют dirty=1 при любом изменении строки, независимо от того, какой
-- именно код-путь его сделал. Понадобилось, потому что единого узкого слоя
-- записи в этой схеме на самом деле нет: минимум 4 места пишут в обход
-- WalletDbCRUD напрямую (TransactionActivity, IconEditDialog, RateEditDialog,
-- MoreActivity) — патчить dirty=1 в каждом из них вручную означало бы держать
-- в голове полный список мест записи и держать его в актуальном состоянии
-- при каждой новой правке. Триггер снимает эту заботу целиком.
--
-- WHEN NEW.dirty = OLD.dirty — единственный надёжный признак "этот UPDATE не
-- упоминал колонку dirty вообще" (в SQLite: если SET не трогает колонку,
-- NEW.<col> остаётся равен OLD.<col>) — именно тогда триггер форсит dirty=1.
-- Синк-движок, применяющий входящие изменения с сервера, обязан ВСЕГДА явно
-- писать dirty=0 в своём UPDATE (даже если он уже был 0) — тогда NEW.dirty,
-- как правило, не совпадёт с OLD.dirty (обычно OLD=1, если строка была
-- локально изменена и теперь перезаписывается сервером), триггер не
-- срабатывает вовсе, и синк не зацикливается сам на себя.
--
-- AND dirty <> 1 в теле останавливает рекурсию: вложенный UPDATE тоже
-- попадает под этот же AFTER UPDATE, но второй раз строка уже dirty=1 и под
-- условие не подходит.
--
-- Для таблиц с составным первичным ключом (currency_pair_rate/
-- currency_conversion/operation_link, нет отдельной _id) корректирующий
-- UPDATE адресует строку по всем колонкам ключа сразу.

CREATE TRIGGER IF NOT EXISTS trg_account_dirty
AFTER UPDATE ON account
WHEN NEW.dirty = OLD.dirty
BEGIN
  UPDATE account SET dirty = 1 WHERE _id = NEW._id AND dirty <> 1;
END;

CREATE TRIGGER IF NOT EXISTS trg_account_operating_dirty
AFTER UPDATE ON account_operating
WHEN NEW.dirty = OLD.dirty
BEGIN
  UPDATE account_operating SET dirty = 1 WHERE _id = NEW._id AND dirty <> 1;
END;

CREATE TRIGGER IF NOT EXISTS trg_currency_dirty
AFTER UPDATE ON currency
WHEN NEW.dirty = OLD.dirty
BEGIN
  UPDATE currency SET dirty = 1 WHERE _id = NEW._id AND dirty <> 1;
END;

CREATE TRIGGER IF NOT EXISTS trg_currency_pair_dirty
AFTER UPDATE ON currency_pair
WHEN NEW.dirty = OLD.dirty
BEGIN
  UPDATE currency_pair SET dirty = 1 WHERE _id = NEW._id AND dirty <> 1;
END;

CREATE TRIGGER IF NOT EXISTS trg_icon_dirty
AFTER UPDATE ON icon
WHEN NEW.dirty = OLD.dirty
BEGIN
  UPDATE icon SET dirty = 1 WHERE _id = NEW._id AND dirty <> 1;
END;

CREATE TRIGGER IF NOT EXISTS trg_operation_dirty
AFTER UPDATE ON operation
WHEN NEW.dirty = OLD.dirty
BEGIN
  UPDATE operation SET dirty = 1 WHERE _id = NEW._id AND dirty <> 1;
END;

CREATE TRIGGER IF NOT EXISTS trg_currency_pair_rate_dirty
AFTER UPDATE ON currency_pair_rate
WHEN NEW.dirty = OLD.dirty
BEGIN
  UPDATE currency_pair_rate SET dirty = 1
   WHERE currency_pair_id = NEW.currency_pair_id AND date = NEW.date AND dirty <> 1;
END;

CREATE TRIGGER IF NOT EXISTS trg_currency_conversion_dirty
AFTER UPDATE ON currency_conversion
WHEN NEW.dirty = OLD.dirty
BEGIN
  UPDATE currency_conversion SET dirty = 1
   WHERE from_currency_id = NEW.from_currency_id
     AND to_currency_id = NEW.to_currency_id
     AND num = NEW.num
     AND dirty <> 1;
END;

CREATE TRIGGER IF NOT EXISTS trg_operation_link_dirty
AFTER UPDATE ON operation_link
WHEN NEW.dirty = OLD.dirty
BEGIN
  UPDATE operation_link SET dirty = 1
   WHERE main_id = NEW.main_id AND link_id = NEW.link_id AND dirty <> 1;
END;
