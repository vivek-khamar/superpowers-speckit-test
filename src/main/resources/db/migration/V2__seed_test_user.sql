-- Password for this seeded user is "TestPassword123!" (documented here and
-- in UserRepositoryTest since there is no signup flow in this project to
-- create it any other way). Hash generated with:
--   htpasswd -bnBC 10 testuser 'TestPassword123!'
INSERT INTO users (email, password_hash, name, failed_attempts, locked_until)
VALUES (
    'testuser@example.com',
    '$2y$10$Dx.pZ8G6nTfgtV7.Y/fRtOJQTxX0bUm4WVeDYwAlx3vKRJL1f5.DG',
    'Test User',
    0,
    NULL
);
