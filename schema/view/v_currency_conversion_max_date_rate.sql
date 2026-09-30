-- ВНИМАНИЕ: этой вьюхи нет в текущей схеме приложения — WalletDbHelper.CreateViews
-- её не создаёт, и по коду (grep по всему app/src) она нигде не запрашивается.
-- Она и не заработает на текущей v_currency_conversion_rate «как есть»: используемые
-- здесь r.is_reverse и r.num вьюха больше не выводит (см. её файл в этой же папке).
-- Похоже на черновик мультипарного пересчёта курса через геометрическое произведение
-- (EXP(SUM(LOG(rate)))) — идея не реализована в приложении, актуальную логику курса
-- на дату см. в ARCHITECTURE.md, разделе «Курс на дату».
CREATE VIEW v_currency_conversion_max_date_rate
as
select r.from_currency_id, r.from_currency_name, r.from_currency_iso,
r.to_currency_id, r.to_currency_name, r.to_currency_iso,
r.date,  EXP(SUM(Log(r.rate))) rate
from v_currency_conversion_rate r
join (
select from_currency_iso , to_currency_iso, num, max(date) mxdate
from v_currency_conversion_rate 
group by from_currency_iso , to_currency_iso , num
) rm
on rm.from_currency_iso  = r.from_currency_name and rm.to_currency_iso  = r.to_currency_iso 
and r.date = rm.mxdate and rm.num = r.num
--where r.to_currency_iso ='RUB' and r.from_currency_iso ='BAM'
group by r.from_currency_id, r.from_currency_name, r.from_currency_iso,
r.to_currency_id, r.to_currency_name, r.to_currency_iso;