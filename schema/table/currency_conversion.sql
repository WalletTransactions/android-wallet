-- Шаги маршрутов пересчёта валют (v93, openspec currency-conversion-routes). Маршрут from -> to —
-- цепочка строк с num 1..3; каждая называет пару и направление шага (is_reverse). Курс по
-- маршруту считает DerivedRates (кэш currency_rate_derived), см. README.md, раздел
-- "currency_conversion". Мягко удалённые строки при повторном создании оживляются (тот же
-- uuid): первичный ключ не учитывает deleted_at.
CREATE TABLE currency_conversion (
  from_currency_id integer, -- валюта, из которой считается весь маршрут (одинакова у всех его шагов)
  "to_currency_id" integer, -- валюта, в которую считается весь маршрут
  "num" integer, -- номер шага, 1..3
  "currency_pair_id" integer, -- пара, курс которой используется на этом шаге
  "is_reverse" integer, -- 0 — шаг от базовой валюты пары к котируемой, 1 — наоборот
  uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), -- идентификатор строки для синка с сервером
  dirty INTEGER NOT NULL DEFAULT 1, -- 1 — строка ещё не отправлена на сервер; сбрасывает в 0 только синк-движок
  deleted_at INTEGER, -- момент мягкого удаления (epoch millis); NULL — строка активна
  updated_by_device TEXT, -- диагностика: устройство, применившее последнее изменение
  CONSTRAINT pk_currency_conversion PRIMARY KEY (from_currency_id, to_currency_id, num),
  CONSTRAINT fk_currency_conversion_from_currency_id FOREIGN KEY (from_currency_id) REFERENCES currency (_id),
  CONSTRAINT fk_currency_conversion_to_currency_id FOREIGN KEY (to_currency_id) REFERENCES currency (_id),
  CONSTRAINT fk_currency_conversion_currency_pair_id FOREIGN KEY (currency_pair_id) REFERENCES currency_pair (_id),
  CONSTRAINT ck_currency_conversion_num CHECK (num BETWEEN 1 AND 3),
  CONSTRAINT ck_currency_conversion_sides CHECK (from_currency_id <> to_currency_id)
);

-- Кэш составных курсов (маршрут, кросс через USD); не синкается, перестраивается DerivedRates.
CREATE TABLE currency_rate_derived (
  from_currency_id INTEGER NOT NULL, to_currency_id INTEGER NOT NULL,
  date TEXT NOT NULL, rate REAL NOT NULL,
  PRIMARY KEY (from_currency_id, to_currency_id, date)) WITHOUT ROWID;

-- Флаг «кэш устарел» (одна строка); ставят триггеры на currency_pair_rate / currency_pair /
-- currency / currency_conversion, сбрасывает DerivedRates.rebuild.
CREATE TABLE derived_rates_state (_id INTEGER PRIMARY KEY, stale INTEGER NOT NULL);
