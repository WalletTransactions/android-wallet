-- Актуализировано по WalletDbHelper.CreateViews (DATABASE_VERSION = 72).
-- Обратный фильтр к v_account_operating: счета БЕЗ строки в account_operating —
-- те, что выступают второй стороной проводки, откуда списываются или на которые
-- зачисляются средства (категории расходов и доходов), а не сами кошельки.
-- Определена в схеме, но ни один текущий SQL-запрос в Java-коде (WalletDbLoader
-- и др.) её не использует — экраны фильтруют категории через AccountList на
-- стороне Java, а не через эту вьюху.
--
-- v72 (org/docs/TODO_mobile_sync.md, Фаза 3): deleted_at IS NULL — мягко
-- удалённый счёт не должен выглядеть живой категорией; join на account_operating
-- вдобавок игнорирует её собственный tombstone (ao.deleted_at is null), иначе
-- мягко удалённая строка account_operating выглядела бы так, будто счёт снова
-- стал категорией.
CREATE VIEW v_account_expanse
as select a.*
from account a
left join account_operating ao
on ao._id = a._id
and ao.deleted_at is null
where a.deleted_at is null
and ao._id is null
