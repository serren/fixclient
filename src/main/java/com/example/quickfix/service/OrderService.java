package com.example.quickfix.service;

import com.example.quickfix.latency.ILatencyTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.ClOrdID;
import quickfix.field.HandlInst;
import quickfix.field.NoUnderlyings;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.SecurityType;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.field.UnderlyingSymbol;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for creating and sending FIX NewOrderSingle (35=D) messages.
 * <p>
 * Each order is assigned a unique ClOrdID and its send time is recorded
 * in the {@link ILatencyTracker} for round-trip latency measurement.
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
 * <p>
 * Also supports:
 * <ul>
 *   <li>OrderCancelRequest (35=F) — cancel a previously sent order</li>
 *   <li>OrderCancelReplaceRequest (35=G) — modify (cancel/replace) a previously sent order</li>
 * </ul>
 */
public class OrderService implements IOrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    /** Atomic counter for generating unique ClOrdID values */
    private final AtomicLong orderIdCounter = new AtomicLong(1);
    
    /** Latency tracker for measuring round-trip times */
    private final ILatencyTracker latencyTracker;
    
    /** Stores order details for cancel requests: ClOrdID → OrderDetails */
    private final ConcurrentMap<String, OrderDetails> activeOrders = new ConcurrentHashMap<>();

    /**
     * Creates an OrderService with the specified latency tracker.
     *
     * @param latencyTracker tracker for recording send/receive timestamps
     */
    public OrderService(ILatencyTracker latencyTracker) {
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
     * Sends an OrderCancelRequest (35=F) for a previously sent order.
     * <p>
     * The cancel request references the original order by its ClOrdID (OrigClOrdID)
     * and includes the required fields: Symbol, Side, and OrderQty from the original order.
     *
     * @param sessionId FIX session to send the cancel request to
     * @param origClOrdId the ClOrdID of the order to cancel
     * @return the new ClOrdID assigned to this cancel request, or {@code null} if sending failed
     */
    public String sendCancelOrder(SessionID sessionId, String origClOrdId) {
        OrderDetails details = activeOrders.get(origClOrdId);
        if (details == null) {
            System.err.println("[OrderService] Cannot cancel — original order not found: ClOrdID=" + origClOrdId);
            return null;
        }
    
        String cancelClOrdId = generateClOrdId();
    
        try {
            OrderCancelRequest cancelRequest = new OrderCancelRequest(
                    new OrigClOrdID(origClOrdId),
                    new ClOrdID(cancelClOrdId),
                    new Side(details.side),
                    new TransactTime(LocalDateTime.now())
            );
    
            cancelRequest.set(new Symbol(details.symbol));
            cancelRequest.set(new OrderQty(details.quantity));
    
            // Record send time BEFORE sending for accurate latency measurement
            latencyTracker.recordSendTime(cancelClOrdId);
        
            boolean sent = Session.sendToTarget(cancelRequest, sessionId);
        
            if (sent) {
                System.out.printf("[OrderService] Cancel SENT: ClOrdID=%s, OrigClOrdID=%s, Symbol=%s, Side=%s%n",
                        cancelClOrdId, origClOrdId, details.symbol,
                        details.side == Side.BUY ? "BUY" : "SELL");
                return cancelClOrdId;
            } else {
                // Remove from tracker if send failed
                latencyTracker.recordReceiveTime(cancelClOrdId);
                System.err.println("[OrderService] FAILED to send cancel: ClOrdID=" + cancelClOrdId
                        + " (session not logged on or not found)");
                return null;
            }
        } catch (SessionNotFound e) {
            // Remove from tracker if session not found
            latencyTracker.recordReceiveTime(cancelClOrdId);
            System.err.println("[OrderService] Session not found: " + sessionId + " — " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Returns the map of active (tracked) orders.
     * Can be used to display available orders for cancellation.
     *
     * @return unmodifiable view of active orders keyed by ClOrdID
     */
    public Map<String, OrderDetails> getActiveOrders() {
        return activeOrders;
    }
    
    /**
     * Removes an order from the active orders map.
     * Should be called when an order is confirmed as canceled or fully filled.
     *
     * @param clOrdId the ClOrdID of the order to remove
     */
    public void removeActiveOrder(String clOrdId) {
        activeOrders.remove(clOrdId);
    }
    
    /**
     * Sends an OrderCancelReplaceRequest (35=G) to modify a previously sent order.
     * <p>
     * The replace request references the original order by its ClOrdID (OrigClOrdID)
     * and includes the updated fields. Only the provided non-null/non-zero values
     * will override the original order parameters.
     *
     * @param sessionId   FIX session to send the replace request to
     * @param origClOrdId the ClOrdID of the order to replace
     * @param newQuantity new order quantity (0 to keep original)
     * @param newPrice    new limit price (0 to keep original)
     * @return the new ClOrdID assigned to this replace request, or {@code null} if sending failed
     */
    public String sendCancelReplaceOrder(SessionID sessionId, String origClOrdId,
                                         double newQuantity, double newPrice) {
        OrderDetails details = activeOrders.get(origClOrdId);
        if (details == null) {
            System.err.println("[OrderService] Cannot replace — original order not found: ClOrdID=" + origClOrdId);
            return null;
        }
    
        String replaceClOrdId = generateClOrdId();
        double effectiveQty = newQuantity > 0 ? newQuantity : details.quantity;
        double effectivePrice = newPrice > 0 ? newPrice : details.price;
        char effectiveOrdType = details.ordType;
    
        try {
            OrderCancelReplaceRequest replaceRequest = new OrderCancelReplaceRequest(
                    new OrigClOrdID(origClOrdId),
                    new ClOrdID(replaceClOrdId),
                    new Side(details.side),
                    new TransactTime(LocalDateTime.now()),
                    new OrdType(effectiveOrdType)
            );
    
            replaceRequest.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
            replaceRequest.set(new Symbol(details.symbol));
            replaceRequest.set(new OrderQty(effectiveQty));
    
            if (effectiveOrdType == OrdType.LIMIT) {
                replaceRequest.set(new Price(effectivePrice));
            }
    
            // Record send time BEFORE sending for accurate latency measurement
            latencyTracker.recordSendTime(replaceClOrdId);
    
            boolean sent = Session.sendToTarget(replaceRequest, sessionId);
    
            if (sent) {
                System.out.printf("[OrderService] Replace SENT: ClOrdID=%s, OrigClOrdID=%s, Symbol=%s, "
                                + "Side=%s, Qty=%.0f, Type=%s%s%n",
                        replaceClOrdId, origClOrdId, details.symbol,
                        details.side == Side.BUY ? "BUY" : "SELL",
                        effectiveQty,
                        effectiveOrdType == OrdType.LIMIT ? "LIMIT" : "MARKET",
                        effectiveOrdType == OrdType.LIMIT ? String.format(", Price=%.2f", effectivePrice) : "");
                return replaceClOrdId;
            } else {
                // Remove from tracker if send failed
                latencyTracker.recordReceiveTime(replaceClOrdId);
                System.err.println("[OrderService] FAILED to send replace: ClOrdID=" + replaceClOrdId
                        + " (session not logged on or not found)");
                return null;
            }
        } catch (SessionNotFound e) {
            // Remove from tracker if session not found
            latencyTracker.recordReceiveTime(replaceClOrdId);
            System.err.println("[OrderService] Session not found: " + sessionId + " — " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Updates an active order's details after a successful replace.
     * Replaces the old ClOrdID entry with the new one and updated parameters.
     *
     * @param origClOrdId the original ClOrdID being replaced
     * @param newClOrdId  the new ClOrdID from the replace confirmation
     * @param newQuantity new order quantity (0 to keep original)
     * @param newPrice    new limit price (0 to keep original)
     */
    public void updateActiveOrder(String origClOrdId, String newClOrdId,
                                  double newQuantity, double newPrice) {
        OrderDetails oldDetails = activeOrders.remove(origClOrdId);
        if (oldDetails != null) {
            double effectiveQty = newQuantity > 0 ? newQuantity : oldDetails.quantity;
            double effectivePrice = newPrice > 0 ? newPrice : oldDetails.price;
            activeOrders.put(newClOrdId,
                    new OrderDetails(oldDetails.symbol, oldDetails.side, effectiveQty,
                            effectivePrice, oldDetails.ordType));
        }
    }

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

            order.set(new NoUnderlyings(1));
            order.set(new SecurityType(SecurityType.OPTION));
            order.setField(UnderlyingSymbol.FIELD, new UnderlyingSymbol(symbol));

            // Record send time BEFORE sending for accurate latency measurement
            latencyTracker.recordSendTime(clOrdId);
            
            // Store order details for potential cancel/replace requests
            activeOrders.put(clOrdId, new OrderDetails(symbol, side, quantity, price, ordType));
            
            boolean sent = Session.sendToTarget(order, sessionId);

            if (sent) {
                log.info("[OrderService] Sent order: {}", order);
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
