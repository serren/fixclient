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
import quickfix.field.OrigClOrdID;

import static com.example.quickfix.MessageUtil.getFieldSafe;

public class CancelOrderHandler extends AbstractExecutionMessageHandler {
    public CancelOrderHandler(IExecutionReportService executionReportService) {
        super(executionReportService);
    }

    /**
     * Handles an incoming OrderCancelRequest (35=F) on the Acceptor side.
     * Logs the request and delegates to {@link ExecutionReportService} for response generation.
     *
     * @param message   the incoming OrderCancelRequest message
     * @param sessionId session identifier
     */
    @Override
    public void handle(Message message, SessionID sessionId) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        try {
            String clOrdId = message.getString(ClOrdID.FIELD);
            String origClOrdId = getFieldSafe(message, OrigClOrdID.FIELD);

            log("CANCEL REQ IN", sessionId,
                    String.format("ClOrdID=%s, OrigClOrdID=%s", clOrdId, origClOrdId));
        } catch (FieldNotFound e) {
            log("CANCEL REQ IN", sessionId,
                    "OrderCancelRequest received (parse error): " + message.toRawString().replace('\001', '|'));
        }

        if (executionReportService != null) {
            executionReportService.handleOrderCancelRequest(message, sessionId);
        }
    }
}
