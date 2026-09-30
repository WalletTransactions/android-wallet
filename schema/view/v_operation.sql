-- v72 (org/docs/TODO_mobile_sync.md, Фаза 3): o.deleted_at IS NULL — мягко удалённая
-- операция не должна быть видна. Счета (acf/act) НЕ фильтруются по deleted_at —
-- операция на мягко удалённый счёт обязана остаться видимой в истории, иначе
-- лента молча теряла бы строки (зеркально wallet-api/schema/view/v_operation.sql).
-- account_operating (aof/aot) фильтруется своим deleted_at — снятая
-- "операционность" счёта не должна больше считаться его валютой.
create view v_operation
as
select o._id, o.date, o.acc_fr_id, o.acc_to_id, o.amount, o.comment , acf.name acc_fr_name, act.name acc_to_name, COALESCE(aof.currency_id , aot.currency_id ) currency_id
, case when aof._id is not null then 1 else 0 end acc_from_is_operating
, case when aot._id is not null then 1 else 0 end acc_to_is_operating
from operation o
join account acf
on acf._id = o.acc_fr_id
join account act
on act._id = o.acc_to_id
left join account_operating aof
on aof._id  = acf._id and aof.deleted_at is null
left join account_operating aot
on  aot._id  =  act._id and aot.deleted_at is null
where o.deleted_at is null;

