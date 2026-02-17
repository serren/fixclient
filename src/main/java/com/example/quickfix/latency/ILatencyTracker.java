package com.example.quickfix.latency;

/**
 * Interface for tracking round-trip latency between sending a NewOrderSingle
 * and receiving the corresponding ExecutionReport.
 * <p>
 * Implementations must be thread-safe.
 */
public interface ILatencyTracker {

    /**
     * Records the send timestamp for a given ClOrdID.
     * Must be called immediately before (or after) sending the NewOrderSingle.
     *
     * @param clOrdId unique client order ID
     */
    void recordSendTime(String clOrdId);

    /**
     * Records the receive timestamp for a given ClOrdID and computes the round-trip latency.
     * If the ClOrdID is not found in pending orders (e.g., unsolicited ExecutionReport),
     * the call is silently ignored.
     *
     * @param clOrdId unique client order ID from the ExecutionReport
     * @return round-trip latency in milliseconds, or {@code -1} if ClOrdID was not tracked
     */
    double recordReceiveTime(String clOrdId);

    /**
     * Returns the number of completed latency measurements.
     *
     * @return sample count
     */
    int getSampleCount();

    /**
     * Returns the number of orders still awaiting an ExecutionReport.
     *
     * @return pending order count
     */
    int getPendingCount();

    /**
     * Prints aggregated latency statistics to the console.
     * Includes: sample count, min, max, average, median, p95, p99.
     */
    void printStatistics();

    /**
     * Resets all collected data (pending orders and latency samples).
     */
    void reset();
}