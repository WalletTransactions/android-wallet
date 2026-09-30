-- Актуализировано по WalletDbHelper.CreateCurrencyEntry (DATABASE_VERSION = 72).
-- symbol — с изначальной схемы; precision добавлена миграцией v69 (ALTER TABLE, DEFAULT 2).
CREATE TABLE currency
(
	_id INTEGER PRIMARY KEY AUTOINCREMENT, -- локальный идентификатор валюты
	name TEXT NOT NULL Unique, -- название валюты, видимое пользователю
	iso_code TEXT NOT NULL Unique, -- код валюты (например, "USD")
	icon_id INTEGER NOT NULL, -- иконка-флаг/символ этой валюты
	symbol TEXT NOT NULL, -- символ валюты для вывода сумм (например, "$")
	precision INTEGER NOT NULL DEFAULT 2, -- число знаков после запятой в суммах этой валюты
	uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), -- идентификатор строки для синка с сервером
	dirty INTEGER NOT NULL DEFAULT 1, -- 1 — строка ещё не отправлена на сервер; сбрасывает в 0 только синк-движок
	deleted_at INTEGER, -- момент мягкого удаления (epoch millis); NULL — строка активна
	updated_by_device TEXT, -- диагностика: устройство, применившее последнее изменение
	CONSTRAINT fk_currency_icon_id FOREIGN KEY (icon_id) REFERENCES icon (_id)
);