package com.example.quickfix.handler;

import com.example.quickfix.service.ExecutionReportService;
import com.example.quickfix.service.IExecutionReportService;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
import quickfix.field.ClOrdID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;

import static com.example.quickfix.util.MessageUtil.getFieldSafe;

public class ReplaceOrderHandler extends AbstractExecutionMessageHandler {

    public ReplaceOrderHandler(IExecutionReportService executionReportService) {
        super(executionReportService);
    }

    /**
     * Handles an incoming OrderCancelReplaceRequest (35=G) on the Acceptor side.
     * Logs the request and delegates to {@link ExecutionReportService} for response generation.
     *
     * @param message   the incoming OrderCancelReplaceRequest message
     * @param sessionId session identifier
     */
    @Override
    public void handle(Message message, SessionID sessionId) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        try {
            String clOrdId = message.getString(ClOrdID.FIELD);
            String origClOrdId = getFieldSafe(message, OrigClOrdID.FIELD);
            String qty = getFieldSafe(message, OrderQty.FIELD);
            String price = getFieldSafe(message, Price.FIELD);

            log("REPLACE REQ IN", sessionId,
                    String.format("ClOrdID=%s, OrigClOrdID=%s, NewQty=%s, NewPrice=%s",
                            clOrdId, origClOrdId, qty, price));
        } catch (FieldNotFound e) {
            log("REPLACE REQ IN", sessionId,
                    "OrderCancelReplaceRequest received (parse error): "
                            + message.toRawString().replace('\001', '|'));
        }

        if (executionReportService != null) {
            executionReportService.handleOrderCancelReplaceRequest(message, sessionId);
        }
    }
}
