-- Every API key, device and message now belongs to an organization.
-- A self-hosted gateway has a single one, created here; existing data is attached to it.
-- Portable between PostgreSQL and H2 in PostgreSQL mode.

create table organization (
    id         uuid primary key,
    name       varchar(100) not null,
    created_at timestamp with time zone not null
);

insert into organization (id, name, created_at)
values ('00000000-0000-0000-0000-000000000001', 'Default', current_timestamp);

alter table api_key add column organization_id uuid;
update api_key set organization_id = '00000000-0000-0000-0000-000000000001';
alter table api_key alter column organization_id set not null;
alter table api_key add constraint fk_api_key_organization foreign key (organization_id) references organization (id);
create index idx_api_key_organization on api_key (organization_id);

alter table device add column organization_id uuid;
update device set organization_id = '00000000-0000-0000-0000-000000000001';
alter table device alter column organization_id set not null;
alter table device add constraint fk_device_organization foreign key (organization_id) references organization (id);
create index idx_device_organization on device (organization_id);

alter table sms_message add column organization_id uuid;
update sms_message set organization_id = '00000000-0000-0000-0000-000000000001';
alter table sms_message alter column organization_id set not null;
alter table sms_message add constraint fk_sms_message_organization foreign key (organization_id) references organization (id);
-- The queue is read per organization: oldest queued message of the device's organization.
create index idx_sms_message_org_status_created on sms_message (organization_id, status, created_at);
create index idx_sms_message_org_created on sms_message (organization_id, created_at);
