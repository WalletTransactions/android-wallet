-- ВНИМАНИЕ: этой вьюхи нет в текущей схеме приложения — WalletDbHelper.CreateViews
-- её не создаёт, и по коду (grep по всему app/src) она нигде не запрашивается.
-- Функционально перекрыта GenerateReportGetBalanceToDate / LoadBalancesPerWallet
-- в WalletDbLoader (см. ../select/GetBalanceToDate.sql) — там расчёт баланса живёт
-- в единственном экземпляре. Файл — черновик более ранней попытки того же расчёта.
CREATE VIEW v_account_operating_balance
as
select vao._id, vao.name,  c._id currency_id, c.iso_code currency_iso_code,
	COALESCE (opt.amount,0) turn_over_to, 
	coalesce(opf.amount,0) turn_over_fr,
	round(opt.amount - opf.amount,2) as balance,
	cto._id to_currency_id, cto.iso_code to_currency_iso_code,
	round(opt.amount_eq - opf.amount_eq,2) as balance_eq
from v_account_operating vao 
join currency cto 
join currency c 
left join (
	select o.acc_fr_id, o.acc_fr_name,  o.currency_id, round(sum(amount),2) amount,  to_currency_id, round(sum(amount_eq),2) amount_eq
	from v_account_operating vao
	join v_operation_eq o
	  on o.acc_fr_id = vao._id
	--WHERE (nullIf(to_currency_id,1) is null)
	group by o.acc_fr_id, o.acc_fr_name, acc_from_is_operating, o.currency_id, to_currency_id
	order by acc_from_is_operating desc, amount_eq desc
) opf
on vao._id = opf.acc_fr_id and cto._id = opf.to_currency_id and c._id = opf.currency_id
left join (
	select o.acc_to_id, o.acc_to_name,  o.currency_id, round(sum(amount),2) amount,  to_currency_id, round(sum(amount_eq),2) amount_eq
	from v_account_operating vao
	join v_operation_eq o
	  on o.acc_to_id = vao._id
	--WHERE (nullIf(to_currency_id,1) is null)
	group by o.acc_to_id, o.acc_to_name, acc_to_is_operating, o.currency_id, to_currency_id
	order by acc_to_is_operating desc, amount_eq desc
) opt
on vao._id = opt.acc_to_id and cto._id = opt.to_currency_id  and c._id = opt.currency_id
where (opt.acc_to_id is not null or opf.acc_fr_id is not null)