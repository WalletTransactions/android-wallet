-- Актуализировано по WalletDbHelper.CreateCategory_v_3 / WalletContract.AccountEntry (DATABASE_VERSION = 75).
-- icon_id NOT NULL: в коде иконка всегда подставляется (заглушка "неизвестная",
-- если пользователь не выбрал свою) — NULL в реальных данных не встречается,
-- см. AddWalletActivity / AddCategoryActivity.
--
-- Удаление иконки, на которую ссылается icon_id, блокирует именно этот внешний
-- ключ (fk_account_icon_id) при включённом PRAGMA foreign_keys — см. icon.sql.
-- Единая таблица кошельков и категорий: строка становится кошельком, если для
-- того же _id есть строка в account_operating, иначе это категория. kind (v76)
-- дублирует это явным полем — см. её описание ниже.
CREATE TABLE account
(
	_id INTEGER PRIMARY KEY AUTOINCREMENT, -- локальный идентификатор счёта/категории
	name TEXT NOT NULL Unique, -- название, видимое пользователю; уникально во всей базе
	comment TEXT NOT NULL, -- свободный комментарий пользователя
	icon_id INT NOT NULL, -- иконка счёта/категории (icon._id)
	is_archived INT DEFAULT 0, -- признак только для интерфейса; в расчётах архивный счёт участвует наравне с остальными
	system_kind INT NOT NULL DEFAULT 0, -- 0=обычная категория, 1=Transfer, 2=Transfer commission (AccountItem.SystemKind, v75); заменяет прежнее распознавание по name
	kind INT NOT NULL DEFAULT 0, -- 0=категория, 1=кошелёк (AccountItem.Kind, v76); дублирует наличие строки в account_operating явным полем, проставляется один раз при создании строки и дальше не меняется
	uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), -- идентификатор строки для синка с сервером (org/docs/TODO_mobile_sync.md, Фаза 3)
	dirty INTEGER NOT NULL DEFAULT 1, -- 1 — строка ещё не отправлена на сервер; сбрасывает в 0 только синк-движок
	deleted_at INTEGER, -- момент мягкого удаления (epoch millis); NULL — строка активна
	updated_by_device TEXT, -- диагностика: устройство, применившее последнее изменение
	CONSTRAINT fk_account_icon_id FOREIGN KEY (icon_id) REFERENCES icon (_id)
);
