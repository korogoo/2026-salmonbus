-- worker와 정비 작업을 중지한 뒤 전환 도구가 실행한다.
-- 백업 식별자는 운영자가 확인한 기록이며 백업 존재·복구 가능성을 자동 검증한 증거는 아니다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10s';
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_stat_activity
        WHERE datname=current_database() AND pid<>pg_backend_pid() AND (backend_type='client backend' OR backend_type IS NULL)
          AND (application_name IN ('salmonbus-worker','salmonbus-historical-migration')
               OR application_name IS NULL OR state IS NULL)) THEN
        RAISE EXCEPTION 'SAL-175: worker 또는 정비 연결이 남아 있다';
    END IF;
    IF NULLIF(current_setting('salmonbus.backup_reference', true),'') IS NULL THEN
        RAISE EXCEPTION 'SAL-175: 확인한 백업 식별자가 필요하다';
    END IF;
END $$;
CREATE TABLE IF NOT EXISTS sal175_evaluation_transition (
    id integer PRIMARY KEY CHECK(id=1),
    backup_reference text NOT NULL CHECK(backup_reference<>''),
    expires_at timestamptz NOT NULL
);
INSERT INTO sal175_evaluation_transition(id,backup_reference,expires_at)
VALUES(1,current_setting('salmonbus.backup_reference'),clock_timestamp()+interval '30 minutes')
ON CONFLICT(id) DO UPDATE SET backup_reference=EXCLUDED.backup_reference,expires_at=EXCLUDED.expires_at;
COMMIT;
