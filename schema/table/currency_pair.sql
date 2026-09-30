-- Актуализировано по WalletDbHelper.CreateRate (DATABASE_VERSION = 72).
-- precision добавлена миграцией v70 (ALTER TABLE, DEFAULT 5) — знаков после запятой в курсе пары.
CREATE TABLE `currency_pair` (
  `_id` integer  PRIMARY KEY AUTOINCREMENT, -- локальный идентификатор пары
  `symbol` TEXT  NOT NULL Unique, -- обозначение пары для отображения (например, "EUR/USD")
  `base_currency_id` integer NOT NULL, -- базовая валюта пары (курс задаётся как "квота за 1 базовую единицу")
  `quote_currency_id` integer NOT NULL, -- котируемая валюта пары
  `precision` integer NOT NULL DEFAULT 5, -- число знаков после запятой в курсах этой пары
  uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), -- идентификатор строки для синка с сервером
  dirty INTEGER NOT NULL DEFAULT 1, -- 1 — строка ещё не отправлена на сервер; сбрасывает в 0 только синк-движок
  deleted_at INTEGER, -- момент мягкого удаления (epoch millis); NULL — строка активна
  updated_by_device TEXT, -- диагностика: устройство, применившее последнее изменение
   CONSTRAINT fk_currency_pair_base_currency_id FOREIGN KEY (base_currency_id) REFERENCES currency (_id),
   CONSTRAINT fk_currency_pair_quote_currency_id FOREIGN KEY (quote_currency_id) REFERENCES currency (_id)
);