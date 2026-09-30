-- Строит маршрут для поиска коэффициента/курса пересчёта одной валюты в другую:
-- разворачивает каждую currency_pair в оба направления (base->quote и quote->base,
-- с флагом is_reverse), затем левым джойном подмешивает переопределение из
-- currency_conversion (которая в текущей версии всегда пуста, см. ../table/README.md).
--
-- v72 (org/docs/TODO_mobile_sync.md, Фаза 3): deleted_at IS NULL на каждой из
-- четырёх участвующих таблиц — мягко удалённая пара/валюта/переопределение не
-- должны участвовать в маршруте пересчёта.
CREATE VIEW v_currency_conversion
as
select fc._id from_currency_id, fc.name from_currency_name, fc.iso_code from_currency_iso,
tc._id to_currency_id, tc.name to_currency_name, tc.iso_code to_currency_iso,
COALESCE(cc.currency_pair_id, fk.currency_pair_id) currency_pair_id,
COALESCE(cc.num, fk.num) num,
COALESCE(cc.is_reverse, fk.is_reverse) is_reverse
from (
select "_id" currency_pair_id, base_currency_id from_currency_id, quote_currency_id to_currency_id, 1 num, 0 is_reverse
from currency_pair cp
where cp.deleted_at is null
union
select "_id" currency_pair_id, quote_currency_id from_currency_id, base_currency_id to_currency_id, 1 num, 1 is_reverse
from currency_pair cp
where cp.deleted_at is null ) fk
left join currency_conversion cc
on cc.from_currency_id = fk.from_currency_id and cc.to_currency_id == fk.to_currency_id and cc.deleted_at is null
join currency fc
on fc."_id" =COALESCE(cc.from_currency_id, fk.from_currency_id) and fc.deleted_at is null
join currency tc
on tc."_id" =COALESCE(cc.to_currency_id,fk.to_currency_id) and tc.deleted_at is null;