-- Доход за период — сумма поступлений на "операционные" счета (кошельки) из
-- "категорийных" счетов (источников дохода), приведённая к валюте эквивалента.
--
-- Направление, а не знак суммы, отличает доход от расхода: строка v_operation_eq
-- считается доходом, когда деньги идут ИЗ категории (acc_from_is_operating = 0)
-- В кошелёк (acc_to_is_operating = 1). "amount"/"amount_eq" в operation всегда
-- хранится как положительное число, введённое пользователем — см.
-- TransactionActivity.SaveClick.
--
-- Это ровно та же логика, что и в WalletDbLoader.GenerateIncomeReportByAccount,
-- только сразу общая сумма за период — без разбивки по счёту-источнику (нужна
-- для ячейки "Доход" в шапке главного экрана; разбивка по счетам — это то, что
-- показывает экран "Отчёт за период").
--
-- Параметры (подставить перед запуском):
--   :date_from      -- нижняя граница периода, включительно, формат 'yyyy-MM-dd HH:mm'
--   :date_to        -- верхняя граница периода, включительно, тот же формат
--   :to_currency_id -- currency._id валюты эквивалента, в которую переводятся суммы

SELECT
    round(sum(o.amount_eq), 2) AS income_eq
FROM v_operation_eq o
WHERE o.date BETWEEN :date_from AND :date_to
  AND o.to_currency_id = :to_currency_id
  AND o.acc_to_is_operating = 1
  AND o.acc_from_is_operating = 0;

-- Разбивка по счёту-источнику (совпадает с тем, что строит
-- GenerateIncomeReportByAccount для списка на экране отчёта):
--
-- SELECT o.acc_fr_id, o.currency_id,
--        round(sum(o.amount), 2)    AS amount,
--        round(sum(o.amount_eq), 2) AS amount_eq
-- FROM v_operation_eq o
-- WHERE o.date BETWEEN :date_from AND :date_to
--   AND o.to_currency_id = :to_currency_id
--   AND o.acc_to_is_operating = 1
--   AND o.acc_from_is_operating = 0
-- GROUP BY o.acc_fr_id, o.currency_id;
