-- Ручное переопределение маршрута пересчёта одной валюты в другую, когда
-- прямой пары нет. Ни один код-путь в неё не пишет, вьюхи всегда резолвят
-- курс через currency_pair — см. README.md, раздел "currency_conversion".
CREATE TABLE currency_conversion (
  from_currency_id integer, -- исходная валюта пересчёта
  "to_currency_id" integer, -- целевая валюта пересчёта
  "num" integer, -- номер альтернативного маршрута между этой парой валют
  "currency_pair_id" integer, -- пара, курс которой использовать для этого маршрута
  "is_reverse" integer, -- 1 — использовать обратный курс пары, 0 — прямой
  uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), -- идентификатор строки для синка с сервером
  dirty INTEGER NOT NULL DEFAULT 1, -- 1 — строка ещё не отправлена на сервер; сбрасывает в 0 только синк-движок
  deleted_at INTEGER, -- момент мягкого удаления (epoch millis); NULL — строка активна
  updated_by_device TEXT, -- диагностика: устройство, применившее последнее изменение
  PRIMARY KEY ("from_currency_id", "to_currency_id", num),
  CONSTRAINT fk_currency_conversion_from_currency_id FOREIGN KEY (from_currency_id) REFERENCES currency (_id),
  CONSTRAINT fk_currency_conversion_to_currency_id FOREIGN KEY (to_currency_id) REFERENCES currency (_id),
  CONSTRAINT fk_currency_conversion_currency_pair_id FOREIGN KEY (currency_pair_id) REFERENCES currency_pair (_id)
);