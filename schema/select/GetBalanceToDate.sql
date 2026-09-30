-- Баланс каждого кошелька на дату — в своей валюте и в эквиваленте.
--
-- Единственная реализация расчёта баланса в приложении (WalletDbLoader.LoadBalancesPerWallet).
-- Её результат обслуживает два экрана:
--   - BalanceReportActivity — построчный список кошельков (вызывается дважды: с датой
--     начала и датой конца периода, поэтому date_from и date_to ниже — это ОДНА и та же
--     дата на каждый вызов, а не границы диапазона)
--   - GenerateReportGetBalanceToDate — суммирует эти же строки для полей
--     «баланс на начало / на конец» в ReportActivity
--
-- Баланс = обороты «на счёт» минус обороты «со счёта» до даты (исключая её самоё,
-- date < date_to), округлённые до currency.precision знаков.
--
-- Курс для эквивалента ищется по шаблону «последняя дата не позже нужной»
-- (см. ARCHITECTURE.md, раздел «Курс на дату»): непосредственно на COALESCE курса
-- НЕ ставится — если курса нет, balance_eq остаётся NULL, а не тихо считается по 1:1
-- (см. ARCHITECTURE.md, раздел «Три состояния эквивалента»).
--
-- Параметры (подставить перед запуском):
--   :date_to        -- граница периода, не включая её (date < :date_to), формат 'yyyy-MM-dd HH:mm'
--   :to_currency_id -- currency._id валюты эквивалента, в которую переводится баланс

SELECT vao._id, vao.name, c._id currency_id, c.symbol, c.precision,
    round(coalesce(opt.amount_to, 0) - coalesce(opf.amount_fr, 0), c.precision) balance,
    round(vccr.rate * (coalesce(opt.amount_to, 0) - coalesce(opf.amount_fr, 0)), 2) balance_eq
FROM v_account_operating vao
JOIN currency c ON vao.currency_id = c._id
LEFT JOIN (
    SELECT acc_to_id, round(sum(amount), 2) amount_to
    FROM operation
    WHERE date < :date_to
    GROUP BY acc_to_id
) opt ON vao._id = opt.acc_to_id
LEFT JOIN (
    SELECT acc_fr_id, round(sum(amount), 2) amount_fr
    FROM operation
    WHERE date < :date_to
    GROUP BY acc_fr_id
) opf ON vao._id = opf.acc_fr_id
LEFT JOIN (
    SELECT from_currency_id, to_currency_id, max(date) max_date
    FROM v_currency_conversion_rate
    WHERE date <= :date_to
    GROUP BY from_currency_id, to_currency_id
) mx ON mx.from_currency_id = c._id AND mx.to_currency_id = :to_currency_id
LEFT JOIN v_currency_conversion_rate vccr
    ON vccr.from_currency_id = mx.from_currency_id
   AND vccr.to_currency_id = mx.to_currency_id
   AND vccr.date = mx.max_date
WHERE (opt.acc_to_id IS NOT NULL OR opf.acc_fr_id IS NOT NULL)
ORDER BY balance_eq DESC;
