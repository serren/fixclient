package com.epam.QuickFIX;

import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.RejectLogon;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.UnsupportedMessageType;
import quickfix.field.MsgType;
import quickfix.field.SenderCompID;
import quickfix.field.TargetCompID;
import quickfix.field.Text;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Реализация интерфейса {@link Application} для FIX 4.4 Initiator.
 * <p>
 * Обрабатывает жизненный цикл FIX-сессии:
 * <ul>
 *   <li>{@link #onCreate}       — сессия создана</li>
 *   <li>{@link #onLogon}        — успешный логон</li>
 *   <li>{@link #onLogout}       — логаут (штатный или по ошибке)</li>
 *   <li>{@link #toAdmin}        — исходящее административное сообщение (Logon, Logout, Heartbeat и т.д.)</li>
 *   <li>{@link #fromAdmin}      — входящее административное сообщение</li>
 *   <li>{@link #toApp}          — исходящее прикладное сообщение</li>
 *   <li>{@link #fromApp}        — входящее прикладное сообщение</li>
 * </ul>
 */
public class FixApplication implements Application {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // ── Lifecycle callbacks ─────────────────────────────────────────────

    /**
     * Вызывается при создании новой FIX-сессии.
     * На этом этапе соединение ещё не установлено.
     */
    @Override
    public void onCreate(SessionID sessionId) {
        log("SESSION CREATED", sessionId, "Session object initialized, waiting for connection...");
    }

    /**
     * Вызывается после успешного обмена Logon-сообщениями (35=A).
     * Сессия полностью установлена, можно отправлять прикладные сообщения.
     */
    @Override
    public void onLogon(SessionID sessionId) {
        log("LOGON SUCCESS", sessionId,
                "FIX session established. Sender=" + sessionId.getSenderCompID()
                        + ", Target=" + sessionId.getTargetCompID()
                        + ", BeginString=" + sessionId.getBeginString());
    
        // Если мы работаем в режиме Acceptor, то это значит, что к нам подключился новый клиент
        if (isAcceptorMode(sessionId)) {
            System.out.println("\n[FIX Acceptor] NEW CLIENT CONNECTED: " + sessionId.getTargetCompID() 
                    + " → " + sessionId.getSenderCompID() + " [" + sessionId.getBeginString() + "]");
        }
    }
    
    /**
     * Определяет, работает ли сессия в режиме Acceptor.
     * В режиме Acceptor SenderCompID обычно соответствует серверной стороне (SERVER).
     * 
     * @param sessionId идентификатор сессии
     * @return {@code true} если сессия работает в режиме Acceptor
     */
    private boolean isAcceptorMode(SessionID sessionId) {
        return "SERVER".equals(sessionId.getSenderCompID());
    }

    /**
     * Вызывается при завершении сессии (логаут).
     * Причины: штатный Logout, разрыв соединения, ошибка sequence numbers и т.д.
     */
    @Override
    public void onLogout(SessionID sessionId) {
        log("LOGOUT", sessionId,
                "FIX session disconnected. Sender=" + sessionId.getSenderCompID()
                        + ", Target=" + sessionId.getTargetCompID());
    
        // Если мы работаем в режиме Acceptor, то это значит, что клиент отключился
        if (isAcceptorMode(sessionId)) {
            System.out.println("\n[FIX Acceptor] CLIENT DISCONNECTED: " + sessionId.getTargetCompID() 
                    + " → " + sessionId.getSenderCompID() + " [" + sessionId.getBeginString() + "]");
        }
    }

    // ── Administrative messages ─────────────────────────────────────────

    /**
     * Вызывается перед отправкой административного сообщения.
     * <p>
     * Здесь можно модифицировать исходящие Logon/Logout/Heartbeat сообщения.
     * Например, добавить Username/Password в Logon (35=A).
     *
     * @param message   исходящее сообщение
     * @param sessionId идентификатор сессии
     * @throws DoNotSend если нужно отменить отправку сообщения
     */
    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);

            switch (msgType) {
                case MsgType.LOGON -> {
                    log("SENDING LOGON", sessionId,
                            "Logon request (35=A) → " + sessionId.getTargetCompID());
                    // Здесь можно добавить аутентификацию:
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
     * Вызывается при получении административного сообщения от контрагента.
     * <p>
     * Здесь можно обработать входящие Logon/Logout, проверить credentials и т.д.
     * Выброс {@link RejectLogon} отклонит входящий Logon.
     *
     * @param message   входящее сообщение
     * @param sessionId идентификатор сессии
     * @throws FieldNotFound      если обязательное поле отсутствует
     * @throws IncorrectDataFormat если формат данных некорректен
     * @throws IncorrectTagValue   если значение тега невалидно
     * @throws RejectLogon         если нужно отклонить логон контрагента
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
                    // Здесь можно проверить credentials контрагента:
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
     * Вызывается перед отправкой прикладного сообщения (NewOrderSingle, Cancel и т.д.).
     *
     * @param message   исходящее сообщение
     * @param sessionId идентификатор сессии
     * @throws DoNotSend если нужно отменить отправку
     */
    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        log("APP OUT", sessionId, "Sending: " + message.toRawString().replace('\001', '|'));
    }

    /**
     * Вызывается при получении прикладного сообщения от контрагента
     * (ExecutionReport, MarketData и т.д.).
     *
     * @param message   входящее сообщение
     * @param sessionId идентификатор сессии
     * @throws FieldNotFound           если обязательное поле отсутствует
     * @throws IncorrectDataFormat     если формат данных некорректен
     * @throws IncorrectTagValue       если значение тега невалидно
     * @throws UnsupportedMessageType  если тип сообщения не поддерживается
     */
    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        log("APP IN", sessionId, "Received: " + message.toRawString().replace('\001', '|'));
    }

    // ── Вспомогательные методы ──────────────────────────────────────────

    /**
     * Инициирует штатный Logout для указанной сессии.
     *
     * @param sessionId идентификатор сессии
     * @param reason    причина логаута (опционально)
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
     * Проверяет, активна ли указанная сессия (залогинена).
     *
     * @param sessionId идентификатор сессии
     * @return {@code true} если сессия залогинена
     */
    public boolean isLoggedOn(SessionID sessionId) {
        Session session = Session.lookupSession(sessionId);
        return session != null && session.isLoggedOn();
    }

    /**
     * Извлекает текстовое поле (58=Text) из сообщения, если оно присутствует.
     */
    private String extractText(Message message) {
        try {
            return message.getString(Text.FIELD);
        } catch (FieldNotFound e) {
            return "";
        }
    }

    /**
     * Форматированный вывод в консоль с временной меткой.
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
