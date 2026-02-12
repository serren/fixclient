package com.epam.QuickFIX;

import quickfix.SessionID;
import quickfix.SessionSettings;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;

/**
 * Интерактивный построитель настроек FIX-сессии.
 * <p>
 * Считывает из консоли параметры, необходимые для конфигурации
 * QuickFIX/J Initiator- или Acceptor-сессии по протоколу FIX 4.4,
 * и формирует объект {@link SessionSettings}.
 * <p>
 * Параметры разделены на две секции:
 * <ul>
 *   <li><b>[DEFAULT]</b> — глобальные настройки (тип подключения, хранилище, логирование)</li>
 *   <li><b>[SESSION]</b> — параметры конкретной сессии (SenderCompID, TargetCompID, адрес/порт и т.д.)</li>
 * </ul>
 */
public class SessionSettingsBuilder {

    // ── Ключи параметров ────────────────────────────────────────────────

    // DEFAULT section
    public static final String CONNECTION_TYPE = "ConnectionType";
    public static final String START_TIME = "StartTime";
    public static final String END_TIME = "EndTime";
    public static final String HEART_BT_INT = "HeartBtInt";
    public static final String RECONNECT_INTERVAL = "ReconnectInterval";
    public static final String FILE_STORE_PATH = "FileStorePath";
    public static final String FILE_LOG_PATH = "FileLogPath";
    public static final String USE_DATA_DICTIONARY = "UseDataDictionary";

    // SESSION section — общие
    public static final String BEGIN_STRING = "BeginString";
    public static final String SENDER_COMP_ID = "SenderCompID";
    public static final String TARGET_COMP_ID = "TargetCompID";
    public static final String RESET_ON_LOGON = "ResetOnLogon";
    public static final String RESET_ON_LOGOUT = "ResetOnLogout";
    public static final String RESET_ON_DISCONNECT = "ResetOnDisconnect";

    // SESSION section — Initiator only
    public static final String SOCKET_CONNECT_HOST = "SocketConnectHost";
    public static final String SOCKET_CONNECT_PORT = "SocketConnectPort";

    // SESSION section — Acceptor only
    public static final String SOCKET_ACCEPT_PORT = "SocketAcceptPort";

    // ── Описание параметра ──────────────────────────────────────────────

    /**
     * Описание одного параметра настройки: подсказка для пользователя
     * и значение по умолчанию.
     */
    private record SettingParam(String prompt, String defaultValue) {
    }

    // ── Карты параметров (LinkedHashMap сохраняет порядок вставки) ──────

    private static final Map<String, SettingParam> DEFAULT_PARAMS_INITIATOR = new LinkedHashMap<>();
    private static final Map<String, SettingParam> DEFAULT_PARAMS_ACCEPTOR = new LinkedHashMap<>();
    private static final Map<String, SettingParam> SESSION_PARAMS_INITIATOR = new LinkedHashMap<>();
    private static final Map<String, SettingParam> SESSION_PARAMS_ACCEPTOR = new LinkedHashMap<>();

    static {
        // --- DEFAULT (общие для обоих режимов) ---
        Map<String, SettingParam> commonDefaults = new LinkedHashMap<>();
        commonDefaults.put(START_TIME,
                new SettingParam("Session start time (HH:MM:SS)", "00:00:00"));
        commonDefaults.put(END_TIME,
                new SettingParam("Session end time (HH:MM:SS)", "00:00:00"));
        commonDefaults.put(HEART_BT_INT,
                new SettingParam("Heartbeat interval (seconds)", "30"));
        commonDefaults.put(FILE_STORE_PATH,
                new SettingParam("File store path", "store"));
        commonDefaults.put(FILE_LOG_PATH,
                new SettingParam("File log path", "log"));
        commonDefaults.put(USE_DATA_DICTIONARY,
                new SettingParam("Use data dictionary (Y/N)", "Y"));

        // DEFAULT — Initiator
        DEFAULT_PARAMS_INITIATOR.put(CONNECTION_TYPE,
                new SettingParam("Connection type", "initiator"));
        DEFAULT_PARAMS_INITIATOR.putAll(commonDefaults);
        DEFAULT_PARAMS_INITIATOR.put(RECONNECT_INTERVAL,
                new SettingParam("Reconnect interval (seconds)", "5"));

        // DEFAULT — Acceptor
        DEFAULT_PARAMS_ACCEPTOR.put(CONNECTION_TYPE,
                new SettingParam("Connection type", "acceptor"));
        DEFAULT_PARAMS_ACCEPTOR.putAll(commonDefaults);

        // --- SESSION (общие) ---
        Map<String, SettingParam> commonSession = new LinkedHashMap<>();
        commonSession.put(BEGIN_STRING,
                new SettingParam("FIX protocol version (BeginString)", "FIX.4.4"));
        commonSession.put(RESET_ON_LOGON,
                new SettingParam("Reset sequence numbers on logon (Y/N)", "Y"));
        commonSession.put(RESET_ON_LOGOUT,
                new SettingParam("Reset sequence numbers on logout (Y/N)", "Y"));
        commonSession.put(RESET_ON_DISCONNECT,
                new SettingParam("Reset sequence numbers on disconnect (Y/N)", "Y"));

        // SESSION — Initiator
        SESSION_PARAMS_INITIATOR.put(BEGIN_STRING, commonSession.get(BEGIN_STRING));
        SESSION_PARAMS_INITIATOR.put(SENDER_COMP_ID,
                new SettingParam("Sender CompID (your identifier)", "CLIENT"));
        SESSION_PARAMS_INITIATOR.put(TARGET_COMP_ID,
                new SettingParam("Target CompID (counterparty identifier)", "SERVER"));
        SESSION_PARAMS_INITIATOR.put(SOCKET_CONNECT_HOST,
                new SettingParam("Server host", "localhost"));
        SESSION_PARAMS_INITIATOR.put(SOCKET_CONNECT_PORT,
                new SettingParam("Server port", "9876"));
        SESSION_PARAMS_INITIATOR.put(RESET_ON_LOGON, commonSession.get(RESET_ON_LOGON));
        SESSION_PARAMS_INITIATOR.put(RESET_ON_LOGOUT, commonSession.get(RESET_ON_LOGOUT));
        SESSION_PARAMS_INITIATOR.put(RESET_ON_DISCONNECT, commonSession.get(RESET_ON_DISCONNECT));

        // SESSION — Acceptor
        SESSION_PARAMS_ACCEPTOR.put(BEGIN_STRING, commonSession.get(BEGIN_STRING));
        SESSION_PARAMS_ACCEPTOR.put(SENDER_COMP_ID,
                new SettingParam("Sender CompID (server identifier)", "SERVER"));
        SESSION_PARAMS_ACCEPTOR.put(TARGET_COMP_ID,
                new SettingParam("Target CompID (client identifier)", "CLIENT"));
        SESSION_PARAMS_ACCEPTOR.put(SOCKET_ACCEPT_PORT,
                new SettingParam("Listen port", "9876"));
        SESSION_PARAMS_ACCEPTOR.put(RESET_ON_LOGON, commonSession.get(RESET_ON_LOGON));
        SESSION_PARAMS_ACCEPTOR.put(RESET_ON_LOGOUT, commonSession.get(RESET_ON_LOGOUT));
        SESSION_PARAMS_ACCEPTOR.put(RESET_ON_DISCONNECT, commonSession.get(RESET_ON_DISCONNECT));
    }

    // ── Публичный API ───────────────────────────────────────────────────

    /**
     * Запускает интерактивный диалог в консоли и возвращает
     * сконфигурированный {@link SessionSettings}.
     * <p>
     * Сначала спрашивает тип подключения (initiator/acceptor),
     * затем запрашивает соответствующие параметры.
     *
     * @param scanner сканер, привязанный к {@code System.in}
     * @return готовый объект настроек для QuickFIX/J
     */
    public SessionSettings buildFromConsole(Scanner scanner) {
        System.out.println("==============================================");
        System.out.println("    FIX 4.4 — Session Configuration Builder   ");
        System.out.println("==============================================");
        System.out.println("Press ENTER to accept [default] value.\n");

        // Определяем тип подключения
        System.out.print("  Connection type (initiator/acceptor) [initiator]: ");
        String typeInput = scanner.nextLine().trim().toLowerCase();
        boolean isAcceptor = typeInput.equals("acceptor");

        String connectionType = isAcceptor ? "acceptor" : "initiator";
        System.out.println("  → Mode: " + connectionType.toUpperCase() + "\n");

        Map<String, SettingParam> defaultParams = isAcceptor ? DEFAULT_PARAMS_ACCEPTOR : DEFAULT_PARAMS_INITIATOR;
        Map<String, SettingParam> sessionParams = isAcceptor ? SESSION_PARAMS_ACCEPTOR : SESSION_PARAMS_INITIATOR;

        // Собираем значения (ConnectionType уже определён)
        Map<String, String> defaultValues = readSection(scanner, "DEFAULT", defaultParams, connectionType);
        Map<String, String> sessionValues = readSection(scanner, "SESSION", sessionParams, null);

        // Строим конфигурационную строку в формате QuickFIX/J .cfg
        String cfg = buildConfigString(defaultValues, sessionValues);

        System.out.println("\n--- Generated configuration ---");
        System.out.println(cfg);
        System.out.println("-------------------------------\n");

        return parseSettings(cfg);
    }

    // ── Внутренние методы ───────────────────────────────────────────────

    /**
     * Считывает значения одной секции, показывая пользователю подсказки
     * и значения по умолчанию.
     *
     * @param scanner        сканер ввода
     * @param sectionName    имя секции для отображения
     * @param params         карта параметров секции
     * @param presetConnType если не null, ConnectionType будет установлен автоматически
     */
    private Map<String, String> readSection(Scanner scanner,
                                            String sectionName,
                                            Map<String, SettingParam> params,
                                            String presetConnType) {
        System.out.println("── [" + sectionName + "] ──");
        Map<String, String> values = new LinkedHashMap<>();

        for (var entry : params.entrySet()) {
            String key = entry.getKey();
            SettingParam param = entry.getValue();

            // ConnectionType уже определён — не спрашиваем повторно
            if (CONNECTION_TYPE.equals(key) && presetConnType != null) {
                values.put(key, presetConnType);
                continue;
            }

            System.out.printf("  %s [%s]: ", param.prompt(), param.defaultValue());
            String input = scanner.nextLine().trim();

            String value = input.isEmpty() ? param.defaultValue() : input;
            values.put(key, value);
        }

        System.out.println();
        return values;
    }

    /**
     * Формирует строку конфигурации в формате QuickFIX/J .cfg.
     */
    private String buildConfigString(Map<String, String> defaultValues,
                                     Map<String, String> sessionValues) {
        StringBuilder sb = new StringBuilder();

        sb.append("[DEFAULT]\n");
        defaultValues.forEach((key, value) ->
                sb.append(key).append("=").append(value).append("\n"));

        sb.append("\n[SESSION]\n");
        sessionValues.forEach((key, value) ->
                sb.append(key).append("=").append(value).append("\n"));

        return sb.toString();
    }

    /**
     * Сохраняет {@link SessionSettings} в .cfg файл в формате QuickFIX/J.
     * <p>
     * Автоматически определяет тип подключения и сохраняет соответствующие параметры.
     *
     * @param settings       объект настроек
     * @param outputFilePath путь к выходному файлу
     * @throws IOException если не удалось записать файл
     */
    public void saveToFile(SessionSettings settings, String outputFilePath) throws IOException {
        Path path = Path.of(outputFilePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        String connectionType = settings.getDefaultProperties()
                .getProperty(CONNECTION_TYPE, "initiator").trim().toLowerCase();
        boolean isAcceptor = "acceptor".equals(connectionType);

        Map<String, SettingParam> defaultParamKeys = isAcceptor ? DEFAULT_PARAMS_ACCEPTOR : DEFAULT_PARAMS_INITIATOR;
        Map<String, SettingParam> sessionParamKeys = isAcceptor ? SESSION_PARAMS_ACCEPTOR : SESSION_PARAMS_INITIATOR;

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFilePath))) {
            // [DEFAULT] section
            writer.println("[DEFAULT]");
            Properties defaults = settings.getDefaultProperties();
            for (String key : defaultParamKeys.keySet()) {
                String value = defaults.getProperty(key);
                if (value != null) {
                    writer.println(key + "=" + value);
                }
            }
            writer.println();

            // [SESSION] sections
            java.util.Iterator<SessionID> it = settings.sectionIterator();
            while (it.hasNext()) {
                SessionID sessionId = it.next();
                writer.println("[SESSION]");
                try {
                    Properties sessionProps = settings.getSessionProperties(sessionId, false);
                    for (String key : sessionParamKeys.keySet()) {
                        String value = sessionProps.getProperty(key);
                        if (value != null) {
                            writer.println(key + "=" + value);
                        }
                    }
                } catch (quickfix.ConfigError e) {
                    throw new IOException("Failed to read session properties: " + e.getMessage(), e);
                }
                writer.println();
            }
        }
    }

    /**
     * Парсит строку конфигурации в объект {@link SessionSettings}.
     *
     * @throws IllegalStateException если конфигурация невалидна
     */
    private SessionSettings parseSettings(String cfg) {
        try {
            return new SessionSettings(
                    new java.io.ByteArrayInputStream(cfg.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (quickfix.ConfigError e) {
            throw new IllegalStateException("Invalid FIX session configuration: " + e.getMessage(), e);
        }
    }
}
