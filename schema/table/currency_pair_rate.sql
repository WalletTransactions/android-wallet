-- Курс пары на дату: квота за 1 базовую единицу.
CREATE TABLE `currency_pair_rate` (
  `currency_pair_id` integer, -- пара, к которой относится этот курс
  `date` DATETIME, -- дата, на которую действует курс (часть составного первичного ключа вместе с currency_pair_id)
  `rate` DECIMAL(12,5), -- значение курса; обратный курс считается делением 1.0/rate, отдельно не хранится
  uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), -- идентификатор строки для синка с сервером
  dirty INTEGER NOT NULL DEFAULT 1, -- 1 — строка ещё не отправлена на сервер; сбрасывает в 0 только синк-движок
  deleted_at INTEGER, -- момент мягкого удаления (epoch millis); NULL — строка активна
  updated_by_device TEXT, -- диагностика: устройство, применившее последнее изменение
  CONSTRAINT pk_currency_pair_rate PRIMARY KEY (currency_pair_id, date),
  CONSTRAINT fk_currency_pair_rate_currency_pair_id FOREIGN KEY (currency_pair_id) REFERENCES currency_pair (_id)
);