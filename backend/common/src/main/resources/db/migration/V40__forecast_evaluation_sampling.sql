-- 기존 예보는 모두 평가한다. 기존 데이터를 UPDATE하거나 전체 검사하지 않는다.
-- 구 worker가 먼저/계속 쓰는 예보도 DEFAULT true로 기존 동작을 유지한다.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE seat_forecast ADD COLUMN evaluation_required boolean NOT NULL DEFAULT true;

CREATE OR REPLACE VIEW forecast_evaluation_pending AS
SELECT forecast.vehicle_observation_id, forecast.target_stop_order, work.route_version_id
FROM forecast_evaluation_work work
JOIN seat_forecast forecast
  ON forecast.vehicle_observation_id = work.vehicle_observation_id
 AND forecast.route_version_id = work.route_version_id
WHERE forecast.evaluation_required
AND NOT EXISTS (
    SELECT 1 FROM forecast_evaluation_result result
    WHERE result.vehicle_observation_id = forecast.vehicle_observation_id
      AND result.target_stop_order = forecast.target_stop_order
)
AND NOT EXISTS (
    SELECT 1 FROM evaluation_archive_member archived
    WHERE archived.vehicle_observation_id = forecast.vehicle_observation_id
      AND archived.target_stop_order = forecast.target_stop_order
);
