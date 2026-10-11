-- V37 전용 수동 롤백. 모든 예보/정산/품질/이관 writer를 중지한 뒤 실행한다.
-- 이 SQL이 성공한 뒤 V36까지 포함한 이전 JAR로 기동한다. 자동 배포 훅에서 실행하지 않는다.
-- 완료 이력·예보 원본·S3 이관 기록은 삭제하지 않는다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_stat_activity
        WHERE datname=current_database() AND pid<>pg_backend_pid() AND (backend_type='client backend' OR backend_type IS NULL)
          AND (application_name IN ('salmonbus-worker','salmonbus-historical-migration')
               OR application_name IS NULL OR state IS NULL)) THEN
        RAISE EXCEPTION 'SAL-175: worker 또는 정비 연결이 남아 있다';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version='37' AND success)
       OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE success AND version::numeric > 37) THEN
        RAISE EXCEPTION '이 롤백은 V37이 마지막으로 적용된 DB에서만 실행할 수 있다';
    END IF;
END $$;

LOCK TABLE forecast_evaluation_work, seat_forecast,
    forecast_evaluation_result, evaluation_archive_member IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE forecast_evaluation_pending_rollback (
    vehicle_observation_id bigint NOT NULL,
    target_stop_order integer NOT NULL,
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    PRIMARY KEY (vehicle_observation_id, target_stop_order),
    FOREIGN KEY (vehicle_observation_id, route_version_id) REFERENCES vehicle_observation(id, route_version_id),
    FOREIGN KEY (vehicle_observation_id, target_stop_order) REFERENCES seat_forecast(vehicle_observation_id, target_stop_order)
) WITH (autovacuum_vacuum_scale_factor=0.01, autovacuum_vacuum_threshold=50,
        autovacuum_vacuum_max_threshold=10000);

INSERT INTO forecast_evaluation_pending_rollback SELECT * FROM forecast_evaluation_pending;
DO $$
BEGIN
    IF EXISTS (SELECT * FROM forecast_evaluation_pending EXCEPT SELECT * FROM forecast_evaluation_pending_rollback)
       OR EXISTS (SELECT * FROM forecast_evaluation_pending_rollback EXCEPT SELECT * FROM forecast_evaluation_pending) THEN
        RAISE EXCEPTION '기존 구조로 복원한 미완료 예보가 일치하지 않는다';
    END IF;
END $$;

ALTER VIEW forecast_evaluation_pending RENAME TO forecast_evaluation_pending_work_view;
ALTER TABLE forecast_evaluation_pending_rollback RENAME TO forecast_evaluation_pending;
CREATE INDEX ix_evaluation_pending_route ON forecast_evaluation_pending
    (route_version_id, vehicle_observation_id, target_stop_order);

CREATE OR REPLACE VIEW forecast_evaluation AS
SELECT * FROM forecast_evaluation_result
UNION ALL
SELECT vehicle_observation_id, target_stop_order, route_version_id, 'PENDING'::varchar(16),
       NULL::bigint, NULL::integer, NULL::timestamptz, NULL::timestamptz,
       NULL::bigint, NULL::varchar(40), NULL::integer, NULL::integer, NULL::integer,
       NULL::varchar(20), NULL::varchar(120), NULL::bigint
FROM forecast_evaluation_pending;

DROP VIEW forecast_evaluation_pending_work_view;
DROP TABLE forecast_evaluation_work;
DELETE FROM flyway_schema_history WHERE version='37' AND success;
COMMIT;
