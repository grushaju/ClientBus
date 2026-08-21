CREATE SCHEMA IF NOT EXISTS dbo;
CREATE SCHEMA IF NOT EXISTS system;

GRANT ALL ON SCHEMA dbo TO clientbus;
GRANT ALL ON SCHEMA system TO clientbus;

create table IF NOT EXISTS system.spring_session
(
    primary_id            char(36) not null
        constraint spring_session_pk
            primary key,
    session_id            char(36) not null,
    creation_time         bigint   not null,
    last_access_time      bigint   not null,
    max_inactive_interval integer  not null,
    expiry_time           bigint   not null,
    principal_name        varchar(100)
);

alter table system.spring_session
    owner to clientbus;

create unique index spring_session_ix1
    on system.spring_session (session_id);

create index spring_session_ix2
    on system.spring_session (expiry_time);

create index spring_session_ix3
    on system.spring_session (principal_name);


create table IF NOT EXISTS system.spring_session_attributes
(
    session_primary_id char(36)     not null
        constraint spring_session_attributes_fk
            references system.spring_session
            on delete cascade,
    attribute_name     varchar(200) not null,
    attribute_bytes    bytea        not null,
    constraint spring_session_attributes_pk
        primary key (session_primary_id, attribute_name)
);

alter table system.spring_session_attributes
    owner to clientbus;



