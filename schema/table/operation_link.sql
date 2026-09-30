-- Актуализировано по WalletDbHelper.CreateOperationLink (DATABASE_VERSION = 77).
-- Добавлена миграцией v71 (только CREATE TABLE, существующие операции не трогает).
-- Связь проводок одной пользовательской операции, хранимых несколькими
-- строками (разновалютный перевод, комиссия за перевод).
CREATE TABLE IF NOT EXISTS "operation_link" (
  "main_id" INTEGER NOT NULL, -- проводка списания с кошелька-источника — по ней находится вся операция целиком
  "link_id" INTEGER NOT NULL, -- вторая нога перевода или комиссия, связанная с main_id
  type INTEGER NOT NULL DEFAULT 0, -- 0=нога валютного перевода, 1=комиссия (OperationItem.LinkType, v77); заменяет прежний вывод по system_kind счёта-источника
  uuid TEXT NOT NULL DEFAULT (lower(hex(randomblob(16)))), -- идентификатор строки для синка с сервером
  dirty INTEGER NOT NULL DEFAULT 1, -- 1 — строка ещё не отправлена на сервер; сбрасывает в 0 только синк-движок
  deleted_at INTEGER, -- момент мягкого удаления (epoch millis); NULL — строка активна
  updated_by_device TEXT, -- диагностика: устройство, применившее последнее изменение
  PRIMARY KEY ("main_id", "link_id"),
  CONSTRAINT fk_operation_link_main FOREIGN KEY (main_id) REFERENCES operation (_id) ON DELETE CASCADE,
  CONSTRAINT fk_operation_link_link FOREIGN KEY (link_id) REFERENCES operation (_id) ON DELETE CASCADE
);
