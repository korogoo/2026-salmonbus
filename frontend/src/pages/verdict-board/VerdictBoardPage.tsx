import { useParams } from "react-router";
import { RouteServiceGate } from "@/shared/components/RouteServiceGate";
import { VerdictBoardScreen } from "./components/VerdictBoardScreen";

export function VerdictBoardPage() {
  const { routeId = "" } = useParams<{ routeId: string }>();
  return <RouteServiceGate routeId={routeId} observations><VerdictBoardScreen /></RouteServiceGate>;
}
