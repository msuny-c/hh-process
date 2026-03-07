create table vacancies (
    id bigserial primary key,
    title varchar(255) not null,
    description text not null,
    status varchar(32) not null,
    created_at timestamp not null
);

create table candidates (
    id bigserial primary key,
    full_name varchar(255) not null,
    email varchar(255) not null unique,
    phone varchar(50),
    resume_text text not null,
    created_at timestamp not null
);

create table applications (
    id bigserial primary key,
    vacancy_id bigint not null references vacancies(id),
    candidate_id bigint not null references candidates(id),
    cover_letter text,
    status varchar(32) not null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create table application_status_history (
    id bigserial primary key,
    application_id bigint not null references applications(id),
    old_status varchar(32),
    new_status varchar(32) not null,
    changed_by varchar(64) not null,
    comment text,
    changed_at timestamp not null
);

create table notification_logs (
    id bigserial primary key,
    application_id bigint not null references applications(id),
    type varchar(32) not null,
    recipient varchar(255) not null,
    message text not null,
    status varchar(32) not null,
    created_at timestamp not null
);
