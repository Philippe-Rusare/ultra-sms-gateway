-- Webhooks: addresses of the client's application that receive the status changes of its messages.
-- Portable between PostgreSQL and H2 in PostgreSQL mode.

create table webhook_endpoint (
    id              uuid primary key,
    organization_id uuid         not null references organization (id),
    url             varchar(500) not null,
    description     varchar(100),
    -- Event types sent to this address, separated by commas, e.g. "message.sent,message.delivered".
    events          varchar(200) not null,
    -- Key of the HMAC signature. Kept in clear: the gateway needs it to sign every request.
    secret          varchar(64)  not null,
    active          boolean      not null,
    created_at      timestamp with time zone not null,
    last_success_at timestamp with time zone,
    last_failure_at timestamp with time zone
);

create index idx_webhook_endpoint_organization on webhook_endpoint (organization_id);

-- One request to send to one address: written in the same transaction as the status change it reports
-- (an outbox), then sent and retried by WebhookSender.
create table webhook_delivery (
    id               uuid primary key,
    endpoint_id      uuid         not null references webhook_endpoint (id) on delete cascade,
    organization_id  uuid         not null,
    event            varchar(40)  not null,
    -- The message concerned; null for a test event. No foreign key: the history outlives nothing it needs.
    message_id       uuid,
    payload          varchar(4000) not null,
    status           varchar(20)  not null,
    attempts         integer      not null,
    next_attempt_at  timestamp with time zone,
    last_status_code integer,
    last_error       varchar(200),
    created_at       timestamp with time zone not null,
    finished_at      timestamp with time zone
);

create index idx_webhook_delivery_due on webhook_delivery (status, next_attempt_at);
create index idx_webhook_delivery_endpoint on webhook_delivery (endpoint_id, created_at);
