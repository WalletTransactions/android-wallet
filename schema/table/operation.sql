-- Актуализировано по WalletDbHelper.CreateOperationEntry_v_3 (DATABASE_VERSION = 72).
-- Единственная таблица транзакций. Любая операция — перевод между двумя
-- account: расход "кошелёк → категория", доход "категория → кошелёк",
-- перевод "кошелёк → кошелёк".
CREATE TABLE "operation" (
  "_id" INTEGER PRIMARY KEY AUTOINCREMENT, -- локальный идентификатор операции
  "date" DATETIME NOT NULL, -- дата и время операции, локальное время устройства
  "acc_fr_id" INTEGER NOT NULL, -- счёт списания (источник проводки)
  "acc_to_id" INTEGER NOT NULL, -- счёт зачисления (получатель проводки)
  "amount" DECIMAL(15,2) NOT NULL, -- сумма проводки; тип DECIMAL номинальный — SQLite хранит и считает как REAL/double
  "comment" TEXT NOT NULL, -- свободный комментарий пользователя
  uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), -- идентификатор строки для синка с сервером
  dirty INTEGER NOT NULL DEFAULT 1, -- 1 — строка ещё не отправлена на сервер; сбрасывает в 0 только синк-движок
  deleted_at INTEGER, -- момент мягкого удаления (epoch millis); NULL — строка активна
  updated_by_device TEXT, -- диагностика: устройство, применившее последнее изменение
  CONSTRAINT fk_acc_fr_id FOREIGN KEY (acc_fr_id) REFERENCES account (_id),
  CONSTRAINT fk_acc_to_id FOREIGN KEY (acc_to_id) REFERENCES account (_id)
);

-- v83 (WalletDbHelper.createOperationUuidUnique): одна операция на uuid, написание
-- с дефисами и без, любой регистр — как ищет pull (SyncEngine.localUuid).
CREATE UNIQUE INDEX ux_operation_uuid ON operation (lower(replace(uuid, '-', '')));