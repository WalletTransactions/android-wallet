-- ВНИМАНИЕ: этой таблицы нет в текущей схеме приложения (WalletDbHelper /
-- WalletContract, DATABASE_VERSION = 71) — ни CREATE TABLE, ни чтение/запись
-- по имени account_balance в Java-коде не встречаются. FK ниже к тому же
-- ссылается на несуществующую таблицу operating_account (правильное имя —
-- account_operating), что подтверждает: это не работающая часть схемы.
-- Похоже на заготовку материализованного кэша баланса (см. triggers/trg_operation.sql),
-- от которой отказались в пользу расчёта на лету — см. GetBalanceToDate.sql /
-- WalletDbLoader.LoadBalancesPerWallet и раздел «Расчёт балансов» в ARCHITECTURE.md.
-- Файл оставлен как исторический черновик; актуализировать нечем, пока идея не реализована.
CREATE TABLE `account_balance` (
  `date` DATETIME, -- дата, на которую закэширован баланс (часть составного ключа)
  `operating_account_id` integer, -- предполагаемая ссылка на кошелёк (FK ниже ссылается на несуществующую таблицу, см. выше)
  `amount` DECIMAL(15,2), -- баланс в валюте кошелька
  `amount_rc` DECIMAL(15,2), -- баланс в валюте пересчёта (эквиваленте)
  CONSTRAINT pk_account_balance PRIMARY KEY (`date`, operating_account_id),
  CONSTRAINT fk_account_balance_operating_account_id FOREIGN KEY (operating_account_id) REFERENCES operating_account (_id)
);