-- Hibernate update does not extend an existing H2 enum when AUDIO is added.
ALTER TABLE IF EXISTS meetings ALTER COLUMN source ENUM ('API_TEXT', 'SLACK_TEXT', 'SLACK_AUDIO', 'AUDIO');
