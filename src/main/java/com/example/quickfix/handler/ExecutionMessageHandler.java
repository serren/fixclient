package com.example.quickfix.handler;

import com.example.quickfix.latency.ILatencyTracker;
import com.example.quickfix.service.IOrderService;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LeavesQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Symbol;

import static com.example.quickfix.util.MessageUtil.describeExecType;
import static com.example.quickfix.util.MessageUtil.describeOrdStatus;
import static com.example.quickfix.util.MessageUtil.getDoubleSafe;
import static com.example.quickfix.util.MessageUtil.getFieldSafe;

public class ExecutionMessageHandler extends AbstractMessageHandler {

    /** Latency tracker for measuring order round-trip times. Set externally. */
    private final ILatencyTracker latencyTracker;
    /** Order service for managing active orders. Set externally. */
    private final IOrderService orderService;

    public ExecutionMessageHandler(ILatencyTracker latencyTracker, IOrderService orderService) {
        this.latencyTracker = latencyTracker;
        this.orderService = orderService;
    }

    /**
     * Handles an incoming ExecutionReport (35=8).
     * Extracts key fields, measures round-trip latency, and logs the result.
     *
     * @param message   the ExecutionReport message
     * @param sessionId session identifier
     */
    @Override
    public void handle(Message message, SessionID sessionId) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        try {
            String clOrdId = message.getString(ClOrdID.FIELD);
            String orderId = getFieldSafe(message, OrderID.FIELD);
            String execId = getFieldSafe(message, ExecID.FIELD);
            char execType = message.getChar(ExecType.FIELD);
            char ordStatus = message.getChar(OrdStatus.FIELD);
            String symbol = getFieldSafe(message, Symbol.FIELD);
            String cumQty = getFieldSafe(message, CumQty.FIELD);
            String leavesQty = getFieldSafe(message, LeavesQty.FIELD);
            String avgPx = getFieldSafe(message, AvgPx.FIELD);

            String execTypeStr = describeExecType(execType);
            String ordStatusStr = describeOrdStatus(ordStatus);

            // Measure round-trip latency for ExecType=NEW, CANCELED, and REPLACED
            String latencyInfo = "";
            if (latencyTracker != null
                    && (execType == ExecType.NEW || execType == ExecType.CANCELED || execType == ExecType.REPLACED)) {
                double latencyMs = latencyTracker.recordReceiveTime(clOrdId);
                if (latencyMs >= 0) {
                    latencyInfo = String.format(" | RTT=%.3f ms", latencyMs);
                }
            }

            // Remove order from active orders when it is canceled or fully filled
            if (orderService != null
                    && (execType == ExecType.CANCELED || execType == ExecType.FILL)) {
                orderService.removeActiveOrder(clOrdId);
            }

            // Update active order after a successful replace
            if (orderService != null && execType == ExecType.REPLACED) {
                String origClOrdId = getFieldSafe(message, OrigClOrdID.FIELD);
                double replacedQty = getDoubleSafe(message, OrderQty.FIELD);
                double replacedPrice = getDoubleSafe(message, Price.FIELD);
                orderService.updateActiveOrder(origClOrdId, clOrdId, replacedQty, replacedPrice);
            }

            log("EXEC REPORT", sessionId,
                    String.format("ClOrdID=%s, OrderID=%s, ExecID=%s, ExecType=%s, OrdStatus=%s, "
                                    + "Symbol=%s, CumQty=%s, LeavesQty=%s, AvgPx=%s%s",
                            clOrdId, orderId, execId, execTypeStr, ordStatusStr,
                            symbol, cumQty, leavesQty, avgPx, latencyInfo));

        } catch (FieldNotFound e) {
            log("EXEC REPORT", sessionId,
                    "ExecutionReport received (parse error: " + e.getMessage() + "): "
                            + message.toRawString().replace('\001', '|'));
        }
    }
}
