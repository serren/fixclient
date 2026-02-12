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
import quickfix.SocketInitiator;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Обёртка над {@link SocketInitiator} для управления FIX Initiator-сессией.
 * <p>
 * Загружает конфигурацию из .cfg файла, создаёт все необходимые компоненты
 * QuickFIX/J (store, log, message factory) и предоставляет методы
 * для запуска, остановки и управления сессией.
 *
 * <h3>Пример использования:</h3>
 * <pre>{@code
 *   FixInitiator initiator = new FixInitiator("sample-initiator-session.cfg");
 *   initiator.start();
 *   // ... работа с сессией ...
 *   initiator.stop();
 * }</pre>
 */
public class FixInitiator {

    private final SocketInitiator initiator;
    private final FixApplication application;
    private final SessionSettings settings;

    /**
     * Создаёт FIX Initiator из конфигурационного файла.
     *
     * @param configFilePath путь к .cfg файлу (например, {@code "sample-initiator-session.cfg"})
     * @throws ConfigError           если конфигурация невалидна
     * @throws FileNotFoundException если файл не найден
     */
    public FixInitiator(String configFilePath) throws ConfigError, FileNotFoundException {
        this.settings = loadSettings(configFilePath);
        this.application = new FixApplication();

        MessageStoreFactory storeFactory = new FileStoreFactory(settings);
        LogFactory logFactory = new FileLogFactory(settings);
        MessageFactory messageFactory = new DefaultMessageFactory();

        this.initiator = new SocketInitiator(
                application,
                storeFactory,
                settings,
                logFactory,
                messageFactory
        );

        printSessionInfo();
    }

    /**
     * Запускает Initiator — начинает подключение к Acceptor-серверу.
     * <p>
     * После вызова QuickFIX/J автоматически:
     * <ol>
     *   <li>Устанавливает TCP-соединение</li>
     *   <li>Отправляет Logon (35=A)</li>
     *   <li>Ожидает подтверждение Logon от сервера</li>
     *   <li>Начинает обмен Heartbeat-сообщениями</li>
     * </ol>
     *
     * @throws RuntimeError если не удалось запустить
     * @throws ConfigError  если конфигурация невалидна
     */
    public void start() throws RuntimeError, ConfigError {
        System.out.println("\n[FIX Initiator] Starting...");
        initiator.start();
        System.out.println("[FIX Initiator] Started. Connecting to server...");
    }

    /**
     * Останавливает Initiator — отправляет Logout и закрывает соединение.
     * <p>
     * QuickFIX/J автоматически:
     * <ol>
     *   <li>Отправляет Logout (35=5)</li>
     *   <li>Ожидает подтверждение Logout от сервера</li>
     *   <li>Закрывает TCP-соединение</li>
     * </ol>
     */
    public void stop() {
        System.out.println("\n[FIX Initiator] Stopping...");
        initiator.stop();
        System.out.println("[FIX Initiator] Stopped.");
    }

    /**
     * Инициирует Logout для конкретной сессии с указанием причины.
     *
     * @param reason причина логаута (будет отправлена в поле Text (58))
     */
    public void logout(String reason) {
        Iterator<SessionID> it = initiator.getSessions().iterator();
        if (it.hasNext()) {
            SessionID sessionId = it.next();
            application.initiateLogout(sessionId, reason);
        } else {
            System.out.println("[FIX Initiator] No active sessions to logout.");
        }
    }

    /**
     * Проверяет, залогинена ли хотя бы одна сессия.
     *
     * @return {@code true} если есть активная залогиненная сессия
     */
    public boolean isLoggedOn() {
        for (SessionID sessionId : initiator.getSessions()) {
            if (application.isLoggedOn(sessionId)) {
                return true;
            }
        }
        return false;
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

    // ── Внутренние методы ───────────────────────────────────────────────

    /**
     * Загружает {@link SessionSettings} из файла.
     * Сначала ищет файл в файловой системе, затем в classpath.
     */
    private SessionSettings loadSettings(String configFilePath) throws ConfigError, FileNotFoundException {
        InputStream inputStream;
        try {
            inputStream = new FileInputStream(configFilePath);
            System.out.println("[FIX Initiator] Loaded config from file: " + configFilePath);
        } catch (FileNotFoundException e) {
            inputStream = getClass().getClassLoader().getResourceAsStream(configFilePath);
            if (inputStream == null) {
                throw new FileNotFoundException("Config file not found: " + configFilePath
                        + " (checked filesystem and classpath)");
            }
            System.out.println("[FIX Initiator] Loaded config from classpath: " + configFilePath);
        }
        return new SessionSettings(inputStream);
    }

    /**
     * Выводит информацию о сконфигурированных сессиях.
     */
    private void printSessionInfo() {
        System.out.println("\n╔══════════════════════════════════════════════╗");
        System.out.println("║       FIX 4.4 Initiator — Session Info       ║");
        System.out.println("╠══════════════════════════════════════════════╣");

        for (SessionID sessionId : initiator.getSessions()) {
            System.out.printf("║  BeginString   : %-27s ║%n", sessionId.getBeginString());
            System.out.printf("║  SenderCompID  : %-27s ║%n", sessionId.getSenderCompID());
            System.out.printf("║  TargetCompID  : %-27s ║%n", sessionId.getTargetCompID());

            try {
                String host = settings.getSessionProperties(sessionId, true)
                        .getProperty("SocketConnectHost", "N/A");
                String port = settings.getSessionProperties(sessionId, true)
                        .getProperty("SocketConnectPort", "N/A");
                System.out.printf("║  Server        : %-27s ║%n", host + ":" + port);
            } catch (ConfigError e) {
                System.out.printf("║  Server        : %-27s ║%n", "N/A");
            }
        }

        System.out.println("╚══════════════════════════════════════════════╝");
    }
}
