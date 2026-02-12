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

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Обёртка над {@link SocketAcceptor} для управления FIX Acceptor-сессией.
 * <p>
 * Загружает конфигурацию из .cfg файла, создаёт все необходимые компоненты
 * QuickFIX/J (store, log, message factory) и предоставляет методы
 * для запуска, остановки и управления сессией.
 * <p>
 * Acceptor — серверная сторона FIX-соединения. Он слушает входящие
 * TCP-подключения от Initiator-клиентов и принимает Logon-запросы.
 *
 * <h3>Пример использования:</h3>
 * <pre>{@code
 *   FixAcceptor acceptor = new FixAcceptor("acceptor-session.cfg");
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
     * Создаёт FIX Acceptor из конфигурационного файла.
     *
     * @param configFilePath путь к .cfg файлу (например, {@code "acceptor-session.cfg"})
     * @throws ConfigError           если конфигурация невалидна
     * @throws FileNotFoundException если файл не найден
     */
    public FixAcceptor(String configFilePath) throws ConfigError, FileNotFoundException {
        this.settings = loadSettings(configFilePath);
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
     * Проверяет, залогинена ли хотя бы одна сессия.
     *
     * @return {@code true} если есть активная залогиненная сессия
     */
    public boolean isLoggedOn() {
        for (SessionID sessionId : acceptor.getSessions()) {
            if (application.isLoggedOn(sessionId)) {
                return true;
            }
        }
        return false;
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
     * Загружает {@link SessionSettings} из файла.
     * Сначала ищет файл в файловой системе, затем в classpath.
     */
    private SessionSettings loadSettings(String configFilePath) throws ConfigError, FileNotFoundException {
        InputStream inputStream;
        try {
            inputStream = new FileInputStream(configFilePath);
            System.out.println("[FIX Acceptor] Loaded config from file: " + configFilePath);
        } catch (FileNotFoundException e) {
            inputStream = getClass().getClassLoader().getResourceAsStream(configFilePath);
            if (inputStream == null) {
                throw new FileNotFoundException("Config file not found: " + configFilePath
                        + " (checked filesystem and classpath)");
            }
            System.out.println("[FIX Acceptor] Loaded config from classpath: " + configFilePath);
        }
        return new SessionSettings(inputStream);
    }

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