ALTER TABLE tasks ADD COLUMN assigned_by_slack_user_id varchar(80);
ALTER TABLE tasks ADD COLUMN assigned_at timestamp with time zone;
