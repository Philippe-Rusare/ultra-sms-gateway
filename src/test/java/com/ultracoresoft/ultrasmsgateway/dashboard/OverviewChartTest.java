package com.ultracoresoft.ultrasmsgateway.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultracoresoft.ultrasmsgateway.message.MessageStatus;
import com.ultracoresoft.ultrasmsgateway.message.SmsMessageRepository.CreatedStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class OverviewChartTest {

    private static final ZoneId KINSHASA = ZoneId.of("Africa/Kinshasa");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private static CreatedStatus row(String instant, MessageStatus status) {
        return new CreatedStatus() {
            @Override
            public Instant getCreatedAt() {
                return Instant.parse(instant);
            }

            @Override
            public MessageStatus getStatus() {
                return status;
            }
        };
    }

    @Test
    void countsOutcomesPerDayInTheDashboardTimeZone() {
        OverviewChart chart = OverviewChart.build(List.of(
                row("2026-11-10T08:00:00Z", MessageStatus.DELIVERED),
                row("2026-11-10T08:05:00Z", MessageStatus.FAILED),
                // 23:30 UTC on the 9th is already the 10th in Kinshasa (UTC+1).
                row("2026-11-09T23:30:00Z", MessageStatus.SENT),
                row("2026-11-09T10:00:00Z", MessageStatus.EXPIRED),
                // Still in progress: not an outcome.
                row("2026-11-10T09:00:00Z", MessageStatus.QUEUED),
                // Before the window.
                row("2026-10-01T09:00:00Z", MessageStatus.DELIVERED)
        ), TODAY, KINSHASA, 14);

        OverviewChart.Bar today = chart.bars().get(13);
        OverviewChart.Bar yesterday = chart.bars().get(12);
        assertThat(chart.bars()).hasSize(14);
        assertThat(today.fullDate()).isEqualTo("Tue 10 Nov 2026");
        assertThat(today.delivered()).isEqualTo(1);
        assertThat(today.failed()).isEqualTo(1);
        assertThat(today.sent()).isEqualTo(1);
        assertThat(yesterday.expired()).isEqualTo(1);
        assertThat(chart.total()).isEqualTo(4);
    }

    @Test
    void stacksOneSegmentPerNonEmptySeriesAndRoundsOnlyTheTop() {
        OverviewChart chart = OverviewChart.build(List.of(
                row("2026-11-10T08:00:00Z", MessageStatus.DELIVERED),
                row("2026-11-10T08:01:00Z", MessageStatus.DELIVERED),
                row("2026-11-10T08:02:00Z", MessageStatus.EXPIRED)
        ), TODAY, KINSHASA, 14);

        List<OverviewChart.Segment> segments = chart.bars().get(13).segments();
        assertThat(segments).extracting(OverviewChart.Segment::series)
                .containsExactly(OverviewChart.Series.DELIVERED, OverviewChart.Series.EXPIRED);
        assertThat(segments.get(0).path()).doesNotContain("Q");
        assertThat(segments.get(1).path()).contains("Q");
    }

    @Test
    void axisUsesRoundSteps() {
        assertThat(OverviewChart.niceStep(0.25)).isEqualTo(1);
        assertThat(OverviewChart.niceStep(3.2)).isEqualTo(5);
        assertThat(OverviewChart.niceStep(34)).isEqualTo(50);
        assertThat(OverviewChart.niceStep(120)).isEqualTo(200);
    }

    @Test
    void successRateCountsSentAndDeliveredAmongFinishedMessages() {
        OverviewChart chart = OverviewChart.build(List.of(
                row("2026-11-10T08:00:00Z", MessageStatus.DELIVERED),
                row("2026-11-10T08:01:00Z", MessageStatus.SENT),
                row("2026-11-10T08:02:00Z", MessageStatus.SENT),
                row("2026-11-10T08:03:00Z", MessageStatus.FAILED)
        ), TODAY, KINSHASA, 14);

        assertThat(DashboardController.successRate(chart.bars())).isEqualTo(75);
        assertThat(DashboardController.successRate(chart.bars().subList(0, 7))).isNull();
    }
}
