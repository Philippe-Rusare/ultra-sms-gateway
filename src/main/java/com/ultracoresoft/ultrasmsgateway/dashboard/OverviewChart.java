package com.ultracoresoft.ultrasmsgateway.dashboard;

import com.ultracoresoft.ultrasmsgateway.message.MessageStatus;
import com.ultracoresoft.ultrasmsgateway.message.SmsMessageRepository.CreatedStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stacked columns of messages per day by outcome, computed on the server and drawn as inline SVG:
 * no JavaScript library, so the dashboard works on servers without internet access.
 * Geometry follows the dashboard chart conventions: columns at most 24 px wide, 4 px rounded top,
 * square at the baseline, 2 px gap between stacked segments, hairline grid.
 */
public record OverviewChart(int width, int height, long total, List<Tick> ticks, List<Bar> bars) {

    /** Stack order, bottom to top. */
    public enum Series {
        DELIVERED("Delivered"), SENT("Sent"), FAILED("Failed"), EXPIRED("Expired");

        private final String label;

        Series(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public String cssClass() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Tick(double y, String label) {
    }

    public record Segment(Series series, String path) {
    }

    public record Bar(String dayLabel, String fullDate, boolean showLabel, double centerX, double hitX, double hitWidth,
                      long delivered, long sent, long failed, long expired, List<Segment> segments) {
        public long total() {
            return delivered + sent + failed + expired;
        }
    }

    static final int WIDTH = 1180;
    static final int HEIGHT = 260;
    static final double LEFT = 40;
    static final double RIGHT = 8;
    static final double TOP = 12;
    static final double BOTTOM = 28;
    static final double MAX_BAR_WIDTH = 24;
    static final double RADIUS = 4;
    static final double GAP = 2;
    static final double MIN_SEGMENT = 1.5;

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH);

    public double baseline() {
        return HEIGHT - BOTTOM;
    }

    public double plotRight() {
        return WIDTH - RIGHT;
    }

    public double plotLeft() {
        return LEFT;
    }

    /** Builds the chart for the {@code days} days ending today, in the dashboard time zone. */
    public static OverviewChart build(List<CreatedStatus> rows, LocalDate today, ZoneId zone, int days) {
        LocalDate first = today.minusDays(days - 1L);
        long[][] counts = new long[days][Series.values().length];
        for (CreatedStatus row : rows) {
            Series series = seriesOf(row.getStatus());
            if (series == null) {
                continue;
            }
            int index = (int) (row.getCreatedAt().atZone(zone).toLocalDate().toEpochDay() - first.toEpochDay());
            if (index >= 0 && index < days) {
                counts[index][series.ordinal()]++;
            }
        }

        long max = 0;
        long total = 0;
        for (long[] day : counts) {
            long dayTotal = 0;
            for (long value : day) {
                dayTotal += value;
            }
            max = Math.max(max, dayTotal);
            total += dayTotal;
        }

        double plotHeight = HEIGHT - TOP - BOTTOM;
        double baseline = HEIGHT - BOTTOM;
        long step = niceStep(Math.max(max, 1) / 4.0);
        long scaleMax = step * 4;
        double perUnit = plotHeight / scaleMax;

        List<Tick> ticks = new ArrayList<>();
        for (int i = 0; i <= 4; i++) {
            ticks.add(new Tick(round(baseline - i * step * perUnit), Long.toString(i * step)));
        }

        double band = (WIDTH - LEFT - RIGHT) / days;
        double barWidth = Math.min(MAX_BAR_WIDTH, band * 0.6);
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate day = first.plusDays(i);
            double center = LEFT + band * i + band / 2;
            double x = center - barWidth / 2;
            List<Segment> segments = segments(counts[i], x, barWidth, baseline, perUnit);
            bars.add(new Bar(SHORT.format(day), FULL.format(day), (days - 1 - i) % 2 == 0, round(center),
                    round(LEFT + band * i), round(band),
                    counts[i][0], counts[i][1], counts[i][2], counts[i][3], segments));
        }
        return new OverviewChart(WIDTH, HEIGHT, total, ticks, bars);
    }

    private static List<Segment> segments(long[] values, double x, double width, double baseline, double perUnit) {
        int top = -1;
        for (int s = 0; s < values.length; s++) {
            if (values[s] > 0) {
                top = s;
            }
        }
        List<Segment> segments = new ArrayList<>();
        double y = baseline;
        for (int s = 0; s < values.length; s++) {
            if (values[s] == 0) {
                continue;
            }
            double height = Math.max(values[s] * perUnit, MIN_SEGMENT);
            double segmentTop = y - height;
            boolean isTop = s == top;
            // The gap belongs to the segment below, so the stack keeps its true total height.
            double visibleTop = isTop ? segmentTop : Math.min(segmentTop + GAP, y - MIN_SEGMENT / 2);
            segments.add(new Segment(Series.values()[s], isTop
                    ? roundedTop(x, visibleTop, width, y)
                    : rect(x, visibleTop, width, y)));
            y = segmentTop;
        }
        return segments;
    }

    private static String rect(double x, double top, double width, double bottom) {
        return "M%s %sH%sV%sH%sZ".formatted(n(x), n(bottom), n(x + width), n(top), n(x));
    }

    /** Rounded corners at the data end only; the baseline end stays square. */
    private static String roundedTop(double x, double top, double width, double bottom) {
        double r = Math.min(RADIUS, Math.min(width / 2, bottom - top));
        return "M%s %sV%sQ%s %s %s %sH%sQ%s %s %s %sV%sZ".formatted(
                n(x), n(bottom), n(top + r),
                n(x), n(top), n(x + r), n(top),
                n(x + width - r),
                n(x + width), n(top), n(x + width), n(top + r),
                n(bottom));
    }

    private static Series seriesOf(MessageStatus status) {
        return switch (status) {
            case DELIVERED -> Series.DELIVERED;
            case SENT -> Series.SENT;
            case FAILED -> Series.FAILED;
            case EXPIRED -> Series.EXPIRED;
            // Messages still in progress are not an outcome yet.
            case QUEUED, DISPATCHED, ACCEPTED -> null;
        };
    }

    /** Smallest of 1, 2, 5 × 10ⁿ at least equal to {@code raw}. */
    static long niceStep(double raw) {
        long magnitude = 1;
        while (magnitude * 10 <= raw) {
            magnitude *= 10;
        }
        for (long factor : new long[] {1, 2, 5, 10}) {
            if (factor * magnitude >= raw) {
                return factor * magnitude;
            }
        }
        return 10 * magnitude;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static String n(double value) {
        double rounded = round(value);
        return rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Double.toString(rounded);
    }
}
