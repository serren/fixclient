package com.example.quickfix.handler;

import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

public class DefaultMessageHandler extends AbstractMessageHandler {

    @Override
    public void handle(Message message, SessionID sessionId) throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        log("APP IN", sessionId, "Received: " + message.toRawString().replace('\001', '|'));
    }
}
