package com.example.quickfix.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.SessionID;
import quickfix.field.OrdType;
import quickfix.field.Side;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Automated order generator that sends orders at a configurable rate
 * for a specified duration.
 * <p>
 * Configuration is loaded from an external properties file ({@code order-generator.properties}).
 * <p>
 * Rate is defined as: {@code ordersPerBatch} orders every {@code batchIntervalMs} milliseconds.
 * <p>
 * Examples:
 * <ul>
 *   <li>10 orders/sec for 1 min → ordersPerBatch=10, batchIntervalMs=1000, durationSeconds=60</li>
 *   <li>5 orders every 2 sec for 5 min → ordersPerBatch=5, batchIntervalMs=2000, durationSeconds=300</li>
 * </ul>
 */
public class OrderGeneratorService implements IOrderGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(OrderGeneratorService.class);

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public static final String DEFAULT_PROPERTIES_FILE = "conf/order-generator.properties";

    // --- Rate control ---
    private int ordersPerBatch = 10;
    private long batchIntervalMs = 1000;
    private long durationSeconds = 60;

    // --- Order parameters ---
    private String symbol = "AAPL";
    private char side = Side.BUY;
    private double quantity = 100;
    private char orderType = OrdType.LIMIT;
    private double price = 150.00;

    // --- Runtime state ---
    private final IOrderService orderService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalSent = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> generatorTask;
    private long startTimeNanos;

    /**
     * Creates an OrderGeneratorService backed by the given OrderService.
     *
     * @param orderService the order service used to send FIX orders
     */
    public OrderGeneratorService(IOrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Loads generator configuration from the specified properties file.
     * Falls back to classpath if the file is not found on the filesystem.
     *
     * @param propertiesPath path to the properties file
     * @throws IOException if the file cannot be read
     */
    public void loadConfig(String propertiesPath) throws IOException {
        Properties props = new Properties();

        File file = new File(propertiesPath);
        if (file.exists() && file.isFile()) {
            try (InputStream is = new FileInputStream(file)) {
                props.load(is);
            }
            logInfo("Loaded config from file: " + file.getAbsolutePath());
        } else {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(propertiesPath)) {
                if (is == null) {
                    throw new IOException("Properties file not found: " + propertiesPath
                            + " (checked filesystem and classpath)");
                }
                props.load(is);
            }
            logInfo("Loaded config from classpath: " + propertiesPath);
        }

        parseProperties(props);
    }

    /**
     * Loads generator configuration from the default properties file
     * ({@value #DEFAULT_PROPERTIES_FILE}).
     *
     * @throws IOException if the file cannot be read
     */
    public void loadConfig() throws IOException {
        loadConfig(DEFAULT_PROPERTIES_FILE);
    }

    /**
     * Starts the order generation process.
     * Orders are sent at the configured rate for the configured duration.
     *
     * @param sessionId the FIX session to send orders on
     * @throws IllegalStateException if the generator is already running
     */
    public void start(SessionID sessionId) {
        if (running.getAndSet(true)) {
            throw new IllegalStateException("Order generator is already running");
        }

        totalSent.set(0);
        totalFailed.set(0);
        startTimeNanos = System.nanoTime();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OrderGenerator");
            t.setDaemon(true);
            return t;
        });

        double effectiveRate = (double) ordersPerBatch / batchIntervalMs * 1000.0;
        long totalExpected = (long) (effectiveRate * durationSeconds);

        logInfo(String.format("Starting order generation: %d orders every %d ms for %d seconds "
                        + "(effective rate: %.1f orders/sec, expected total: ~%d)",
                ordersPerBatch, batchIntervalMs, durationSeconds,
                effectiveRate, totalExpected));
        logInfo(String.format("Order params: Symbol=%s, Side=%s, Qty=%.0f, Type=%s%s",
                symbol,
                side == Side.BUY ? "BUY" : "SELL",
                quantity,
                orderType == OrdType.LIMIT ? "LIMIT" : "MARKET",
                orderType == OrdType.LIMIT ? String.format(", Price=%.2f", price) : ""));

        // Schedule the batch task at fixed rate
        generatorTask = scheduler.scheduleAtFixedRate(
                () -> executeBatch(sessionId),
                0,
                batchIntervalMs,
                TimeUnit.MILLISECONDS
        );

        // Schedule the stop task after the configured duration
        scheduler.schedule(() -> {
            stop();
            printSummary();
        }, durationSeconds, TimeUnit.SECONDS);
    }

    /**
     * Stops the order generation process.
     * If the generator is not running, this method does nothing.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (generatorTask != null) {
            generatorTask.cancel(false);
        }

        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        logInfo("Order generator stopped.");
    }

    /**
     * Returns whether the generator is currently running.
     *
     * @return {@code true} if the generator is actively sending orders
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the total number of orders successfully sent during the current (or last) run.
     *
     * @return total sent count
     */
    public long getTotalSent() {
        return totalSent.get();
    }

    /**
     * Returns the total number of orders that failed to send during the current (or last) run.
     *
     * @return total failed count
     */
    public long getTotalFailed() {
        return totalFailed.get();
    }

    /**
     * Prints the current configuration to the console.
     */
    public void printConfig() {
        double effectiveRate = (double) ordersPerBatch / batchIntervalMs * 1000.0;
        long totalExpected = (long) (effectiveRate * durationSeconds);

        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║     Order Generator — Configuration          ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.printf("║  Orders per batch  : %-23d ║%n", ordersPerBatch);
        System.out.printf("║  Batch interval    : %-20d ms ║%n", batchIntervalMs);
        System.out.printf("║  Duration          : %-21d s ║%n", durationSeconds);
        System.out.printf("║  Effective rate    : %-17.1f ord/s ║%n", effectiveRate);
        System.out.printf("║  Expected total    : ~%-22d ║%n", totalExpected);
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.printf("║  Symbol            : %-23s ║%n", symbol);
        System.out.printf("║  Side              : %-23s ║%n", side == Side.BUY ? "BUY" : "SELL");
        System.out.printf("║  Quantity          : %-23.0f ║%n", quantity);
        System.out.printf("║  Order type        : %-23s ║%n", orderType == OrdType.LIMIT ? "LIMIT" : "MARKET");
        if (orderType == OrdType.LIMIT) {
            System.out.printf("║  Price             : %-23.2f ║%n", price);
        }
        System.out.println("╚══════════════════════════════════════════════╝");
    }

    // ── Internal methods ────────────────────────────────────────────────

    /**
     * Executes a single batch: sends {@code ordersPerBatch} orders.
     * Called by the scheduler at each interval tick.
     */
    private void executeBatch(SessionID sessionId) {
        if (!running.get()) {
            return;
        }

        for (int i = 0; i < ordersPerBatch; i++) {
            if (!running.get()) {
                break;
            }

            try {
                String clOrdId;
                if (orderType == OrdType.LIMIT) {
                    clOrdId = orderService.sendLimitOrder(sessionId, symbol, side, quantity, price);
                } else {
                    clOrdId = orderService.sendMarketOrder(sessionId, symbol, side, quantity);
                }

                if (clOrdId != null) {
                    totalSent.incrementAndGet();
                } else {
                    totalFailed.incrementAndGet();
                }
            } catch (Exception e) {
                totalFailed.incrementAndGet();
                log.error("[OrderGenerator] Error sending order: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Prints a summary of the generation run.
     */
    private void printSummary() {
        long elapsedNanos = System.nanoTime() - startTimeNanos;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        long sent = totalSent.get();
        long failed = totalFailed.get();
        double actualRate = sent / elapsedSeconds;

        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║     Order Generator — Run Summary            ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.printf("║  Total sent        : %-23d ║%n", sent);
        System.out.printf("║  Total failed      : %-23d ║%n", failed);
        System.out.printf("║  Elapsed time      : %-21.2f s ║%n", elapsedSeconds);
        System.out.printf("║  Actual rate       : %-17.1f ord/s ║%n", actualRate);
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println("[OrderGenerator] Use 'stats' command to view round-trip latency statistics.");
    }

    /**
     * Parses the loaded properties and applies them to the generator configuration.
     *
     * @param props loaded properties
     */
    private void parseProperties(Properties props) {
        ordersPerBatch = getIntProperty(props, "generator.ordersPerBatch", ordersPerBatch);
        batchIntervalMs = getLongProperty(props, "generator.batchIntervalMs", batchIntervalMs);
        durationSeconds = getLongProperty(props, "generator.durationSeconds", durationSeconds);

        symbol = props.getProperty("generator.symbol", symbol).trim();

        String sideStr = props.getProperty("generator.side", "BUY").trim().toUpperCase();
        side = sideStr.equals("SELL") ? Side.SELL : Side.BUY;

        quantity = getDoubleProperty(props, "generator.quantity", quantity);

        String typeStr = props.getProperty("generator.orderType", "LIMIT").trim().toUpperCase();
        orderType = typeStr.equals("MARKET") ? OrdType.MARKET : OrdType.LIMIT;

        price = getDoubleProperty(props, "generator.price", price);

        // Validation
        if (ordersPerBatch <= 0) {
            throw new IllegalArgumentException("generator.ordersPerBatch must be > 0, got: " + ordersPerBatch);
        }
        if (batchIntervalMs <= 0) {
            throw new IllegalArgumentException("generator.batchIntervalMs must be > 0, got: " + batchIntervalMs);
        }
        if (durationSeconds <= 0) {
            throw new IllegalArgumentException("generator.durationSeconds must be > 0, got: " + durationSeconds);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("generator.quantity must be > 0, got: " + quantity);
        }
        if (orderType == OrdType.LIMIT && price <= 0) {
            throw new IllegalArgumentException("generator.price must be > 0 for LIMIT orders, got: " + price);
        }
    }

    private int getIntProperty(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logInfo("WARNING: Invalid integer for '" + key + "': " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    private long getLongProperty(Properties props, String key, long defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            logInfo("WARNING: Invalid long for '" + key + "': " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    private double getDoubleProperty(Properties props, String key, double defaultValue) {
        String value = props.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            logInfo("WARNING: Invalid double for '" + key + "': " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    private void logInfo(String message) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        System.out.printf("[%s] [OrderGenerator] %s%n", timestamp, message);
    }
}
