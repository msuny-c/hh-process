create table app_users (
    id bigserial primary key,
    email varchar(255) not null unique,
    password varchar(255) not null,
    role varchar(32) not null,
    active boolean not null default true,
    created_at timestamp not null
);

create table refresh_tokens (
    id bigserial primary key,
    user_id bigint not null references app_users(id) on delete cascade,
    token varchar(512) not null unique,
    expires_at timestamp not null,
    revoked boolean not null default false,
    created_at timestamp not null
);
