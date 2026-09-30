-- Актуализировано по WalletDbHelper.CreateViews (DATABASE_VERSION = 72).
-- LEFT JOIN, а не INNER JOIN: возвращает ВСЕ счета, включая категории.
-- currency_id у категории — NULL не из-за механики JOIN, а потому что у категории
-- (статьи расхода/дохода) своей валюты нет и быть не может: валюта операции по
-- категории определяется второй стороной проводки — операционным счётом (см.
-- v_operation.currency_id). Именно поэтому это не «список кошельков»,
-- см. также v_account_expanse (обратный фильтр).
--
-- v72 (org/docs/TODO_mobile_sync.md, Фаза 3): deleted_at IS NULL на обеих
-- таблицах — мягко удалённый счёт не должен появляться здесь живым.
CREATE VIEW v_account_operating
as
select a._id, a.name, a.comment, a.icon_id , ao.currency_id
from account a
left join account_operating ao
on ao."_id" = a."_id"
and ao.deleted_at is null
where a.deleted_at is null