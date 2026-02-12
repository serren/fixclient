package com.epam.quickfix;

import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for creating and sending FIX NewOrderSingle (35=D) messages.
 * <p>
 * Each order is assigned a unique ClOrdID and its send time is recorded
 * in the {@link LatencyTracker} for round-trip latency measurement.
 * <p>
 * Currently supports FIX 4.4 NewOrderSingle with the following fields:
 * <ul>
 *   <li>ClOrdID (11) — unique client order ID</li>
 *   <li>HandlInst (21) — handling instruction (automated, no intervention)</li>
 *   <li>Symbol (55) — instrument symbol</li>
 *   <li>Side (54) — Buy or Sell</li>
 *   <li>TransactTime (60) — transaction time</li>
 *   <li>OrdType (40) — order type (Limit or Market)</li>
 *   <li>OrderQty (38) — order quantity</li>
 *   <li>Price (44) — limit price (for Limit orders)</li>
 * </ul>
 */
public class OrderService {

    /** Atomic counter for generating unique ClOrdID values */
    private final AtomicLong orderIdCounter = new AtomicLong(1);

    /** Latency tracker for measuring round-trip times */
    private final LatencyTracker latencyTracker;

    /**
     * Creates an OrderService with the specified latency tracker.
     *
     * @param latencyTracker tracker for recording send/receive timestamps
     */
    public OrderService(LatencyTracker latencyTracker) {
        this.latencyTracker = latencyTracker;
    }

    /**
     * Sends a Limit NewOrderSingle to the specified FIX session.
     *
     * @param sessionId FIX session to send the order to
     * @param symbol    instrument symbol (e.g., "AAPL", "MSFT")
     * @param side      order side: {@link Side#BUY} or {@link Side#SELL}
     * @param quantity  order quantity
     * @param price     limit price
     * @return the ClOrdID assigned to this order, or {@code null} if sending failed
     */
    public String sendLimitOrder(SessionID sessionId, String symbol, char side, double quantity, double price) {
        return sendOrder(sessionId, symbol, side, quantity, OrdType.LIMIT, price);
    }

    /**
     * Sends a Market NewOrderSingle to the specified FIX session.
     *
     * @param sessionId FIX session to send the order to
     * @param symbol    instrument symbol (e.g., "AAPL", "MSFT")
     * @param side      order side: {@link Side#BUY} or {@link Side#SELL}
     * @param quantity  order quantity
     * @return the ClOrdID assigned to this order, or {@code null} if sending failed
     */
    public String sendMarketOrder(SessionID sessionId, String symbol, char side, double quantity) {
        return sendOrder(sessionId, symbol, side, quantity, OrdType.MARKET, 0);
    }

    /**
     * Sends a batch of Limit orders for benchmarking purposes.
     *
     * @param sessionId FIX session to send orders to
     * @param symbol    instrument symbol
     * @param side      order side
     * @param quantity  order quantity per order
     * @param price     limit price
     * @param count     number of orders to send
     * @return number of orders successfully sent
     */
    public int sendBatch(SessionID sessionId, String symbol, char side, double quantity, double price, int count) {
        int sent = 0;
        for (int i = 0; i < count; i++) {
            String clOrdId = sendLimitOrder(sessionId, symbol, side, quantity, price);
            if (clOrdId != null) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * Returns the latency tracker used by this service.
     *
     * @return latency tracker instance
     */
    public LatencyTracker getLatencyTracker() {
        return latencyTracker;
    }

    // ── Internal methods ────────────────────────────────────────────────

    /**
     * Creates and sends a NewOrderSingle (35=D) FIX 4.4 message.
     *
     * @param sessionId FIX session to send the order to
     * @param symbol    instrument symbol
     * @param side      order side
     * @param quantity  order quantity
     * @param ordType   order type (Limit, Market, etc.)
     * @param price     limit price (ignored for Market orders)
     * @return the ClOrdID assigned to this order, or {@code null} if sending failed
     */
    private String sendOrder(SessionID sessionId, String symbol, char side, double quantity, char ordType, double price) {
        String clOrdId = generateClOrdId();

        try {
            NewOrderSingle order = new NewOrderSingle(
                    new ClOrdID(clOrdId),
                    new Side(side),
                    new TransactTime(LocalDateTime.now()),
                    new OrdType(ordType)
            );

            order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
            order.set(new Symbol(symbol));
            order.set(new OrderQty(quantity));

            if (ordType == OrdType.LIMIT) {
                order.set(new Price(price));
            }

            // Record send time BEFORE sending for accurate latency measurement
            latencyTracker.recordSendTime(clOrdId);

            boolean sent = Session.sendToTarget(order, sessionId);

            if (sent) {
                System.out.printf("[OrderService] Order SENT: ClOrdID=%s, Symbol=%s, Side=%s, Qty=%.0f, Type=%s%s%n",
                        clOrdId, symbol,
                        side == Side.BUY ? "BUY" : "SELL",
                        quantity,
                        ordType == OrdType.LIMIT ? "LIMIT" : "MARKET",
                        ordType == OrdType.LIMIT ? String.format(", Price=%.2f", price) : "");
                return clOrdId;
            } else {
                // Remove from tracker if send failed
                latencyTracker.recordReceiveTime(clOrdId);
                System.err.println("[OrderService] FAILED to send order: ClOrdID=" + clOrdId
                        + " (session not logged on or not found)");
                return null;
            }
        } catch (SessionNotFound e) {
            // Remove from tracker if session not found
            latencyTracker.recordReceiveTime(clOrdId);
            System.err.println("[OrderService] Session not found: " + sessionId + " — " + e.getMessage());
            return null;
        }
    }

    /**
     * Generates a unique ClOrdID using an atomic counter with a prefix.
     *
     * @return unique ClOrdID string (e.g., "ORD-00001")
     */
    private String generateClOrdId() {
        return String.format("ORD-%05d", orderIdCounter.getAndIncrement());
    }
}
