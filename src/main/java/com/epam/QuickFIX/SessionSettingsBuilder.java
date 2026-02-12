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