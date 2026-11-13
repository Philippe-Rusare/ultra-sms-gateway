-- Pairing a phone by QR code: a one-time code, valid a few minutes, exchanged by the app for a device token.
-- Only the hash of the code is stored. Portable between PostgreSQL and H2 in PostgreSQL mode.

create table device_pairing (
    id              uuid primary key,
    organization_id uuid         not null references organization (id),
    device_name     varchar(100) not null,
    code_hash       varchar(64)  not null unique,
    expires_at      timestamp with time zone not null,
    used_at         timestamp with time zone,
    -- The device created when the code was used.
    device_id       uuid references device (id),
    created_at      timestamp with time zone not null
);

create index idx_device_pairing_organization on device_pairing (organization_id);
