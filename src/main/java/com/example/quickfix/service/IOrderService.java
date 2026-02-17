package com.example.quickfix.service;

import quickfix.SessionID;

import java.util.concurrent.ConcurrentMap;

/**
 * Service for creating and sending FIX order messages.
 * <p>
 * Supports NewOrderSingle (35=D), OrderCancelRequest (35=F),
 * and OrderCancelReplaceRequest (35=G).
 */
public interface IOrderService {

    /**
     * Sends a Limit NewOrderSingle to the specified FIX session.
     *
     * @param sessionId FIX session to send the order to
     * @param symbol    instrument symbol (e.g., "AAPL", "MSFT")
     * @param side      order side: {@link quickfix.field.Side#BUY} or {@link quickfix.field.Side#SELL}
     * @param quantity  order quantity
     * @param price     limit price
     * @return the ClOrdID assigned to this order, or {@code null} if sending failed
     */
    String sendLimitOrder(SessionID sessionId, String symbol, char side, double quantity, double price);

    /**
     * Sends a Market NewOrderSingle to the specified FIX session.
     *
     * @param sessionId FIX session to send the order to
     * @param symbol    instrument symbol (e.g., "AAPL", "MSFT")
     * @param side      order side: {@link quickfix.field.Side#BUY} or {@link quickfix.field.Side#SELL}
     * @param quantity  order quantity
     * @return the ClOrdID assigned to this order, or {@code null} if sending failed
     */
    String sendMarketOrder(SessionID sessionId, String symbol, char side, double quantity);

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
    int sendBatch(SessionID sessionId, String symbol, char side, double quantity, double price, int count);

    /**
     * Sends an OrderCancelRequest (35=F) for a previously sent order.
     *
     * @param sessionId   FIX session to send the cancel request to
     * @param origClOrdId the ClOrdID of the order to cancel
     * @return the new ClOrdID assigned to this cancel request, or {@code null} if sending failed
     */
    String sendCancelOrder(SessionID sessionId, String origClOrdId);

    /**
     * Sends an OrderCancelReplaceRequest (35=G) to modify a previously sent order.
     *
     * @param sessionId   FIX session to send the replace request to
     * @param origClOrdId the ClOrdID of the order to replace
     * @param newQuantity new order quantity (0 to keep original)
     * @param newPrice    new limit price (0 to keep original)
     * @return the new ClOrdID assigned to this replace request, or {@code null} if sending failed
     */
    String sendCancelReplaceOrder(SessionID sessionId, String origClOrdId, double newQuantity, double newPrice);

    /**
     * Returns the map of active (tracked) orders.
     * Can be used to display available orders for cancellation.
     *
     * @return unmodifiable view of active orders keyed by ClOrdID
     */
    ConcurrentMap<String, OrderDetails> getActiveOrders();

    /**
     * Removes an order from the active orders map.
     * Should be called when an order is confirmed as canceled or fully filled.
     *
     * @param clOrdId the ClOrdID of the order to remove
     */
    void removeActiveOrder(String clOrdId);

    /**
     * Updates an active order's details after a successful replace.
     * Replaces the old ClOrdID entry with the new one and updated parameters.
     *
     * @param origClOrdId the original ClOrdID being replaced
     * @param newClOrdId  the new ClOrdID from the replace confirmation
     * @param newQuantity new order quantity (0 to keep original)
     * @param newPrice    new limit price (0 to keep original)
     */
    void updateActiveOrder(String origClOrdId, String newClOrdId, double newQuantity, double newPrice);
}