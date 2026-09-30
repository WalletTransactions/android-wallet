-- ВНИМАНИЕ: этого триггера нет в текущей схеме приложения — WalletDbHelper не
-- выполняет CREATE TRIGGER, и в Java-коде нет ничего, что рассчитывало бы на его
-- существование. Обслуживает несуществующую таблицу account_balance (см. её файл
-- в ../table/account_balance.sql) — оставлен как черновик отказавшейся от
-- реализации идеи материализованного баланса по месяцам.
CREATE TRIGGER trg_operation after insert ON operation
  BEGIN	  
	  -- insert new data (if there is no)
	  insert or IGNORE  into account_balance(date, operating_account_id, amount, amount_rc)  	  
	  select t.date, account_id, COALESCE(abl.amount,0) amount, COALESCE (abl.amount_rc,0) amount_rc
	  from 	  ( 	  
	  	select date(t.dt,'start of month','+1 month') date,  account_id, t.amount, t.amount_rc
	  	from (
	  	select new.date dt, new.acc_fr_id account_id, -1*new.amount amount, -1*new.amount_rc amount_rc  
	  	union select new.date dt, new.acc_to_id account_id, new.amount, new.amount_rc	    
		) t	
	  ) t
	  join account_operating aof 
	    on aof."_id"  = t.account_id 
	  left join (select operating_account_id, max(date) maxdate
	  			  from account_balance abl  
	  			  where  abl.date <= date(new.date,'start of month','+1 month')
	  			  group by operating_account_id
	  			  ) abm
	    on  abm.operating_account_id = t.account_id	   
	  left join account_balance abl  
	    on  abl.operating_account_id = t.account_id	and abl.date = abm.maxdate
  ;
	   --update each record which is more than 
	   insert or replace into account_balance(date, operating_account_id, amount, amount_rc)  	  	  
 		select ab.date dt, account_id, t.amount + ab.amount amount, t.amount_rc + ab.amount_rc amount_rc
	  from 	  ( 	  
	  	select date(t.dt,'start of month','+1 month') date,  account_id, t.amount, t.amount_rc
	  	from (
	  	select new.date dt, new.acc_fr_id account_id, -1*new.amount amount, -1*new.amount_rc amount_rc  
	  	union select new.date dt, new.acc_to_id account_id, new.amount, new.amount_rc	    
		) t	
	  ) t
	  join account_operating aof 
	    on aof."_id"  = t.account_id 
	  left join account_balance ab  	  
	    on ab.date >= t.date and ab.operating_account_id = t.account_id	   
	    ;
 end;