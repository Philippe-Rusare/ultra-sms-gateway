-- An API key may send with some phones only (api_key_device; no row: every phone of the organization).
create table api_key_device (
    api_key_id uuid not null references api_key (id) on delete cascade,
    device_id  uuid not null references device (id) on delete cascade,
    primary key (api_key_id, device_id)
);

-- Routing by operator: each SMS goes preferably from a phone on the recipient's network.
create table routing_settings (
    organization_id uuid primary key,
    enabled         boolean     not null,
    -- ANY: with another phone when none of the right network is online; WAIT: wait for one.
    fallback        varchar(10) not null
);

-- The networks of the recipient and of the phone that took the message, when known.
alter table sms_message add column recipient_network varchar(20);
alter table sms_message add column sent_network varchar(20);
