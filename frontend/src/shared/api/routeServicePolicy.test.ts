import { describe, expect, it } from "@jest/globals";
import { serviceForecastAllowed, type RouteServiceStatus } from "./routeService.types";

const status: RouteServiceStatus = {
  routeId: "228000184", managed: true, state: "ON", forecastAllowed: true,
  collectionEnabled: true, reason: "NORMAL", message: "", sourceUrl: "",
  checkedAt: "2026-10-29T08:00:00Z", validUntil: "2026-10-29T08:00:30Z",
};
describe("운영 상태를 확인한 기간에만 예보 공개", () => {
  it("ON 응답도 유효기간이 끝나는 순간 더 이상 공개하지 않는다", () => {
    expect(serviceForecastAllowed(status, Date.parse(status.validUntil) - 1)).toBe(true);
    expect(serviceForecastAllowed(status, Date.parse(status.validUntil))).toBe(false);
  });
  it("미확인, OFF, 복구 준비와 잘못된 만료 시각은 공개하지 않는다", () => {
    const now = Date.parse(status.checkedAt);
    expect(serviceForecastAllowed(null, now)).toBe(false);
    expect(serviceForecastAllowed({ ...status, state: "OFF", forecastAllowed: false }, now)).toBe(false);
    expect(serviceForecastAllowed({ ...status, state: "WARMING_UP", forecastAllowed: false }, now)).toBe(false);
    expect(serviceForecastAllowed({ ...status, validUntil: "invalid" }, now)).toBe(false);
  });
});
