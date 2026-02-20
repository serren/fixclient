package com.example.quickfix.service;

import com.example.quickfix.handler.MessageHandler;
import quickfix.Message;
import quickfix.SessionID;

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
}