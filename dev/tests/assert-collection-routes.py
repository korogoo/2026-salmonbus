"""로컬 API의 수집 노선 목록과 모델 미지원 응답을 확인한다. 운영 부하/정확도 검사가 아니다."""
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

api, data = sys.argv[1], Path(sys.argv[2])
assert api.startswith("http://127.0.0.1:") or api.startswith("http://localhost:"), "local API only"
reference = json.loads((data / "routes.json").read_text())["routes"]
catalog = json.loads((data / "catalog-routes.json").read_text())["routes"]
added = {"204000081", "234000002", "234000313", "234000878", "234000882", "204000082",
         "234001739", "233000266", "204000170", "234000879", "228000393", "200000314"}


def get(path):
    try:
        with urllib.request.urlopen(api + path, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


status, body = get("/api/v1/routes")
assert status == 200
listed = {route["id"]: route for route in body["routes"]}
assert len(listed) == len(reference) + len(catalog) == 41
for route in reference + catalog:
    route_id = route["routeId"]
    if route_id not in added and route not in reference:
        continue
    assert listed[route_id]["displayName"] == route["displayName"], route_id
    if route_id in added:
        assert listed[route_id]["status"] == "PREPARING", route_id
        status, body = get(f"/api/v1/routes/{route_id}/board")
        assert status == 503 and body["code"] == "MODEL_OUT_OF_SCOPE", (route_id, status, body)
    else:
        assert listed[route_id]["status"] == "FORECAST_READY", route_id
        status, _ = get(f"/api/v1/routes/{route_id}/board")
        assert status == 200, route_id
print("20개 노선 조회, 기존 8개 예보 응답, 새 12개 모델 미지원 응답 확인 완료")
