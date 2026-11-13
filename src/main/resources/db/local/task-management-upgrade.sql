-- H2: existing tasks retain their visibility before Hibernate adds the non-null mapped column.
ALTER TABLE IF EXISTS tasks ADD COLUMN IF NOT EXISTS deleted boolean DEFAULT false NOT NULL;
