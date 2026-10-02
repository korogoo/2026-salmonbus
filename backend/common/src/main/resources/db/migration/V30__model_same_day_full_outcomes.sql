-- 이전 모델 혼합 합계는 보존하되 새 예보에서 사용하지 않는다. 원본 일괄 복사 없음.
SET LOCAL lock_timeout = '1s';
CREATE TABLE same_day_model_full_outcomes (
    route_id bigint NOT NULL REFERENCES route(id),
    model_deployment_id bigint NOT NULL REFERENCES model_deployment(id),
    outcome_date date NOT NULL,
    stops_to_target integer NOT NULL,
    row_count integer NOT NULL,
    actual_full_count integer NOT NULL,
    raw_full_chance_sum double precision NOT NULL,
    settled_through timestamptz NOT NULL,
    quality_revision bigint NOT NULL,
    PRIMARY KEY (route_id, model_deployment_id, outcome_date, stops_to_target),
    CHECK (row_count >= 0 AND actual_full_count BETWEEN 0 AND row_count)
);
