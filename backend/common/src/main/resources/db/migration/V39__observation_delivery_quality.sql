-- 기록 당시의 품질 버전이 현재와 같을 때만 수집 원본을 정산 후보로 사용한다.
-- 기존 전달은 원본 사용을 허용하지 않고 SQL의 품질 판정을 거쳐 복원한다.
ALTER TABLE observation_delivery ADD COLUMN quality_revision BIGINT NOT NULL DEFAULT -1;
