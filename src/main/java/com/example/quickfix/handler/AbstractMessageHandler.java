package com.example.quickfix.handler;

import quickfix.SessionID;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Base class for message handlers providing common functionality.
 */
public abstract class AbstractMessageHandler implements MessageHandler {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Formatted console output with a timestamp.
     */
    public static void log(String event, SessionID sessionId, String details) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        System.out.printf("[%s] [%-18s] [%s:%s] %s%n",
                timestamp,
                event,
                sessionId.getSenderCompID(),
                sessionId.getTargetCompID(),
                details);
    }
}