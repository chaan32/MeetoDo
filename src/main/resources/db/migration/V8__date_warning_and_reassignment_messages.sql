ALTER TABLE tasks ADD COLUMN due_date_warning varchar(500);
ALTER TABLE slack_message_references ADD COLUMN notification_kind varchar(20) NOT NULL DEFAULT 'ASSIGNMENT';
ALTER TABLE slack_message_references ADD COLUMN notification_actor_slack_user_id varchar(80);
