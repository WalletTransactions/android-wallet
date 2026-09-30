-- Актуализировано по WalletDbHelper.CreateViews (DATABASE_VERSION = 72).
-- Опечатка "o.o._id" в предыдущей редакции исправлена на "o._id" — так и создаёт код.
--
-- v72 (servers/TODO_mobile_sync.md, Фаза 3): cc.deleted_at IS NULL — мягко
-- удалённая валюта не должна предлагаться как эквивалент (v_operation уже
-- фильтрует саму операцию, см. её файл).
CREATE VIEW v_operation_eq
as
select o._id, o.date, o.acc_fr_id, o.acc_to_id, o.amount, o.comment , o.acc_fr_name, o.acc_from_is_operating, o.acc_to_name, o.acc_to_is_operating,o.currency_id, o.to_currency_id,
o.max_date_rate, r.rate , round(r.rate*o.amount,2) amount_eq
from
(
select o._id, o.date, o.acc_fr_id, o.acc_to_id, o.amount, o.comment , o.acc_fr_name, o.acc_from_is_operating,
o.acc_to_name, o.acc_to_is_operating, o.currency_id, cc._id to_currency_id, max(rld.date) max_date_rate , cc._id
  from v_operation o
  cross join currency cc
  left join v_currency_conversion_rate rld
    on rld.from_currency_id = o.currency_id and rld.to_currency_id =cc._id and o.date >= rld.date
 where cc.deleted_at is null
 group by o._id, o.date, o.acc_fr_id, o.acc_to_id, o.amount, o.comment , o.acc_fr_name, o.acc_from_is_operating, o.acc_to_name, o.acc_to_is_operating,
 o.currency_id,  cc._id
 ) o
left join v_currency_conversion_rate r
  on r.to_currency_id =o.to_currency_id and r.from_currency_id = o.currency_id and o.max_date_rate = r.date;