-- Explicit, optional developer data. V1 contains schema only, never sample rows.
INSERT INTO members (family_name, given_name, team, slack_user_id)
SELECT '김', '민수', '백엔드팀', 'U-DEV-MINSU'
WHERE NOT EXISTS (SELECT 1 FROM members WHERE slack_user_id = 'U-DEV-MINSU');
INSERT INTO members (family_name, given_name, team, slack_user_id)
SELECT '이', '지영', '백엔드팀', 'U-DEV-JIYOUNG-BACKEND'
WHERE NOT EXISTS (SELECT 1 FROM members WHERE slack_user_id = 'U-DEV-JIYOUNG-BACKEND');
INSERT INTO members (family_name, given_name, team, slack_user_id)
SELECT '이', '지영', '디자인팀', 'U-DEV-JIYOUNG-DESIGN'
WHERE NOT EXISTS (SELECT 1 FROM members WHERE slack_user_id = 'U-DEV-JIYOUNG-DESIGN');
INSERT INTO members (family_name, given_name, team, slack_user_id)
SELECT '전', '해찬', '백엔드팀', 'U-DEV-HAECHAN'
WHERE NOT EXISTS (SELECT 1 FROM members WHERE slack_user_id = 'U-DEV-HAECHAN');
