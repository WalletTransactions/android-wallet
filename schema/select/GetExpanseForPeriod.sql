-- Расход за период — сумма списаний с "операционных" счетов (кошельков) на
-- "категорийные" счета (статьи расходов), приведённая к валюте эквивалента.
--
-- Зеркально GetIncomeForPeriod.sql: расходом строка v_operation_eq считается,
-- когда деньги идут ИЗ кошелька (acc_from_is_operating = 1) В категорию
-- (acc_to_is_operating = 0). Сумма здесь тоже приходит положительной — знак
-- (и красный цвет) на экране расход получает только при выводе, когда
-- AdapterUtils.setAmountText вызывается с явным Operation.expanse.
--
-- Та же логика, что и WalletDbLoader.GenerateExpanseReportByAccount, но сразу
-- общая сумма за период — без разбивки по счёту-получателю (для ячейки
-- "Расход" в шапке главного экрана).
--
-- Параметры (подставить перед запуском):
--   :date_from      -- нижняя граница периода, включительно, формат 'yyyy-MM-dd HH:mm'
--   :date_to        -- верхняя граница периода, включительно, тот же формат
--   :to_currency_id -- currency._id валюты эквивалента, в которую переводятся суммы

SELECT
    round(sum(o.amount_eq), 2) AS expanse_eq
FROM v_operation_eq o
WHERE o.date BETWEEN :date_from AND :date_to
  AND o.to_currency_id = :to_currency_id
  AND o.acc_to_is_operating = 0
  AND o.acc_from_is_operating = 1;

-- Разбивка по счёту-получателю (совпадает с тем, что строит
-- GenerateExpanseReportByAccount для списка на экране отчёта):
--
-- SELECT o.acc_to_id, o.currency_id,
--        round(sum(o.amount), 2)    AS amount,
--        round(sum(o.amount_eq), 2) AS amount_eq
-- FROM v_operation_eq o
-- WHERE o.date BETWEEN :date_from AND :date_to
--   AND o.to_currency_id = :to_currency_id
--   AND o.acc_to_is_operating = 0
--   AND o.acc_from_is_operating = 1
-- GROUP BY o.acc_to_id, o.currency_id
-- ORDER BY amount_eq DESC;
