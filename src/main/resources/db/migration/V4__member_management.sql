ALTER TABLE members ADD COLUMN active boolean NOT NULL DEFAULT true;
ALTER TABLE members ADD CONSTRAINT uk_members_slack_user_id UNIQUE (slack_user_id);
