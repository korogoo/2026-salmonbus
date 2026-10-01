package com.gustler.backend.forecasting.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ForecastFeatureContractTest {
    @Test
    void 기존_계약은_통계까지_기존_31열을_그대로_계산한다() {
        SeatForecastInput input = ForecastInputFixture.of("204000057");
        assertThat(ForecastFeatureContract.LEGACY.vectorOf(input))
            .containsExactly(SeatForecastDesignMatrix.of(input).toArray());
    }

    @ParameterizedTest
    @EnumSource(TimeSlot.class)
    void 위치와_방향을_시간대별_열에_같은_순서로_전달한다(TimeSlot slot) {
        double[] actual = ForecastFeatureContract.STOP_DIRECTION_TIME.vectorOf(input(slot, RouteDirection.DOWN));
        assertThat(actual).hasSize(50);
        assertThat(actual[19]).isEqualTo(0.5);
        assertThat(actual[23]).isEqualTo(0.5, within(1e-14));
        assertThat(actual[24]).isEqualTo(0.5, within(1e-14));
        assertThat(actual[31]).isEqualTo(1);
        assertThat(actual[35]).isEqualTo(slot == TimeSlot.MORNING ? 0.5 : 0, within(1e-14));
        assertThat(actual[40]).isEqualTo(slot == TimeSlot.MORNING ? 1 : 0);
        assertThat(actual[44]).isEqualTo(slot == TimeSlot.EVENING ? 0.5 : 0, within(1e-14));
        assertThat(actual[49]).isEqualTo(slot == TimeSlot.EVENING ? 1 : 0);
        assertThat(new double[] {actual[3], actual[28], actual[29], actual[30]}).containsOnly(0);
        assertThat(ForecastFeatureContract.STOP_DIRECTION_TIME.featureNames()).hasSize(50).doesNotHaveDuplicates();
    }

    @Test
    void 같은_정류장_ID라도_방향을_구분한다() {
        double[] up = ForecastFeatureContract.STOP_DIRECTION_TIME.vectorOf(input(TimeSlot.MORNING, RouteDirection.UP));
        double[] down = ForecastFeatureContract.STOP_DIRECTION_TIME.vectorOf(input(TimeSlot.MORNING, RouteDirection.DOWN));
        assertThat(up[31]).isZero();
        assertThat(down[31]).isEqualTo(1);
        assertThat(up[40]).isZero();
        assertThat(down[40]).isEqualTo(1);
    }

    @Test
    void 새_계약은_방향을_모르면_임의로_추정하지_않는다() {
        assertThatThrownBy(() -> ForecastFeatureContract.STOP_DIRECTION_TIME.vectorOf(
            input(TimeSlot.MORNING, RouteDirection.UNKNOWN))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 검증한_판본도_정류장_내용이_바뀌면_다시_거절한다() {
        SeatForecastInput input = input(TimeSlot.MORNING, RouteDirection.DOWN);
        ForecastRouteReference reference = new ForecastRouteReference(Map.of("3330", input.stops().stops().stream()
            .map(stop -> new ForecastRouteReference.Stop(stop.stopOrder(), stop.stopId(), stop.boardingAllowed(), stop.direction()))
            .toList()));
        reference.requireMatches(input.stops());
        reference.requireMatches(input.stops());
        RouteStop changed = new RouteStop(1L, 49, "different-stop", true, RouteDirection.DOWN);
        assertThatThrownBy(() -> reference.requireMatches(new RouteStops(1L, "204000057",
            List.of(changed, input.stops().stops().get(1))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reference.requireMatches(input(TimeSlot.MORNING, RouteDirection.UP).stops()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static SeatForecastInput input(TimeSlot slot, RouteDirection direction) {
        SeatForecastInput seed = ForecastInputFixture.of("204000057");
        RouteStop target = new RouteStop(1L, 49, "same-stop-id", true, direction);
        RouteStops stops = new RouteStops(1L, "204000057", List.of(target,
            new RouteStop(1L, 98, "last-stop", true, RouteDirection.DOWN)));
        return new SeatForecastInput(new VehicleStopTarget(seed.observation(), target), seed.trajectory(),
            new StopDemandStatistics(1L, slot, 1, seed.statistics().cells()), stops, slot, null);
    }
}
