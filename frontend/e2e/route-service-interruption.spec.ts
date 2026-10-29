import { test, expect } from "./fixtures/api";
import { routeId } from "./fixtures/mock-data";

test("운휴 상태 직접 진입은 예보 대신 안내와 복귀 링크를 표시한다", async ({ page, api }) => {
  api.serviceState = "OFF";
  await page.goto(`/routes/${routeId}`);
  await expect(page.getByText("좌석 예보 제공을 일시 중단했어요", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "노선 선택으로 돌아가기", exact: true })).toBeVisible();
  expect(api.requests.watch).toBe(0);
});

test("이미 열린 화면도 운영 OFF가 반영되면 마지막 성공 예보를 숨긴다", async ({ page, api }) => {
  await page.goto(`/routes/${routeId}`);
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  api.serviceState = "OFF";
  await page.clock.runFor(15000);
  await expect(page.getByText("좌석 예보 제공을 일시 중단했어요", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toHaveCount(0);
});

test("운영 상태 갱신 실패 후 유효기간이 지나면 이전 ON으로 예보를 유지하지 않는다", async ({ page, api }) => {
  await page.goto(`/routes/${routeId}`);
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toBeVisible();
  await api.route(async (route) => {
    if (new URL(route.request().url()).pathname.endsWith("/service-status")) await route.abort("failed");
    else await route.fallback();
  });
  await page.clock.runFor(31000);
  await expect(page.getByText("운영 상태를 확인하고 있어요", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "3330", exact: true })).toHaveCount(0);
});
