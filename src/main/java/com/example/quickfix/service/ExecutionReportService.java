package com.example.quickfix.service;

import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LeavesQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.fix44.ExecutionReport;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulates a trading venue by generating ExecutionReport (35=8) responses
 * for incoming NewOrderSingle (35=D), OrderCancelRequest (35=F), and
 * OrderCancelReplaceRequest (35=G) messages.
 * <p>
 * Each response is sent after a random delay between {@link #MIN_DELAY_MS}
 * and {@link #MAX_DELAY_MS} milliseconds to mimic real-world processing latency.
 * <p>
 * This service is intended to be used on the <b>Acceptor</b> side only.
 */
public class ExecutionReportService {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Minimum simulated processing delay in milliseconds */
    private static final long MIN_DELAY_MS = 1;

    /** Maximum simulated processing delay in milliseconds */
    private static final long MAX_DELAY_MS = 1000;

    /** Counter for generating unique ExecID values */
    private final AtomicLong execIdCounter = new AtomicLong(1);

    /** Counter for generating unique OrderID values */
    private final AtomicLong orderIdCounter = new AtomicLong(1);

    /** Scheduler for delayed response delivery */
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "ExecReport-Scheduler");
                t.setDaemon(true);
                return t;
            });

    /**
     * Stores accepted orders: ClOrdID → AcceptedOrder.
     * Used to look up order details when processing cancel/replace requests.
     */
    private final ConcurrentMap<String, AcceptedOrder> acceptedOrders = new ConcurrentHashMap<>();

    /**
     * Processes an incoming NewOrderSingle (35=D).
     * Sends an ExecutionReport with ExecType=NEW after a random delay.
     *
     * @param message   the incoming NewOrderSingle message
     * @param sessionId the FIX session to respond on
     */
    public void handleNewOrderSingle(Message message, SessionID sessionId) {
        try {
            NewOrderSingle nos = (NewOrderSingle) message;

            String clOrdId = nos.getString(ClOrdID.FIELD);
            char side = nos.getChar(Side.FIELD);
            String symbol = nos.getString(Symbol.FIELD);
            double orderQty = nos.getDouble(OrderQty.FIELD);
            char ordType = nos.getChar(OrdType.FIELD);
            double price = ordType == OrdType.LIMIT ? nos.getDouble(Price.FIELD) : 0;

            String orderId = generateOrderId();

            // Store the accepted order for future cancel/replace lookups
            acceptedOrders.put(clOrdId, new AcceptedOrder(
                    orderId, clOrdId, symbol, side, orderQty, price, ordType));

            // Build the ExecutionReport for a NEW order acknowledgement
            ExecutionReport execReport = new ExecutionReport();
            execReport.set(new OrderID(orderId));
            execReport.set(new ExecID(generateExecId()));
            execReport.set(new ExecType(ExecType.NEW));
            execReport.set(new OrdStatus(OrdStatus.NEW));
            execReport.set(new ClOrdID(clOrdId));
            execReport.set(new Side(side));
            execReport.set(new Symbol(symbol));
            execReport.set(new OrderQty(orderQty));
            execReport.set(new LeavesQty(orderQty));
            execReport.set(new CumQty(0));
            execReport.set(new AvgPx(0));
            if (ordType == OrdType.LIMIT) {
                execReport.set(new Price(price));
            }

            sendWithDelay(execReport, sessionId, "NEW", clOrdId);

        } catch (FieldNotFound e) {
            logError("NewOrderSingle", e);
        }
    }

    /**
     * Processes an incoming OrderCancelRequest (35=F).
     * Sends an ExecutionReport with ExecType=CANCELED after a random delay.
     *
     * @param message   the incoming OrderCancelRequest message
     * @param sessionId the FIX session to respond on
     */
    public void handleOrderCancelRequest(Message message, SessionID sessionId) {
        try {
            OrderCancelRequest cancelReq = (OrderCancelRequest) message;

            String clOrdId = cancelReq.getString(ClOrdID.FIELD);
            String origClOrdId = cancelReq.getString(OrigClOrdID.FIELD);
            char side = cancelReq.getChar(Side.FIELD);

            // Look up the original order
            AcceptedOrder original = acceptedOrders.remove(origClOrdId);
            String orderId = original != null ? original.orderId : generateOrderId();
            String symbol = original != null ? original.symbol : "N/A";
            double orderQty = original != null ? original.orderQty : 0;

            // Build the ExecutionReport for a CANCELED order
            ExecutionReport execReport = new ExecutionReport();
            execReport.set(new OrderID(orderId));
            execReport.set(new ExecID(generateExecId()));
            execReport.set(new ExecType(ExecType.CANCELED));
            execReport.set(new OrdStatus(OrdStatus.CANCELED));
            execReport.set(new ClOrdID(clOrdId));
            execReport.set(new OrigClOrdID(origClOrdId));
            execReport.set(new Side(side));
            execReport.set(new Symbol(symbol));
            execReport.set(new OrderQty(orderQty));
            execReport.set(new LeavesQty(0));
            execReport.set(new CumQty(0));
            execReport.set(new AvgPx(0));

            sendWithDelay(execReport, sessionId, "CANCELED", clOrdId);

        } catch (FieldNotFound e) {
            logError("OrderCancelRequest", e);
        }
    }

    /**
     * Processes an incoming OrderCancelReplaceRequest (35=G).
     * Sends an ExecutionReport with ExecType=REPLACED after a random delay.
     *
     * @param message   the incoming OrderCancelReplaceRequest message
     * @param sessionId the FIX session to respond on
     */
    public void handleOrderCancelReplaceRequest(Message message, SessionID sessionId) {
        try {
            OrderCancelReplaceRequest replaceReq = (OrderCancelReplaceRequest) message;

            String clOrdId = replaceReq.getString(ClOrdID.FIELD);
            String origClOrdId = replaceReq.getString(OrigClOrdID.FIELD);
            char side = replaceReq.getChar(Side.FIELD);
            String symbol = replaceReq.getString(Symbol.FIELD);
            double newOrderQty = replaceReq.getDouble(OrderQty.FIELD);
            char ordType = replaceReq.getChar(OrdType.FIELD);
            double newPrice = ordType == OrdType.LIMIT ? replaceReq.getDouble(Price.FIELD) : 0;

            // Look up the original order and replace it with updated details
            AcceptedOrder original = acceptedOrders.remove(origClOrdId);
            String orderId = original != null ? original.orderId : generateOrderId();

            // Store the replaced order under the new ClOrdID
            acceptedOrders.put(clOrdId, new AcceptedOrder(
                    orderId, clOrdId, symbol, side, newOrderQty, newPrice, ordType));

            // Build the ExecutionReport for a REPLACED order
            ExecutionReport execReport = new ExecutionReport();
            execReport.set(new OrderID(orderId));
            execReport.set(new ExecID(generateExecId()));
            execReport.set(new ExecType(ExecType.REPLACED));
            execReport.set(new OrdStatus(OrdStatus.REPLACED));
            execReport.set(new ClOrdID(clOrdId));
            execReport.set(new OrigClOrdID(origClOrdId));
            execReport.set(new Side(side));
            execReport.set(new Symbol(symbol));
            execReport.set(new OrderQty(newOrderQty));
            execReport.set(new LeavesQty(newOrderQty));
            execReport.set(new CumQty(0));
            execReport.set(new AvgPx(0));
            if (ordType == OrdType.LIMIT) {
                execReport.set(new Price(newPrice));
            }

            sendWithDelay(execReport, sessionId, "REPLACED", clOrdId);

        } catch (FieldNotFound e) {
            logError("OrderCancelReplaceRequest", e);
        }
    }

    /**
     * Gracefully shuts down the scheduler, waiting for pending responses to be sent.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log("ExecutionReportService shut down.");
    }

    // ── Internal methods ────────────────────────────────────────────────

    /**
     * Schedules the ExecutionReport to be sent after a random delay
     * between {@link #MIN_DELAY_MS} and {@link #MAX_DELAY_MS} milliseconds.
     *
     * @param execReport the ExecutionReport to send
     * @param sessionId  the FIX session to send the report on
     * @param execType   human-readable exec type for logging
     * @param clOrdId    ClOrdID for logging
     */
    private void sendWithDelay(ExecutionReport execReport, SessionID sessionId,
                               String execType, String clOrdId) {
        long delayMs = ThreadLocalRandom.current().nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1);

        log(String.format("Scheduling %s ExecutionReport for ClOrdID=%s with delay=%d ms",
                execType, clOrdId, delayMs));

        scheduler.schedule(() -> {
            try {
                boolean sent = Session.sendToTarget(execReport, sessionId);
                if (sent) {
                    log(String.format("Sent %s ExecutionReport: ClOrdID=%s", execType, clOrdId));
                } else {
                    logError(String.format("Failed to send %s ExecutionReport: ClOrdID=%s "
                            + "(session not logged on)", execType, clOrdId));
                }
            } catch (SessionNotFound e) {
                logError(String.format("Session not found when sending %s ExecutionReport: "
                        + "ClOrdID=%s — %s", execType, clOrdId, e.getMessage()));
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Generates a unique ExecID.
     *
     * @return unique ExecID string (e.g., "EXEC-00001")
     */
    private String generateExecId() {
        return String.format("EXEC-%05d", execIdCounter.getAndIncrement());
    }

    /**
     * Generates a unique OrderID.
     *
     * @return unique OrderID string (e.g., "SIM-00001")
     */
    private String generateOrderId() {
        return String.format("SIM-%05d", orderIdCounter.getAndIncrement());
    }

    private void log(String message) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        System.out.printf("[%s] [ExecReportService] %s%n", timestamp, message);
    }

    private void logError(String msgType, FieldNotFound e) {
        log(String.format("ERROR processing %s: missing field %d — %s",
                msgType, e.field, e.getMessage()));
    }

    private void logError(String message) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        System.err.printf("[%s] [ExecReportService] %s%n", timestamp, message);
    }

    // ── Inner class ─────────────────────────────────────────────────────

    /**
     * Holds the details of an accepted order on the simulated venue side.
     * Used to look up original order parameters when processing cancel/replace requests.
     */
    private static class AcceptedOrder {
        final String orderId;
        final String clOrdId;
        final String symbol;
        final char side;
        final double orderQty;
        final double price;
        final char ordType;

        AcceptedOrder(String orderId, String clOrdId, String symbol, char side,
                      double orderQty, double price, char ordType) {
            this.orderId = orderId;
            this.clOrdId = clOrdId;
            this.symbol = symbol;
            this.side = side;
            this.orderQty = orderQty;
            this.price = price;
            this.ordType = ordType;
        }
    }
}
