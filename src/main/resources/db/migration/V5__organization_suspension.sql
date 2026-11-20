-- An organization can be suspended (spam, unpaid): its API answers 403 and its phones are refused, until it is
-- reactivated. Nothing is deleted.
alter table organization add column suspended_at timestamp with time zone;
alter table organization add column suspension_reason varchar(200);
