-- 좌표 최초 수신/변경만 저장한다. 과거 버전의 좌표를 현재 좌표로 채우지 않는다.
SET LOCAL lock_timeout = '1s';
CREATE TABLE route_stop_location_history (
    route_version_id bigint NOT NULL,
    stop_order integer NOT NULL,
    stop_id varchar(20) NOT NULL,
    observed_at timestamptz NOT NULL,
    x double precision NOT NULL,
    y double precision NOT NULL,
    PRIMARY KEY (route_version_id, stop_order, observed_at),
    FOREIGN KEY (route_version_id, stop_order, stop_id)
        REFERENCES route_stop (route_version_id, stop_order, stop_id)
);
