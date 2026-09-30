-- Актуализировано по WalletDbHelper.onCreate (DATABASE_VERSION = 72).
-- Иконка хранится как BLOB (WEBP 256x256), а не как ссылка на ресурс: старое поле
-- resource относится к более раннему дизайну, когда иконки лежали в drawable.
-- name объявлена INT, хотя по факту это подпись/идентификатор иконки — так исторически
-- задано в WalletContract.IconEntry.COLUMN_NAME и не менялось.
--
-- Удаление иконки, на которую есть ссылка, запрещает внешний ключ, а не код:
-- account.icon_id (fk_account_icon_id) и currency.icon_id (fk_currency_icon_id)
-- ссылаются на icon._id, и при включённом PRAGMA foreign_keys = ON (см.
-- WalletDbHelper.getWritableDatabase) DELETE, нарушающий эти FK, падает с
-- SQLiteConstraintException — IconEditDialog.deleteIcon только ловит её и
-- показывает сообщение, отдельной проверки COUNT(*) в коде больше нет.
CREATE TABLE icon
(
	_id INTEGER PRIMARY KEY AUTOINCREMENT, -- локальный идентификатор иконки
	name INT NOT NULL Unique, -- по факту подпись/идентификатор иконки, а не число — см. комментарий выше
	image BLOB NOT NULL, -- само изображение (WEBP 256x256)
	icon_type INT NOT NULL, -- код типа иконки (0=категория/1=флаг у Icon.IconType; встречаются и другие значения)
	uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), -- идентификатор строки для синка с сервером
	dirty INTEGER NOT NULL DEFAULT 1, -- 1 — строка ещё не отправлена на сервер (push иконок пока не реализован сервером, см. org/docs/TODO_mobile_sync.md — колонка заведена для единообразия)
	deleted_at INTEGER, -- момент мягкого удаления (epoch millis); NULL — строка активна
	updated_by_device TEXT -- диагностика: устройство, применившее последнее изменение
);