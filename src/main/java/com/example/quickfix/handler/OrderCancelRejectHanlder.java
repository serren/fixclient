package com.example.quickfix.handler;

import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
import quickfix.field.ClOrdID;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrigClOrdID;

import static com.example.quickfix.util.MessageUtil.describeCxlRejReason;
import static com.example.quickfix.util.MessageUtil.describeCxlRejResponseTo;
import static com.example.quickfix.util.MessageUtil.describeOrdStatus;
import static com.example.quickfix.util.MessageUtil.extractText;
import static com.example.quickfix.util.MessageUtil.getFieldSafe;

public class OrderCancelRejectHanlder extends AbstractMessageHandler {
    @Override
    public void handle(Message message, SessionID sessionId) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        try {
            String clOrdId = message.getString(ClOrdID.FIELD);
            String origClOrdId = getFieldSafe(message, OrigClOrdID.FIELD);
            String orderId = getFieldSafe(message, OrderID.FIELD);
            char ordStatus = message.getChar(OrdStatus.FIELD);
            String ordStatusStr = describeOrdStatus(ordStatus);

            String cxlRejReason = "N/A";
            if (message.isSetField(CxlRejReason.FIELD)) {
                cxlRejReason = describeCxlRejReason(message.getInt(CxlRejReason.FIELD));
            }

            String cxlRejResponseTo = "N/A";
            if (message.isSetField(CxlRejResponseTo.FIELD)) {
                cxlRejResponseTo = describeCxlRejResponseTo(message.getChar(CxlRejResponseTo.FIELD));
            }

            String text = extractText(message);

            log("CANCEL REJECT", sessionId,
                    String.format("ClOrdID=%s, OrigClOrdID=%s, OrderID=%s, OrdStatus=%s, "
                                    + "CxlRejReason=%s, ResponseTo=%s%s",
                            clOrdId, origClOrdId, orderId, ordStatusStr,
                            cxlRejReason, cxlRejResponseTo,
                            text.isEmpty() ? "" : ", Text=" + text));

        } catch (FieldNotFound e) {
            log("CANCEL REJECT", sessionId,
                    "OrderCancelReject received (parse error: " + e.getMessage() + "): "
                            + message.toRawString().replace('\001', '|'));
        }
    }
}
