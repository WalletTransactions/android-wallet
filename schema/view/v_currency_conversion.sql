-- Строит маршрут для поиска коэффициента/курса пересчёта одной валюты в другую:
-- разворачивает каждую currency_pair в оба направления (base->quote и quote->base,
-- с флагом is_reverse). До v93 левым джойном подмешивалось переопределение из currency_conversion;
-- с v93 строки той таблицы — шаги маршрутов, вьюха их не подмешивает (см. ../table/README.md).
--
-- v72 (org/docs/TODO_mobile_sync.md, Фаза 3): deleted_at IS NULL на каждой из
-- четырёх участвующих таблиц — мягко удалённая пара/валюта/переопределение не
-- должны участвовать в маршруте пересчёта.
CREATE VIEW v_currency_conversion
as
select fc._id from_currency_id, fc.name from_currency_name, fc.iso_code from_currency_iso,
tc._id to_currency_id, tc.name to_currency_name, tc.iso_code to_currency_iso,
fk.currency_pair_id currency_pair_id,
fk.num num,
fk.is_reverse is_reverse
from (
select "_id" currency_pair_id, base_currency_id from_currency_id, quote_currency_id to_currency_id, 1 num, 0 is_reverse
from currency_pair cp
where cp.deleted_at is null
union
select "_id" currency_pair_id, quote_currency_id from_currency_id, base_currency_id to_currency_id, 1 num, 1 is_reverse
from currency_pair cp
where cp.deleted_at is null ) fk
join currency fc
on fc."_id" = fk.from_currency_id and fc.deleted_at is null
join currency tc
on tc."_id" = fk.to_currency_id and tc.deleted_at is null;