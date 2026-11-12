ALTER TABLE slack_message_references ALTER COLUMN channel_id DROP NOT NULL;
ALTER TABLE slack_message_references ALTER COLUMN message_ts DROP NOT NULL;
ALTER TABLE slack_message_references ADD COLUMN message_type varchar(20) NOT NULL DEFAULT 'CHANNEL';
ALTER TABLE slack_message_references ADD COLUMN target_slack_user_id varchar(80);
ALTER TABLE slack_message_references ADD COLUMN delivery_status varchar(20) NOT NULL DEFAULT 'SENT';
ALTER TABLE slack_message_references ADD COLUMN error_code varchar(80);
ALTER TABLE slack_message_references ADD COLUMN permalink varchar(2048);
