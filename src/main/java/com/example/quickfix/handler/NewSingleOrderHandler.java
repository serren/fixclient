package com.example.quickfix.handler;

import com.example.quickfix.service.IExecutionReportService;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
import quickfix.field.ClOrdID;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Symbol;

import static com.example.quickfix.MessageUtil.getFieldSafe;

public class NewSingleOrderHandler extends AbstractExecutionMessageHandler {

    public NewSingleOrderHandler(IExecutionReportService executionReportService) {
        super(executionReportService);
    }

    @Override
    public void handle(Message message, SessionID sessionId) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        try {
            String clOrdId = message.getString(ClOrdID.FIELD);
            String symbol = getFieldSafe(message, Symbol.FIELD);
            char side = message.getChar(quickfix.field.Side.FIELD);
            String qty = getFieldSafe(message, OrderQty.FIELD);
            char ordType = message.getChar(quickfix.field.OrdType.FIELD);
            String price = ordType == quickfix.field.OrdType.LIMIT
                    ? getFieldSafe(message, Price.FIELD) : "MARKET";

            log("NEW ORDER IN", sessionId,
                    String.format("ClOrdID=%s, Symbol=%s, Side=%s, Qty=%s, Type=%s, Price=%s",
                            clOrdId, symbol,
                            side == quickfix.field.Side.BUY ? "BUY" : "SELL",
                            qty,
                            ordType == quickfix.field.OrdType.LIMIT ? "LIMIT" : "MARKET",
                            price));
        } catch (FieldNotFound e) {
            log("NEW ORDER IN", sessionId,
                    "NewOrderSingle received (parse error): " + message.toRawString().replace('\001', '|'));
        }

        if (executionReportService != null) {
            executionReportService.handleNewOrderSingle(message, sessionId);
        }
    }
}
