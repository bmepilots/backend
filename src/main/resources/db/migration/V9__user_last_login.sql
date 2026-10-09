ALTER TABLE users ADD COLUMN last_login_at DATETIME(6) NULL;

-- Only the named, successful login event provides trustworthy historical evidence.
-- Missing history remains NULL; creation, request and failed-login dates are not logins.
UPDATE users u
JOIN (
  SELECT actor_user_id, MAX(created_at) AS last_login_at
  FROM audit_log
  WHERE action = 'LOGIN_SUCCEEDED'
    AND entity_type = 'USER'
    AND actor_user_id = entity_id
  GROUP BY actor_user_id
) successful ON successful.actor_user_id = u.id
SET u.last_login_at = successful.last_login_at;
