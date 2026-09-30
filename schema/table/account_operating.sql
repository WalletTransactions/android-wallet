-- Актуализировано по WalletDbHelper.CreateAccountOperatingEntry (DATABASE_VERSION = 72).
-- currency_id — INTEGER, а не TEXT: старое объявление типа было ошибочным для FK на currency._id.
-- Расширение account для кошельков: строка есть — счёт операционный (есть
-- валюта), строки нет — это категория. _id всегда равен account._id.
CREATE TABLE account_operating
(
	_id INTEGER PRIMARY KEY, -- = account._id того же счёта
	currency_id INTEGER NOT NULL, -- валюта этого кошелька
	uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), -- ВСЕГДА равен uuid того же account (см. wallet-api/schema/table/account_operating.sql, "uuid здесь всегда равен account.uuid") — DEFAULT здесь только заглушка на случай CREATE TABLE без явной вставки колонки, каждый настоящий писатель (InsertOperatingAccount/WalletFillData/WalletImportData/backfillAccountOperatingUuid) обязан явно передать account.uuid, не полагаться на него. Инвариант нарушался какое-то время (до коммита "openapi", 2026-09-05) — все три писателя генерировали свой случайный uuid; уже существующие сломанные строки чинит WalletDbHelper.repairAccountOperatingUuidMismatch (v74), см. её javadoc.
	dirty INTEGER NOT NULL DEFAULT 1, -- 1 — строка ещё не отправлена на сервер; сбрасывает в 0 только синк-движок
	deleted_at INTEGER, -- момент мягкого удаления (epoch millis); NULL — строка активна
	updated_by_device TEXT, -- диагностика: устройство, применившее последнее изменение
	CONSTRAINT fk_account_operating_currencies_id FOREIGN KEY (currency_id) REFERENCES currency (_id),
	CONSTRAINT fk_account_operating_id FOREIGN KEY (_id) REFERENCES account (_id)
);