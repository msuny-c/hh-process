DROP TABLE IF EXISTS recruiter_schedule_slots CASCADE;

CREATE TABLE processed_kafka_events (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumer_name VARCHAR(255) NOT NULL
);
