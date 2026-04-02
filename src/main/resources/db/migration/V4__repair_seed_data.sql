INSERT INTO roles (code)
VALUES ('ADMIN'), ('RECRUITER'), ('CANDIDATE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO users (id, email, password_hash, first_name, last_name, enabled)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'admin@example.com', '$2y$10$famFWNlBCcFMsV6EYqEmM.BgtWv8zDw.CmtwfThdmCzI4mLNCxxM2', 'Admin', 'User', true),
    ('00000000-0000-0000-0000-000000000002', 'recruiter@example.com', '$2y$10$famFWNlBCcFMsV6EYqEmM.BgtWv8zDw.CmtwfThdmCzI4mLNCxxM2', 'Seed', 'Recruiter', true)
ON CONFLICT (email) DO UPDATE
SET password_hash = EXCLUDED.password_hash,
    first_name = EXCLUDED.first_name,
    last_name = EXCLUDED.last_name,
    enabled = EXCLUDED.enabled,
    updated_at = now();

INSERT INTO user_roles (user_id, role_id)
SELECT '00000000-0000-0000-0000-000000000001', r.id
FROM roles r
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT '00000000-0000-0000-0000-000000000002', r.id
FROM roles r
WHERE r.code = 'RECRUITER'
ON CONFLICT DO NOTHING;
