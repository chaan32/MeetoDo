-- Local H2: preserve existing members and fail clearly if duplicate Slack links need manual resolution.
ALTER TABLE IF EXISTS members ADD COLUMN IF NOT EXISTS active boolean DEFAULT true NOT NULL;
ALTER TABLE IF EXISTS members ADD CONSTRAINT IF NOT EXISTS uk_members_slack_user_id UNIQUE (slack_user_id);
