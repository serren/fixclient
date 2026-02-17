package com.example.quickfix.latency;

import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks round-trip latency between sending a NewOrderSingle and receiving
 * the corresponding ExecutionReport.
 * <p>
 * Thread-safe: uses {@link ConcurrentHashMap} for storing send timestamps
 * and a synchronized list for completed latency samples.
 * <p>
 * Usage:
 * <ol>
 *   <li>Call {@link #recordSendTime(String)} when sending a NewOrderSingle (keyed by ClOrdID)</li>
 *   <li>Call {@link #recordReceiveTime(String)} when the ExecutionReport arrives</li>
 *   <li>Call {@link #printStatistics()} to display aggregated latency stats</li>
 * </ol>
 */
public class LatencyTracker implements ILatencyTracker {

    /** Pending orders: ClOrdID → send timestamp (nanos) */
    private final ConcurrentMap<String, Long> pendingSendTimes = new ConcurrentHashMap<>();

    /** Completed round-trip latency samples in milliseconds */
    private final List<Double> latencySamples = new ArrayList<>();

    /**
     * Records the send timestamp for a given ClOrdID.
     * Must be called immediately before (or after) sending the NewOrderSingle.
     *
     * @param clOrdId unique client order ID
     */
    public void recordSendTime(String clOrdId) {
        pendingSendTimes.put(clOrdId, System.nanoTime());
    }

    /**
     * Records the receive timestamp for a given ClOrdID and computes the round-trip latency.
     * If the ClOrdID is not found in pending orders (e.g., unsolicited ExecutionReport),
     * the call is silently ignored.
     *
     * @param clOrdId unique client order ID from the ExecutionReport
     * @return round-trip latency in milliseconds, or {@code -1} if ClOrdID was not tracked
     */
    public double recordReceiveTime(String clOrdId) {
        Long sendNanos = pendingSendTimes.remove(clOrdId);
        if (sendNanos == null) {
            return -1;
        }

        long receiveNanos = System.nanoTime();
        double latencyMs = (receiveNanos - sendNanos) / 1_000_000.0;

        synchronized (latencySamples) {
            latencySamples.add(latencyMs);
        }

        return latencyMs;
    }

    /**
     * Returns the number of completed latency measurements.
     *
     * @return sample count
     */
    public int getSampleCount() {
        synchronized (latencySamples) {
            return latencySamples.size();
        }
    }

    /**
     * Returns the number of orders still awaiting an ExecutionReport.
     *
     * @return pending order count
     */
    public int getPendingCount() {
        return pendingSendTimes.size();
    }

    /**
     * Prints aggregated latency statistics to the console.
     * Includes: sample count, min, max, average, median, p95, p99.
     */
    public void printStatistics() {
        synchronized (latencySamples) {
            if (latencySamples.isEmpty()) {
                System.out.println("\n[LatencyTracker] No latency samples collected yet.");
                return;
            }

            List<Double> sorted = new ArrayList<>(latencySamples);
            sorted.sort(Double::compareTo);

            DoubleSummaryStatistics stats = sorted.stream()
                    .mapToDouble(Double::doubleValue)
                    .summaryStatistics();

            double median = percentile(sorted, 50);
            double p95 = percentile(sorted, 95);
            double p99 = percentile(sorted, 99);

            System.out.println("\n╔══════════════════════════════════════════════╗");
            System.out.println("║       Round-Trip Latency Statistics          ║");
            System.out.println("╠══════════════════════════════════════════════╣");
            System.out.printf("║  Samples         : %-25d ║%n", stats.getCount());
            System.out.printf("║  Min             : %-22.3f ms ║%n", stats.getMin());
            System.out.printf("║  Max             : %-22.3f ms ║%n", stats.getMax());
            System.out.printf("║  Average         : %-22.3f ms ║%n", stats.getAverage());
            System.out.printf("║  Median (p50)    : %-22.3f ms ║%n", median);
            System.out.printf("║  p95             : %-22.3f ms ║%n", p95);
            System.out.printf("║  p99             : %-22.3f ms ║%n", p99);
            System.out.printf("║  Pending orders  : %-25d ║%n", pendingSendTimes.size());
            System.out.println("╚══════════════════════════════════════════════╝");
        }
    }

    /**
     * Resets all collected data (pending orders and latency samples).
     */
    public void reset() {
        pendingSendTimes.clear();
        synchronized (latencySamples) {
            latencySamples.clear();
        }
        System.out.println("[LatencyTracker] Statistics reset.");
    }

    /**
     * Computes the given percentile from a sorted list of values.
     *
     * @param sorted     sorted list of latency values
     * @param percentile percentile to compute (0–100)
     * @return the percentile value
     */
    private double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }
}
