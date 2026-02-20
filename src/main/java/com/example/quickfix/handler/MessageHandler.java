package com.example.quickfix.handler;

import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

/**
 * Functional interface for message handling callbacks.
 * <p>
 * Implementations perform the actual business logic for processing
 * incoming FIX messages (parsing, logging, state updates, response generation, etc.).
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * Handles an incoming FIX application message.
     *
     * @param message   the incoming message
     * @param sessionId the session the message was received on
     * @throws FieldNotFound          if a required field is missing
     * @throws IncorrectDataFormat    if the data format is incorrect
     * @throws IncorrectTagValue      if a tag value is invalid
     * @throws UnsupportedMessageType if the message type is not supported
     */
    void handle(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType;
}
