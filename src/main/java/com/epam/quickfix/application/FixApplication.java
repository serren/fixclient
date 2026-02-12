package com.epam.quickfix.application;

import com.epam.quickfix.Starter;
import com.epam.quickfix.latency.LatencyTracker;
import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.Symbol;
import quickfix.field.Text;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Implementation of the {@link Application} interface for FIX Initiator/Acceptor.
 * <p>
 * Supports all FIX protocol versions (4.0 — 5.0SP2). The version is determined from the configuration file.
 * <p>
 * Handles the FIX session lifecycle:
 * <ul>
 *   <li>{@link #onCreate}       — session created</li>
 *   <li>{@link #onLogon}        — successful logon</li>
 *   <li>{@link #onLogout}       — logout (normal or due to error)</li>
 *   <li>{@link #toAdmin}        — outgoing administrative message (Logon, Logout, Heartbeat, etc.)</li>
 *   <li>{@link #fromAdmin}      — incoming administrative message</li>
 *   <li>{@link #toApp}          — outgoing application message</li>
 *   <li>{@link #fromApp}        — incoming application message</li>
 * </ul>
 */
public class FixApplication implements Application {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Connection type: "acceptor" or "initiator". Set externally. */
    private String connectionType = "";
    
    /** Latency tracker for measuring order round-trip times. Set externally. */
    private LatencyTracker latencyTracker;

    /**
     * Sets the connection type for correct operating mode detection.
     *
     * @param connectionType "initiator" or "acceptor"
     */
    public void setConnectionType(String connectionType) {
        this.connectionType = connectionType == null ? "" : connectionType.trim().toLowerCase();
    }
    
    /**
     * Sets the latency tracker for recording ExecutionReport round-trip times.
     *
     * @param latencyTracker latency tracker instance
     */
    public void setLatencyTracker(LatencyTracker latencyTracker) {
        this.latencyTracker = latencyTracker;
    }

    // ── Lifecycle callbacks ─────────────────────────────────────────────

    /**
     * Called when a new FIX session is created.
     * At this point, the connection is not yet established.
     */
    @Override
    public void onCreate(SessionID sessionId) {
        log("SESSION CREATED", sessionId, "Session object initialized, waiting for connection...");
    }

    /**
     * Called after a successful Logon message exchange (35=A).
     * The session is fully established; application messages can now be sent.
     */
    @Override
    public void onLogon(SessionID sessionId) {
        log("LOGON SUCCESS", sessionId,
                "FIX session established. Sender=" + sessionId.getSenderCompID()
                        + ", Target=" + sessionId.getTargetCompID()
                        + ", BeginString=" + sessionId.getBeginString());
    
        // If we are running in Acceptor mode, a new client has connected
        if (isAcceptorMode()) {
            System.out.println("\n[FIX Acceptor] NEW CLIENT CONNECTED: " + sessionId.getTargetCompID() 
                    + " → " + sessionId.getSenderCompID() + " [" + sessionId.getBeginString() + "]");
        }
    }
    
    /**
     * Determines whether the application is running in Acceptor mode.
     *
     * @return {@code true} if the application is running in Acceptor mode
     */
    private boolean isAcceptorMode() {
        return Starter.CONNECTION_TYPE_ACCEPTOR.equals(connectionType);
    }

    /**
     * Called when the session is terminated (logout).
     * Reasons: normal Logout, connection loss, sequence number errors, etc.
     */
    @Override
    public void onLogout(SessionID sessionId) {
        log("LOGOUT", sessionId,
                "FIX session disconnected. Sender=" + sessionId.getSenderCompID()
                        + ", Target=" + sessionId.getTargetCompID());
    
        if (isAcceptorMode()) {
            System.out.println("\n[FIX Acceptor] CLIENT DISCONNECTED: " + sessionId.getTargetCompID() 
                    + " → " + sessionId.getSenderCompID() + " [" + sessionId.getBeginString() + "]");
        }
    }

    // ── Administrative messages ─────────────────────────────────────────

    /**
     * Called before sending an administrative message.
     * <p>
     * Outgoing Logon/Logout/Heartbeat messages can be modified here.
     * For example, Username/Password can be added to Logon (35=A).
     *
     * @param message   outgoing message
     * @param sessionId session identifier
     * @throws DoNotSend if the message should not be sent
     */
    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);

            switch (msgType) {
                case MsgType.LOGON -> {
                    log("SENDING LOGON", sessionId,
                            "Logon request (35=A) → " + sessionId.getTargetCompID());
                    // Authentication can be added here:
                    // message.setField(new Username("myUser"));
                    // message.setField(new Password("myPass"));
                }
                case MsgType.LOGOUT -> {
                    String text = extractText(message);
                    log("SENDING LOGOUT", sessionId,
                            "Logout request (35=5) → " + sessionId.getTargetCompID()
                                    + (text.isEmpty() ? "" : " | Reason: " + text));
                }
                case MsgType.HEARTBEAT ->
                        log("HEARTBEAT OUT", sessionId, "Heartbeat (35=0) → " + sessionId.getTargetCompID());
                default ->
                        log("ADMIN OUT", sessionId, "Admin message (35=" + msgType + ") → " + sessionId.getTargetCompID());
            }
        } catch (FieldNotFound e) {
            log("ADMIN OUT", sessionId, "Admin message (unknown type) → " + sessionId.getTargetCompID());
        }
    }

    /**
     * Called when an administrative message is received from the counterparty.
     * <p>
     * Incoming Logon/Logout messages can be processed here, credentials can be verified, etc.
     * Throwing {@link RejectLogon} will reject the incoming Logon.
     *
     * @param message   incoming message
     * @param sessionId session identifier
     * @throws FieldNotFound      if a required field is missing
     * @throws IncorrectDataFormat if the data format is incorrect
     * @throws IncorrectTagValue   if a tag value is invalid
     * @throws RejectLogon         if the counterparty's logon should be rejected
     */
    @Override
    public void fromAdmin(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);

            switch (msgType) {
                case MsgType.LOGON -> {
                    log("RECEIVED LOGON", sessionId,
                            "Logon confirmation (35=A) ← " + sessionId.getTargetCompID());
                    // Counterparty credentials can be verified here:
                    // if (!isValidCounterparty(message)) throw new RejectLogon("Invalid credentials");
                }
                case MsgType.LOGOUT -> {
                    String text = extractText(message);
                    log("RECEIVED LOGOUT", sessionId,
                            "Logout (35=5) ← " + sessionId.getTargetCompID()
                                    + (text.isEmpty() ? "" : " | Reason: " + text));
                }
                case MsgType.HEARTBEAT ->
                        log("HEARTBEAT IN", sessionId, "Heartbeat (35=0) ← " + sessionId.getTargetCompID());
                case MsgType.REJECT -> {
                    String text = extractText(message);
                    log("REJECT", sessionId,
                            "Session-level reject (35=3) ← " + sessionId.getTargetCompID()
                                    + (text.isEmpty() ? "" : " | Reason: " + text));
                }
                default ->
                        log("ADMIN IN", sessionId, "Admin message (35=" + msgType + ") ← " + sessionId.getTargetCompID());
            }
        } catch (FieldNotFound e) {
            log("ADMIN IN", sessionId, "Admin message (unknown type) ← " + sessionId.getTargetCompID());
        }
    }

    // ── Application messages ────────────────────────────────────────────

    /**
     * Called before sending an application message (NewOrderSingle, Cancel, etc.).
     *
     * @param message   outgoing message
     * @param sessionId session identifier
     * @throws DoNotSend if the message should not be sent
     */
    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        log("APP OUT", sessionId, "Sending: " + message.toRawString().replace('\001', '|'));
    }

    /**
     * Called when an application message is received from the counterparty
     * (ExecutionReport, MarketData, etc.).
     *
     * @param message   incoming message
     * @param sessionId session identifier
     * @throws FieldNotFound           if a required field is missing
     * @throws IncorrectDataFormat     if the data format is incorrect
     * @throws IncorrectTagValue       if a tag value is invalid
     * @throws UnsupportedMessageType  if the message type is not supported
     */
    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        String msgType = message.getHeader().getString(MsgType.FIELD);
    
        if (MsgType.EXECUTION_REPORT.equals(msgType)) {
            handleExecutionReport(message, sessionId);
        } else {
            log("APP IN", sessionId, "Received: " + message.toRawString().replace('\001', '|'));
        }
    }
    
    /**
     * Handles an incoming ExecutionReport (35=8).
     * Extracts key fields, measures round-trip latency, and logs the result.
     *
     * @param message   the ExecutionReport message
     * @param sessionId session identifier
     */
    private void handleExecutionReport(Message message, SessionID sessionId) {
        try {
            String clOrdId = message.getString(ClOrdID.FIELD);
            String orderId = getFieldSafe(message, OrderID.FIELD);
            String execId = getFieldSafe(message, ExecID.FIELD);
            char execType = message.getChar(ExecType.FIELD);
            char ordStatus = message.getChar(OrdStatus.FIELD);
            String symbol = getFieldSafe(message, Symbol.FIELD);
            String cumQty = getFieldSafe(message, CumQty.FIELD);
            String leavesQty = getFieldSafe(message, LeavesQty.FIELD);
            String avgPx = getFieldSafe(message, AvgPx.FIELD);
    
            String execTypeStr = describeExecType(execType);
            String ordStatusStr = describeOrdStatus(ordStatus);
    
            // Measure round-trip latency
            String latencyInfo = "";
            if (latencyTracker != null) {
                double latencyMs = latencyTracker.recordReceiveTime(clOrdId);
                if (latencyMs >= 0) {
                    latencyInfo = String.format(" | RTT=%.3f ms", latencyMs);
                }
            }
    
            log("EXEC REPORT", sessionId,
                    String.format("ClOrdID=%s, OrderID=%s, ExecID=%s, ExecType=%s, OrdStatus=%s, "
                                    + "Symbol=%s, CumQty=%s, LeavesQty=%s, AvgPx=%s%s",
                            clOrdId, orderId, execId, execTypeStr, ordStatusStr,
                            symbol, cumQty, leavesQty, avgPx, latencyInfo));
    
        } catch (FieldNotFound e) {
            log("EXEC REPORT", sessionId,
                    "ExecutionReport received (parse error: " + e.getMessage() + "): "
                            + message.toRawString().replace('\001', '|'));
        }
    }
    
    /**
     * Safely extracts a string field from a message. Returns "N/A" if the field is missing.
     */
    private String getFieldSafe(Message message, int field) {
        try {
            return message.getString(field);
        } catch (FieldNotFound e) {
            return "N/A";
        }
    }
    
    /**
     * Returns a human-readable description of the ExecType (150) field.
     */
    private String describeExecType(char execType) {
        return switch (execType) {
            case ExecType.NEW -> "NEW";
            case ExecType.PARTIAL_FILL -> "PARTIAL_FILL";
            case ExecType.FILL -> "FILL";
            case ExecType.CANCELED -> "CANCELED";
            case ExecType.REPLACED -> "REPLACED";
            case ExecType.PENDING_CANCEL -> "PENDING_CANCEL";
            case ExecType.REJECTED -> "REJECTED";
            case ExecType.PENDING_NEW -> "PENDING_NEW";
            case ExecType.PENDING_REPLACE -> "PENDING_REPLACE";
            case ExecType.TRADE -> "TRADE";
            case ExecType.ORDER_STATUS -> "ORDER_STATUS";
            default -> "UNKNOWN(" + execType + ")";
        };
    }
    
    /**
     * Returns a human-readable description of the OrdStatus (39) field.
     */
    private String describeOrdStatus(char ordStatus) {
        return switch (ordStatus) {
            case OrdStatus.NEW -> "NEW";
            case OrdStatus.PARTIALLY_FILLED -> "PARTIALLY_FILLED";
            case OrdStatus.FILLED -> "FILLED";
            case OrdStatus.CANCELED -> "CANCELED";
            case OrdStatus.REPLACED -> "REPLACED";
            case OrdStatus.PENDING_CANCEL -> "PENDING_CANCEL";
            case OrdStatus.REJECTED -> "REJECTED";
            case OrdStatus.PENDING_NEW -> "PENDING_NEW";
            case OrdStatus.PENDING_REPLACE -> "PENDING_REPLACE";
            default -> "UNKNOWN(" + ordStatus + ")";
        };
    }

    // ── Helper methods ──────────────────────────────────────────────────

    /**
     * Initiates a normal Logout for the specified session.
     *
     * @param sessionId session identifier
     * @param reason    logout reason (optional)
     */
    public void initiateLogout(SessionID sessionId, String reason) {
        Session session = Session.lookupSession(sessionId);
        if (session != null) {
            log("INITIATING LOGOUT", sessionId,
                    "Sending logout" + (reason == null || reason.isEmpty() ? "" : " | Reason: " + reason));
            session.logout(reason);
        } else {
            log("LOGOUT FAILED", sessionId, "Session not found");
        }
    }

    /**
     * Checks whether the specified session is active (logged on).
     *
     * @param sessionId session identifier
     * @return {@code true} if the session is logged on
     */
    public boolean isLoggedOn(SessionID sessionId) {
        Session session = Session.lookupSession(sessionId);
        return session != null && session.isLoggedOn();
    }

    /**
     * Extracts the text field (58=Text) from the message, if present.
     */
    private String extractText(Message message) {
        try {
            return message.getString(Text.FIELD);
        } catch (FieldNotFound e) {
            return "";
        }
    }

    /**
     * Formatted console output with a timestamp.
     */
    private void log(String event, SessionID sessionId, String details) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        System.out.printf("[%s] [%-18s] [%s:%s] %s%n",
                timestamp,
                event,
                sessionId.getSenderCompID(),
                sessionId.getTargetCompID(),
                details);
    }
}
