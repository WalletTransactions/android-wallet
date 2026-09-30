-- Актуализировано по WalletDbHelper.CreateViews (DATABASE_VERSION = 72).
-- Отличие от предыдущей редакции этого файла: текущая вьюха НЕ отдаёт is_reverse
-- и num в результате — они использованы только внутри case/группировки самой
-- v_currency_conversion и наружу не выведены. Расширенная редакция с is_reverse/num
-- в SELECT нужна только для не вошедшей в схему v_currency_conversion_max_date_rate
-- (см. соответствующий файл) и в приложении не создаётся.
--
-- v72 (org/docs/TODO_mobile_sync.md, Фаза 3): deleted_at IS NULL на currency_pair_rate/
-- currency_pair/currency — мягко удалённый курс/пара/валюта не должны попасть
-- в пересчёт эквивалента.
CREATE VIEW v_currency_conversion_rate
as
select
from_currency_id, from_currency_name, from_currency_iso,
to_currency_id, to_currency_name, to_currency_iso,
date,
case when is_reverse = 1 then 1.0/rate else rate end rate
from v_currency_conversion cc
join currency_pair_rate r
on r.currency_pair_id = cc.currency_pair_id and r.deleted_at is null
join currency_pair p
on p._id = cc.currency_pair_id and p.deleted_at is null
union select _id, name, iso_code , _id, name, iso_code , '2000-01-01', 1 from currency c where c.deleted_at is null;