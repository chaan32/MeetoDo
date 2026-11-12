-- H2 local only: preserve existing V2 rows before Hibernate update adds non-null fields.
ALTER TABLE IF EXISTS slack_message_references ADD COLUMN IF NOT EXISTS message_type varchar(20) DEFAULT 'CHANNEL' NOT NULL;
ALTER TABLE IF EXISTS slack_message_references ADD COLUMN IF NOT EXISTS delivery_status varchar(20) DEFAULT 'SENT' NOT NULL;
ALTER TABLE IF EXISTS slack_message_references ALTER COLUMN channel_id DROP NOT NULL;
ALTER TABLE IF EXISTS slack_message_references ALTER COLUMN message_ts DROP NOT NULL;
