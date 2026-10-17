-- SAL-150: 기존 검증된 상태 집합에 QUALITY_EXCLUDED만 허용한다.
-- 전체 평가 테이블 재검증 스캔을 피한다. 기존 행은 이전 CHECK가 보장한 부분집합이다.
-- NOT VALID도 이후 INSERT/UPDATE에는 적용된다. 별도 VALIDATE는 이 배포에서 실행하지 않는다.
SET LOCAL lock_timeout = '1s';
ALTER TABLE forecast_evaluation DROP CONSTRAINT ck_evaluation_state;
ALTER TABLE forecast_evaluation ADD CONSTRAINT ck_evaluation_state
    CHECK (scoring_state IN ('PENDING','SETTLED','SKIPPED','LOST','SEAT_MISSING','QUALITY_EXCLUDED')) NOT VALID;
