"""로컬 API의 20개 수집 노선과 예보 유무를 확인한다. 운영 부하/정확도 검사가 아니다."""
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

ADDED = {"204000081", "234000002", "234000313", "234000878", "234000882", "204000082",
         "234001739", "233000266", "204000170", "234000879", "228000393", "200000314"}


def verify_board(route_id, status, body, has_model):
    assert status == 200, f"{route_id}: board HTTP {status}, code={body.get('code')}"
    expected = "FORECAST_READY" if has_model else "PREPARING"
    assert body["route"]["id"] == route_id, f"{route_id}: 다른 노선 응답"
    assert body["route"]["status"] == expected, f"{route_id}: board status != {expected}"
    forecasts = [vehicle["forecast"] for stop in body["stops"] for vehicle in stop["approachingVehicles"]]
    assert forecasts, f"{route_id}: 확인할 접근 차량 없음"
    if has_model:
        assert any(item.get("status") == "AVAILABLE" for item in forecasts), f"{route_id}: 제공 중인 예보 없음"
    else:
        assert all(forecast == {"status": "UNAVAILABLE"} for forecast in forecasts), f"{route_id}: 미지원 예보 노출"


def verify(api, data):
    assert api.startswith("http://127.0.0.1:") or api.startswith("http://localhost:"), "local API only"
    reference = json.loads((data / "routes.json").read_text())["routes"]
    catalog = json.loads((data / "catalog-routes.json").read_text())["routes"]
    reference_ids = {route["routeId"] for route in reference}

    def get(path):
        try:
            with urllib.request.urlopen(api + path, timeout=10) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    status, body = get("/api/v1/routes")
    assert status == 200, f"노선 목록 HTTP {status}"
    listed = {route["id"]: route for route in body["routes"]}
    assert len(listed) == len(reference) + len(catalog) == 41
    failures = []
    for route in reference + catalog:
        route_id = route["routeId"]
        if route_id not in ADDED | reference_ids:
            continue
        try:
            has_model = True
            expected = "FORECAST_READY" if has_model else "PREPARING"
            assert listed[route_id]["displayName"] == route["displayName"], f"{route_id}: 노선명 불일치"
            assert listed[route_id]["status"] == expected, f"{route_id}: 목록 status != {expected}"
            status, body = get(f"/api/v1/routes/{route_id}/board")
            verify_board(route_id, status, body, has_model)
        except (AssertionError, KeyError, urllib.error.URLError) as error:
            failures.append(f"{route_id}: {error}")
    assert not failures, "\n".join(failures)
    print("20개 노선 조회, 20개 예보 응답 확인 완료")


if __name__ == "__main__":
    verify(sys.argv[1], Path(sys.argv[2]))
