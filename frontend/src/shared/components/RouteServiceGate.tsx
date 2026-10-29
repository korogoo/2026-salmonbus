import { Link } from "react-router";
import { paths } from "@/shared/routing/paths";
import { useEffect, useState, type ReactNode } from "react";
import { useRouteService } from "@/shared/hooks/useRouteService";
import type { RouteServiceStatus } from "@/shared/api/routeService.types";
import { requestJson } from "@/shared/api/client";

export function RouteServiceGate({ routeId, children, observations = false }: {
  routeId: string; children: ReactNode; observations?: boolean;
}) {
  const { status, allowed } = useRouteService(routeId);
  return <>
    {allowed && status?.reason === "PLANNED" && <ServiceNotice status={status} />}
    {allowed ? children : <section role="status" style={{ padding: 20 }}>
      {observations && <Link to={paths.routeSelect}>노선 선택으로 돌아가기</Link>}
      <ServiceNotice status={status} />
      {observations && status?.collectionEnabled && <ObservedVehicles routeId={routeId} />}
    </section>}
  </>;
}

function ServiceNotice({ status }: { status: RouteServiceStatus | null }) {
  const fallback = status?.state === "WARMING_UP" ? "새 예보를 확인하고 있어요" :
    status === null ? "운영 상태를 확인하고 있어요" : "좌석 예보 제공을 일시 중단했어요";
  return <div>
    <p>{status?.message || fallback}</p>
    {status && <small>확인 시각: {new Date(status.checkedAt).toLocaleString("ko-KR")}</small>}
    {status?.sourceUrl && /^https?:\/\//.test(status.sourceUrl) &&
      <p><a href={status.sourceUrl} target="_blank" rel="noreferrer">공식 안내 확인</a></p>}
  </div>;
}

interface Observations {
  observation: { state: string; staleAt: string | null };
  vehicles: { vehicleId: string | null; stopName: string }[];
}
function ObservedVehicles({ routeId }: { routeId: string }) {
  const [value, setValue] = useState<Observations | null>(null);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    let disposed = false;
    let active: AbortController | null = null;
    const load = async () => {
      active?.abort(); active = new AbortController();
      const result = await requestJson<Observations>(`/api/v1/routes/${encodeURIComponent(routeId)}/vehicles`, { signal: active.signal });
      if (!disposed && result.ok && Array.isArray(result.body.vehicles) && result.body.observation) setValue(result.body);
    };
    void load();
    const poll = window.setInterval(() => { void load(); }, 15000);
    const clock = window.setInterval(() => setNow(Date.now()), 1000);
    return () => { disposed = true; active?.abort(); window.clearInterval(poll); window.clearInterval(clock); };
  }, [routeId]);
  const fresh = value?.observation.staleAt != null && Date.parse(value.observation.staleAt) > now;
  return <div><p>최근 관측 차량</p>{fresh && value.vehicles.length > 0 ?
    <ul>{value.vehicles.map((v, index) => <li key={v.vehicleId ?? `${v.stopName}-${index}`}>{v.stopName} 부근</li>)}</ul> :
    <p>현재 확인 가능한 차량 정보가 없어요. 이 정보만으로 운휴 여부를 판단할 수는 없어요.</p>}</div>;
}
