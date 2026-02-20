package com.example.quickfix.application;

import com.example.quickfix.handler.DefaultMessageHandler;
import com.example.quickfix.handler.MessageHandler;
import com.example.quickfix.service.IIncomingMessageProcessor;
import com.example.quickfix.service.IncomingMessageProcessor;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.LogFactory;
import quickfix.Message;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.UnsupportedMessageType;
import quickfix.field.MsgType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import static com.example.quickfix.MessageUtil.extractText;

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
public abstract class FixApplication implements Application {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    protected final SessionSettings settings;
    protected final MessageStoreFactory storeFactory;
    protected final LogFactory logFactory;
    protected final MessageFactory messageFactory;

    /** Asynchronous processor for incoming application messages. Set externally. */
    protected IIncomingMessageProcessor incomingMessageProcessor;

    protected final Map<String, MessageHandler> handlers = new HashMap<>();

    protected FixApplication(SessionSettings settings) {
        this.settings = settings;
        this.storeFactory = new FileStoreFactory(settings);
        this.logFactory = new FileLogFactory(settings);
        this.messageFactory = new DefaultMessageFactory();
        initHandlers();
    }

    protected void initHandlers() {
        handlers.put("DEFAULT", new DefaultMessageHandler());
    }

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
    }

    /**
     * Called before sending an administrative message.
     * <p>
     * Outgoing Logon/Logout/Heartbeat messages can be modified here.
     * For example, Username/Password can be added to Logon (35=A).
     *
     * @param message   outgoing message
     * @param sessionId session identifier
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
    public void fromAdmin(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
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
     * <p>
     * If an {@link IncomingMessageProcessor} is configured, the message is dispatched
     * to a separate thread pool for asynchronous processing, freeing the QuickFIX/J
     * session thread to continue receiving messages. Otherwise, the message is
     * processed synchronously in the session thread.
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
        if (incomingMessageProcessor != null) {
            incomingMessageProcessor.submit(message, sessionId, this::processAppMessage);
        } else {
            processAppMessage(message, sessionId);
        }
    }
    
    /**
     * Performs the actual processing of an incoming application message.
     * <p>
     * Dispatches the message to the appropriate handler based on the message type
     * and the current operating mode (Acceptor or Initiator).
     * This method may be called from the session thread (synchronous mode) or
     * from a worker thread in the {@link IncomingMessageProcessor} (asynchronous mode).
     *
     * @param message   incoming message
     * @param sessionId session identifier
     * @throws FieldNotFound           if a required field is missing
     * @throws IncorrectDataFormat     if the data format is incorrect
     * @throws IncorrectTagValue       if a tag value is invalid
     * @throws UnsupportedMessageType  if the message type is not supported
     */
    protected void processAppMessage(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        String msgType = message.getHeader().getString(MsgType.FIELD);
        handlers.getOrDefault(msgType, handlers.get("DEFAULT")).handle(message, sessionId);
    }


    /**
     * Safely extracts a string field from a message. Returns "N/A" if the field is missing.
     */
    protected String getFieldSafe(Message message, int field) {
        try {
            return message.getString(field);
        } catch (FieldNotFound e) {
            return "N/A";
        }
    }

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
     * Formatted console output with a timestamp.
     */
    protected void log(String event, SessionID sessionId, String details) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        System.out.printf("[%s] [%-18s] [%s:%s] %s%n",
                timestamp,
                event,
                sessionId.getSenderCompID(),
                sessionId.getTargetCompID(),
                details);
    }
}
