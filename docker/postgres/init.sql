-- Init script for shared Postgres instance.
-- Each service owns a dedicated schema to simulate "database per service"
-- without spawning multiple containers. Services connect with the same
-- credentials but set their default schema via Spring Boot config.
CREATE SCHEMA IF NOT EXISTS order_db;
CREATE SCHEMA IF NOT EXISTS payment_db;
CREATE SCHEMA IF NOT EXISTS inventory_db;