INSERT INTO users (id, email, password_hash, role, enabled)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'admin@example.com', '$2y$10$famFWNlBCcFMsV6EYqEmM.BgtWv8zDw.CmtwfThdmCzI4mLNCxxM2', 'ADMIN', true),
    ('00000000-0000-0000-0000-000000000002', 'recruiter@example.com', '$2y$10$famFWNlBCcFMsV6EYqEmM.BgtWv8zDw.CmtwfThdmCzI4mLNCxxM2', 'RECRUITER', true)
ON CONFLICT (email) DO NOTHING;

INSERT INTO recruiters (id, user_id, full_name)
VALUES ('00000000-0000-0000-0000-000000000010', '00000000-0000-0000-0000-000000000002', 'Seed Recruiter')
ON CONFLICT (user_id) DO NOTHING;
