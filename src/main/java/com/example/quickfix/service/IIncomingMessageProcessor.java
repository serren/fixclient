package com.example.quickfix.service;

import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

/**
 * Asynchronous processor for incoming FIX application messages.
 * <p>
 * Decouples message handling from the QuickFIX/J session thread by placing
 * incoming messages into a bounded queue and processing them in a dedicated
 * thread pool.
 */
public interface IIncomingMessageProcessor {

    /**
     * Submits an incoming FIX message for asynchronous processing.
     * <p>
     * The message and sessionId are captured and passed to the provided handler
     * in a worker thread. If the internal queue is full, the calling (session) thread
     * will execute the handler directly (CallerRunsPolicy).
     *
     * @param message   the incoming FIX application message
     * @param sessionId the session the message was received on
     * @param handler   the callback that performs the actual message processing
     */
    void submit(Message message, SessionID sessionId, MessageHandler handler);

    /**
     * Gracefully shuts down the processor, waiting for queued messages to be processed.
     */
    void shutdown();

    /**
     * Functional interface for message handling callbacks.
     * <p>
     * Implementations perform the actual business logic for processing
     * incoming FIX messages (parsing, logging, state updates, response generation, etc.).
     */
    @FunctionalInterface
    interface MessageHandler {

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
}