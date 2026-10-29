export interface RouteServiceStatus {
  routeId: string;
  managed: boolean;
  state: "ON" | "OFF" | "WARMING_UP" | "PENDING" | "READY" | "FAILED";
  forecastAllowed: boolean;
  collectionEnabled: boolean;
  reason: string;
  message: string;
  sourceUrl: string;
  checkedAt: string;
  validUntil: string;
}

export function serviceForecastAllowed(status: RouteServiceStatus | null, now: number): boolean {
  return status !== null && status.forecastAllowed && Number.isFinite(Date.parse(status.validUntil)) &&
    Date.parse(status.validUntil) > now;
}
