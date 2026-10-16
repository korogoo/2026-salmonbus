-- 초기화용 번들이 아닌 현재 활성 모델의 예보를 확인한다.
WITH supported AS (
    SELECT DISTINCT s.route_version_id FROM stop_demand_statistics s
    JOIN route_version v ON v.id=s.route_version_id AND v.valid_to IS NULL
), active AS (
    SELECT m.id FROM model_deployment m
    WHERE m.state='ACTIVE'
)
SELECT count(*)>0 AND bool_and(EXISTS (
    SELECT 1 FROM forecast_publication p
    WHERE p.route_version_id=s.route_version_id AND p.model_deployment_id=a.id
      AND p.provenance='RECORDED' AND p.prediction_count>0
)) FROM supported s CROSS JOIN active a;
