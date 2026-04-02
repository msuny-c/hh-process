INSERT INTO roles (id, code)
VALUES (1, 'ADMIN'), (2, 'RECRUITER'), (3, 'CANDIDATE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO users (id, email, password_hash, first_name, last_name, enabled)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'admin@example.com', '$2a$10$7MWMMAB73G/EvLLLEDsk5eSoOuhLQET/M94o95rYiBAkOfsKCNdvK', 'Admin', 'User', true),
    ('00000000-0000-0000-0000-000000000002', 'recruiter@example.com', '$2a$10$7MWMMAB73G/EvLLLEDsk5eSoOuhLQET/M94o95rYiBAkOfsKCNdvK', 'Seed', 'Recruiter', true)
ON CONFLICT (email) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
VALUES
    ('00000000-0000-0000-0000-000000000001', 1),
    ('00000000-0000-0000-0000-000000000002', 2)
ON CONFLICT DO NOTHING;
