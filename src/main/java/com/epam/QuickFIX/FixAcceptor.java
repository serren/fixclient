package com.epam.QuickFIX;

import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.FileStoreFactory;
import quickfix.LogFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.RuntimeError;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

import java.util.Iterator;

/**
 * Обёртка над {@link SocketAcceptor} для управления FIX Acceptor-сессией.
 * <p>
 * Создаёт все необходимые компоненты QuickFIX/J (store, log, message factory)
 * и предоставляет методы для запуска, остановки и управления сессией.
 * <p>
 * Acceptor — серверная сторона FIX-соединения. Он слушает входящие
 * TCP-подключения от Initiator-клиентов и принимает Logon-запросы.
 *
 * <h3>Пример использования:</h3>
 * <pre>{@code
 *   SessionSettings settings = Starter.loadSettings("acceptor-session.cfg");
 *   FixAcceptor acceptor = new FixAcceptor(settings);
 *   acceptor.start();
 *   // ... приём подключений от Initiator-клиентов ...
 *   acceptor.stop();
 * }</pre>
 */
public class FixAcceptor {

    private final SocketAcceptor acceptor;
    private final FixApplication application;
    private final SessionSettings settings;

    /**
     * Создаёт FIX Acceptor на основе переданных настроек сессии.
     *
     * @param settings настройки сессии {@link SessionSettings}
     * @throws ConfigError если конфигурация невалидна
     */
    public FixAcceptor(SessionSettings settings) throws ConfigError {
        this.settings = settings;
        this.application = new FixApplication();

        MessageStoreFactory storeFactory = new FileStoreFactory(settings);
        LogFactory logFactory = new FileLogFactory(settings);
        MessageFactory messageFactory = new DefaultMessageFactory();

        this.acceptor = new SocketAcceptor(
                application,
                storeFactory,
                settings,
                logFactory,
                messageFactory
        );

        printSessionInfo();
    }

    /**
     * Запускает Acceptor — начинает слушать входящие подключения.
     * <p>
     * После вызова QuickFIX/J автоматически:
     * <ol>
     *   <li>Открывает серверный TCP-сокет на указанном порту</li>
     *   <li>Ожидает входящие подключения от Initiator-клиентов</li>
     *   <li>При получении Logon (35=A) — проверяет SenderCompID/TargetCompID</li>
     *   <li>Подтверждает Logon и начинает обмен Heartbeat-сообщениями</li>
     * </ol>
     *
     * @throws RuntimeError если не удалось запустить
     * @throws ConfigError  если конфигурация невалидна
     */
    public void start() throws RuntimeError, ConfigError {
        System.out.println("\n[FIX Acceptor] Starting...");
        acceptor.start();
        System.out.println("[FIX Acceptor] Started. Listening for incoming connections on port " + getAcceptPort() + "...");
    }

    /**
     * Останавливает Acceptor — отправляет Logout всем подключённым клиентам
     * и закрывает серверный сокет.
     * <p>
     * QuickFIX/J автоматически:
     * <ol>
     *   <li>Отправляет Logout (35=5) каждому подключённому Initiator</li>
     *   <li>Ожидает подтверждение Logout</li>
     *   <li>Закрывает все TCP-соединения</li>
     *   <li>Закрывает серверный сокет</li>
     * </ol>
     */
    public void stop() {
        System.out.println("\n[FIX Acceptor] Stopping...");
        acceptor.stop();
        System.out.println("[FIX Acceptor] Stopped.");
    }

    /**
     * Инициирует Logout для конкретной сессии с указанием причины.
     *
     * @param reason причина логаута (будет отправлена в поле Text (58))
     */
    public void logout(String reason) {
        Iterator<SessionID> it = acceptor.getSessions().iterator();
        if (it.hasNext()) {
            SessionID sessionId = it.next();
            application.initiateLogout(sessionId, reason);
        } else {
            System.out.println("[FIX Acceptor] No active sessions to logout.");
        }
    }

    /**
     * Возвращает количество активных (залогиненных) сессий.
     *
     * @return количество залогиненных сессий
     */
    public int getLoggedOnSessionCount() {
        int count = 0;
        for (SessionID sessionId : acceptor.getSessions()) {
            if (application.isLoggedOn(sessionId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Возвращает общее количество сконфигурированных сессий.
     *
     * @return количество сессий
     */
    public int getTotalSessionCount() {
        return acceptor.getSessions().size();
    }

    /**
     * Выводит информацию о текущем количестве подключенных клиентов.
     */
    public void printConnectedClients() {
        int loggedOn = getLoggedOnSessionCount();
        int total = getTotalSessionCount();
        
        System.out.println("\n[FIX Acceptor] Connected clients: " + loggedOn + "/" + total);
        
        if (loggedOn > 0) {
            System.out.println("[FIX Acceptor] Active sessions:");
            for (SessionID sessionId : acceptor.getSessions()) {
                if (application.isLoggedOn(sessionId)) {
                    System.out.println("  - " + sessionId.getTargetCompID() + " → " 
                            + sessionId.getSenderCompID() + " [" + sessionId.getBeginString() + "]");
                }
            }
        }
    }

    /**
     * Возвращает объект {@link FixApplication} для прямого доступа к callback-ам.
     */
    public FixApplication getApplication() {
        return application;
    }

    /**
     * Возвращает настройки сессии.
     */
    public SessionSettings getSettings() {
        return settings;
    }

    /**
     * Возвращает порт, на котором слушает Acceptor.
     * Если несколько сессий с разными портами, возвращает первый.
     *
     * @return порт или "N/A" если не удалось определить
     */
    private String getAcceptPort() {
        Iterator<SessionID> it = acceptor.getSessions().iterator();
        if (it.hasNext()) {
            SessionID sessionId = it.next();
            try {
                return settings.getSessionProperties(sessionId, true)
                        .getProperty("SocketAcceptPort", "N/A");
            } catch (ConfigError e) {
                return "N/A";
            }
        }
        return "N/A";
    }

    // ── Внутренние методы ───────────────────────────────────────────────
    
    /**
     * Выводит информацию о сконфигурированных сессиях.
     */
    private void printSessionInfo() {
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║        FIX 4.4 Acceptor — Session Info       ║");
        System.out.println("╠══════════════════════════════════════════════╣");

        for (SessionID sessionId : acceptor.getSessions()) {
            System.out.printf("║  BeginString   : %-27s ║%n", sessionId.getBeginString());
            System.out.printf("║  SenderCompID  : %-27s ║%n", sessionId.getSenderCompID());
            System.out.printf("║  TargetCompID  : %-27s ║%n", sessionId.getTargetCompID());

            try {
                String port = settings.getSessionProperties(sessionId, true)
                        .getProperty("SocketAcceptPort", "N/A");
                System.out.printf("║  Listen port   : %-27s ║%n", port);
            } catch (ConfigError e) {
                System.out.printf("║  Listen port   : %-27s ║%n", "N/A");
            }
        }

        System.out.println("╚══════════════════════════════════════════════╝");
    }
}