INSERT INTO roles (code)
VALUES ('ADMIN'), ('RECRUITER'), ('CANDIDATE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO users (id, email, password_hash, first_name, last_name, enabled)
VALUES
    ('00000000-0000-0000-0000-000000000003', 'candidate-demo@example.com', '$2y$10$famFWNlBCcFMsV6EYqEmM.BgtWv8zDw.CmtwfThdmCzI4mLNCxxM2', 'Candidate', 'Demo', true)
ON CONFLICT (email) DO UPDATE
SET password_hash = EXCLUDED.password_hash,
    first_name = EXCLUDED.first_name,
    last_name = EXCLUDED.last_name,
    enabled = true,
    updated_at = now();

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON r.code = 'CANDIDATE'
WHERE u.email = 'candidate-demo@example.com'
ON CONFLICT DO NOTHING;

UPDATE users
SET enabled = true,
    updated_at = now()
WHERE email IN ('admin@example.com', 'recruiter@example.com', 'candidate-demo@example.com');
