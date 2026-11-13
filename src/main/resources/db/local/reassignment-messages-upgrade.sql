ALTER TABLE IF EXISTS slack_message_references ADD COLUMN IF NOT EXISTS notification_kind varchar(20) DEFAULT 'ASSIGNMENT' NOT NULL;
ALTER TABLE IF EXISTS slack_message_references ADD COLUMN IF NOT EXISTS notification_actor_slack_user_id varchar(80);
