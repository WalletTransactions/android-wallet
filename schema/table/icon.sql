-- Актуализировано по WalletDbHelper.onCreate (DATABASE_VERSION = 86).
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
--
-- v86 (openspec server-managed-icons): иконки только скачиваются с сервера (общий набор
-- ведёт администратор на сайте), в POST /sync/push их нет. origin отличает иконки с
-- сервера от своих: у счёта или валюты со своей иконкой в push уходит заместитель
-- (SyncEngine / IconSubstitutes).
CREATE TABLE icon
(
	_id INTEGER PRIMARY KEY AUTOINCREMENT, -- локальный идентификатор иконки
	name INT NOT NULL, -- по факту подпись/идентификатор иконки, а не число — см. комментарий выше; уникальность среди живых — частичный индекс uq_icon_name (deleted_at IS NULL)
	image BLOB NOT NULL, -- само изображение (WEBP 256x256)
	icon_type INT NOT NULL, -- код типа иконки (0=категория/1=флаг у Icon.IconType; встречаются и другие значения)
	origin INTEGER NOT NULL DEFAULT 0, -- v86: 0 — с сервера (пришла через pull или ушла на сервер до v86), только просмотр; 1 — своя (галерея, seed.json), на сервер не отправляется
	uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), -- идентификатор строки для синка с сервером
	dirty INTEGER NOT NULL DEFAULT 1, -- с v86 для иконок ничего не значит: иконки в push не уходят (миграция v86 обнулила её у всех); триггер trg_icon_dirty по-прежнему ставит 1 на любой UPDATE
	deleted_at INTEGER, -- момент мягкого удаления (epoch millis); NULL — строка активна
	updated_by_device TEXT -- диагностика: устройство, применившее последнее изменение
);
