package com.example.quickfix.service;

import quickfix.Message;
import quickfix.SessionID;

/**
 * Service for generating ExecutionReport (35=8) responses
 * for incoming order messages.
 * <p>
 * This service is intended to be used on the <b>Acceptor</b> side only.
 */
public interface IExecutionReportService {

    /**
     * Processes an incoming NewOrderSingle (35=D).
     * Sends an ExecutionReport with ExecType=NEW after a random delay.
     *
     * @param message   the incoming NewOrderSingle message
     * @param sessionId the FIX session to respond on
     */
    void handleNewOrderSingle(Message message, SessionID sessionId);

    /**
     * Processes an incoming OrderCancelRequest (35=F).
     * Sends an ExecutionReport with ExecType=CANCELED after a random delay.
     *
     * @param message   the incoming OrderCancelRequest message
     * @param sessionId the FIX session to respond on
     */
    void handleOrderCancelRequest(Message message, SessionID sessionId);

    /**
     * Processes an incoming OrderCancelReplaceRequest (35=G).
     * Sends an ExecutionReport with ExecType=REPLACED after a random delay.
     *
     * @param message   the incoming OrderCancelReplaceRequest message
     * @param sessionId the FIX session to respond on
     */
    void handleOrderCancelReplaceRequest(Message message, SessionID sessionId);

    /**
     * Gracefully shuts down the service, waiting for pending responses to be sent.
     */
    void shutdown();
}