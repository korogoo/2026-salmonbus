-- 기존 worker/유지보수 writer를 중지한 상태에서 적용한다. 구 worker와 혼용할 수 없다.
-- API가 먼저 이 migration을 실행할 수 있으므로 자동 순차 배포만으로 writer 중지를 보장하지 않는다.
-- 구 버전 복귀 시 deploy/sql/rollback-observation-evaluation-work.sql과 이전 JAR가 함께 필요하다.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- 빈 DB의 자동 설치는 유지한다. 기존 운행 데이터가 있으면 명시적인 전환 준비가 필요하다.
DO $$
DECLARE approved boolean := false;
BEGIN
    IF EXISTS (SELECT 1 FROM seat_forecast LIMIT 1) THEN
        IF to_regclass(format('%I.sal175_evaluation_transition', current_schema())) IS NOT NULL THEN
            EXECUTE 'SELECT EXISTS (SELECT 1 FROM sal175_evaluation_transition
                WHERE id=1 AND expires_at>clock_timestamp() AND backup_reference<>'''')' INTO approved;
        END IF;
        IF NOT approved THEN
            RAISE EXCEPTION 'SAL-175: worker와 정비 작업을 중지하고 prepare로 전환을 승인해야 한다';
        END IF;
        IF EXISTS (SELECT 1 FROM pg_stat_activity
            WHERE datname=current_database() AND pid<>pg_backend_pid() AND (backend_type='client backend' OR backend_type IS NULL)
              AND (application_name IN ('salmonbus-worker','salmonbus-historical-migration')
                   OR application_name IS NULL OR state IS NULL)) THEN
            RAISE EXCEPTION 'SAL-175: worker 또는 정비 연결이 남아 있다';
        END IF;
    END IF;
END $$;

LOCK TABLE forecast_evaluation_pending, seat_forecast,
    forecast_evaluation_result, evaluation_archive_member
    IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE forecast_evaluation_work (
    vehicle_observation_id bigint PRIMARY KEY,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    route_version_id bigint NOT NULL REFERENCES route_version(id),
    FOREIGN KEY (vehicle_observation_id, route_version_id)
        REFERENCES vehicle_observation(id, route_version_id)
) WITH (
    autovacuum_vacuum_scale_factor = 0.01,
    autovacuum_vacuum_threshold = 50,
    autovacuum_vacuum_max_threshold = 10000
);

CREATE INDEX ix_evaluation_work_route
    ON forecast_evaluation_work(route_version_id, vehicle_observation_id);

INSERT INTO forecast_evaluation_work(vehicle_observation_id, route_version_id)
SELECT DISTINCT vehicle_observation_id, route_version_id
FROM forecast_evaluation_pending;

ALTER TABLE forecast_evaluation_pending RENAME TO forecast_evaluation_pending_legacy;

CREATE VIEW forecast_evaluation_pending AS
SELECT forecast.vehicle_observation_id, forecast.target_stop_order, work.route_version_id
FROM forecast_evaluation_work work
JOIN seat_forecast forecast
  ON forecast.vehicle_observation_id = work.vehicle_observation_id
 AND forecast.route_version_id = work.route_version_id
WHERE NOT EXISTS (
    SELECT 1 FROM forecast_evaluation_result result
    WHERE result.vehicle_observation_id = forecast.vehicle_observation_id
      AND result.target_stop_order = forecast.target_stop_order
)
AND NOT EXISTS (
    SELECT 1 FROM evaluation_archive_member archived
    WHERE archived.vehicle_observation_id = forecast.vehicle_observation_id
      AND archived.target_stop_order = forecast.target_stop_order
);

DO $$
BEGIN
    IF EXISTS (
        SELECT vehicle_observation_id, target_stop_order, route_version_id
        FROM forecast_evaluation_pending_legacy
        EXCEPT
        SELECT vehicle_observation_id, target_stop_order, route_version_id
        FROM forecast_evaluation_pending
    ) OR EXISTS (
        SELECT vehicle_observation_id, target_stop_order, route_version_id
        FROM forecast_evaluation_pending
        EXCEPT
        SELECT vehicle_observation_id, target_stop_order, route_version_id
        FROM forecast_evaluation_pending_legacy
    ) THEN
        RAISE EXCEPTION '기존 대기와 관측별 복원 결과가 다르다';
    END IF;
END $$;

CREATE OR REPLACE VIEW forecast_evaluation AS
SELECT * FROM forecast_evaluation_result
UNION ALL
SELECT vehicle_observation_id, target_stop_order, route_version_id, 'PENDING'::varchar(16),
       NULL::bigint, NULL::integer, NULL::timestamptz, NULL::timestamptz,
       NULL::bigint, NULL::varchar(40), NULL::integer, NULL::integer, NULL::integer,
       NULL::varchar(20), NULL::varchar(120), NULL::bigint
FROM forecast_evaluation_pending;

DROP TABLE forecast_evaluation_pending_legacy;
DROP TABLE IF EXISTS sal175_evaluation_transition;
