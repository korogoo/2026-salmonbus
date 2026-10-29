import { useEffect, useState } from "react";
import { requestJson } from "@/shared/api/client";
import { serviceForecastAllowed, type RouteServiceStatus } from "@/shared/api/routeService.types";

export function useRouteService(routeId: string) {
  const [snapshot, setSnapshot] = useState<{ key: string; value: RouteServiceStatus; receivedAt: number; referenceAtReceipt: number } | null>(null);
  const [now, setNow] = useState(() => performance.now());
  useEffect(() => {
    let disposed = false;
    let active: AbortController | null = null;
    const load = async () => {
      if (document.visibilityState !== "visible") return;
      active?.abort();
      active = new AbortController();
      const result = await requestJson<RouteServiceStatus>(
        `/api/v1/routes/${encodeURIComponent(routeId)}/service-status`, { signal: active.signal },
      );
      if (!disposed && result.ok && result.body.routeId === routeId &&
          typeof result.body.forecastAllowed === "boolean" && typeof result.body.validUntil === "string") {
        const referenceAtReceipt = result.clock?.now() ?? Date.parse(result.body.checkedAt);
        if (Number.isFinite(referenceAtReceipt)) {
          setSnapshot({ key: routeId, value: result.body, receivedAt: performance.now(), referenceAtReceipt });
        }
      }
    };
    void load();
    const poll = window.setInterval(() => { void load(); }, 15000);
    const clock = window.setInterval(() => setNow(performance.now()), 1000);
    const resume = () => { void load(); };
    document.addEventListener("visibilitychange", resume);
    window.addEventListener("online", resume);
    return () => {
      disposed = true;
      active?.abort();
      window.clearInterval(poll);
      window.clearInterval(clock);
      document.removeEventListener("visibilitychange", resume);
      window.removeEventListener("online", resume);
    };
  }, [routeId]);
  const status = snapshot?.key === routeId && now - snapshot.receivedAt < 30000 ? snapshot.value : null;
  const referenceNow = snapshot === null ? Number.POSITIVE_INFINITY :
    snapshot.referenceAtReceipt + Math.max(0, now - snapshot.receivedAt);
  return { status, allowed: serviceForecastAllowed(status, referenceNow) };
}
