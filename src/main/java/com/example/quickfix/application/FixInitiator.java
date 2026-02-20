package com.example.quickfix.application;

import com.example.quickfix.handler.ExecutionMessageHandler;
import com.example.quickfix.handler.OrderCancelRejectHanlder;
import com.example.quickfix.latency.ILatencyTracker;
import com.example.quickfix.latency.LatencyTracker;
import com.example.quickfix.service.IOrderGeneratorService;
import com.example.quickfix.service.IOrderService;
import com.example.quickfix.service.IncomingMessageProcessor;
import com.example.quickfix.service.OrderGeneratorService;
import com.example.quickfix.service.OrderService;
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
import quickfix.field.MsgType;
import quickfix.field.Side;

import java.util.Iterator;
import java.util.Scanner;

/**
 * Wrapper around {@link SocketInitiator} for managing a FIX Initiator session.
 * <p>
 * Creates all necessary QuickFIX/J components (store, log, message factory)
 * and provides methods for starting, stopping, and managing the session.
 *
 * <h3>Usage example:</h3>
 * <pre>{@code
 *   SessionSettings settings = Starter.loadSettings("sample-initiator-session.cfg");
 *   FixInitiator initiator = new FixInitiator(settings);
 *   initiator.start();
 *   // ... working with the session ...
 *   initiator.stop();
 * }</pre>
 */
public class FixInitiator extends FixApplication {

    private final SocketInitiator initiator;
    private final IOrderGeneratorService orderGeneratorService;

    /** Latency tracker for measuring order round-trip times. Set externally. */
    private final ILatencyTracker latencyTracker;
    /** Order service for managing active orders. Set externally. */
    private final IOrderService orderService;

    /**
     * Creates a FIX Initiator based on the provided session settings.
     *
     * @param settings session settings {@link SessionSettings}
     * @throws ConfigError if the configuration is invalid
     */
    public FixInitiator(SessionSettings settings) throws ConfigError {
        super(settings);
        this.latencyTracker = new LatencyTracker();
        this.orderService = new OrderService(latencyTracker);
        this.orderGeneratorService = new OrderGeneratorService(orderService);
        this.incomingMessageProcessor = new IncomingMessageProcessor();

        MessageStoreFactory storeFactory = new FileStoreFactory(settings);
        LogFactory logFactory = new FileLogFactory(settings);
        MessageFactory messageFactory = new DefaultMessageFactory();

        this.initiator = new SocketInitiator(
                this,
                storeFactory,
                settings,
                logFactory,
                messageFactory
        );

        printSessionInfo();
    }

    @Override
    protected void initHandlers() {
        super.initHandlers();
        handlers.put(MsgType.EXECUTION_REPORT, new ExecutionMessageHandler(latencyTracker, orderService));
        handlers.put(MsgType.ORDER_CANCEL_REJECT, new OrderCancelRejectHanlder());
    }

    /**
     * Starts the Initiator — begins connecting to the Acceptor server.
     * <p>
     * After invocation, QuickFIX/J automatically:
     * <ol>
     *   <li>Establishes a TCP connection</li>
     *   <li>Sends Logon (35=A)</li>
     *   <li>Waits for Logon confirmation from the server</li>
     *   <li>Starts Heartbeat message exchange</li>
     * </ol>
     *
     * @throws RuntimeError if the initiator failed to start
     * @throws ConfigError  if the configuration is invalid
     */
    public void start() throws RuntimeError, ConfigError {
        System.out.println("\n[FIX Initiator] Starting...");
        initiator.start();
        System.out.println("[FIX Initiator] Started. Connecting to server...");
        runInitiatorCommandLoop();
    }

    /**
     * Stops the Initiator — sends Logout and closes the connection.
     * <p>
     * QuickFIX/J automatically:
     * <ol>
     *   <li>Sends Logout (35=5)</li>
     *   <li>Waits for Logout confirmation from the server</li>
     *   <li>Closes the TCP connection</li>
     * </ol>
     */
    public void stop() {
        System.out.println("\n[FIX Initiator] Stopping...");
        if (orderGeneratorService.isRunning()) {
            orderGeneratorService.stop();
        }
        incomingMessageProcessor.shutdown();
        initiator.stop();
        System.out.println("[FIX Initiator] Stopped.");
    }

    /**
     * Initiates Logout for a specific session with the given reason.
     *
     * @param reason logout reason (will be sent in the Text (58) field)
     */
    public void logout(String reason) {
        Iterator<SessionID> it = initiator.getSessions().iterator();
        if (it.hasNext()) {
            SessionID sessionId = it.next();
            initiateLogout(sessionId, reason);
        } else {
            System.out.println("[FIX Initiator] No active sessions to logout.");
        }
    }

    /**
     * Checks whether at least one session is logged on.
     *
     * @return {@code true} if there is an active logged-on session
     */
    public boolean isLoggedOn() {
        for (SessionID sessionId : initiator.getSessions()) {
            if (isLoggedOn(sessionId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first available {@link SessionID}, or {@code null} if none.
     */
    public SessionID getSessionId() {
        Iterator<SessionID> it = initiator.getSessions().iterator();
        return it.hasNext() ? it.next() : null;
    }

    /**
     * Interactive command loop for Initiator mode.
     */
    private void runInitiatorCommandLoop() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║     FIX Initiator — Available commands:          ║");
        System.out.println("║  order    — send a NewOrderSingle                ║");
        System.out.println("║  cancel   — send an OrderCancelRequest           ║");
        System.out.println("║  replace  — send an OrderCancelReplaceRequest    ║");
        System.out.println("║  orders   — list active (cancellable) orders     ║");
        System.out.println("║  batch    — send a batch of orders               ║");
        System.out.println("║  generate — start automated order generation     ║");
        System.out.println("║  genstop  — stop order generation                ║");
        System.out.println("║  genconf  — show generator configuration         ║");
        System.out.println("║  stats    — show round-trip latency stats        ║");
        System.out.println("║  reset    — reset latency statistics             ║");
        System.out.println("║  status   — check session status                 ║");
        System.out.println("║  logout   — send Logout and disconnect           ║");
        System.out.println("║  quit     — stop initiator and exit              ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nfix-initiator> ");
                String command = scanner.nextLine().trim().toLowerCase();

                switch (command) {
                    case "order" -> handleOrderCommand(scanner);
                    case "cancel" -> handleCancelCommand(scanner);
                    case "replace" -> handleReplaceCommand(scanner);
                    case "orders" -> handleListOrdersCommand();
                    case "batch" -> handleBatchCommand(scanner);
                    case "generate" -> handleGenerateCommand(scanner);
                    case "genstop" -> handleGenStopCommand();
                    case "genconf" -> handleGenConfCommand(scanner);
                    case "stats" -> latencyTracker.printStatistics();
                    case "reset" -> latencyTracker.reset();
                    case "status" -> {
                        boolean loggedOn = isLoggedOn();
                        System.out.println("Session status: " + (loggedOn ? "LOGGED ON ✓" : "DISCONNECTED ✗"));
                    }
                    case "logout" -> {
                        System.out.print("Logout reason (optional, press ENTER to skip): ");
                        String reason = scanner.nextLine().trim();
                        logout(reason.isEmpty() ? "User initiated logout" : reason);
                        System.out.println("Logout request sent.");
                    }
                    case "quit", "exit", "q" -> {
                        stop();
                        System.out.println("Goodbye!");
                        return;
                    }
                    case "help" -> System.out.println("Commands: order, cancel, replace, orders, batch, generate, genstop, genconf, stats, reset, status, logout, quit, help");
                    case "" -> { /* ignore empty input */ }
                    default ->
                            System.out.println("Unknown command: '" + command + "'. Type 'help' for available commands.");
                }
            }
        }
    }

    /**
     * Handles the interactive "order" command.
     * Prompts the user for order parameters and sends a NewOrderSingle.
     *
     * @param scanner      console input scanner
     */
    private void handleOrderCommand(Scanner scanner) {
        if (!isLoggedOn()) {
            System.out.println("[ERROR] Cannot send order — session is not logged on.");
            return;
        }

        SessionID sessionId = getSessionId();
        if (sessionId == null) {
            System.out.println("[ERROR] No session available.");
            return;
        }

        try {
            System.out.print("  Symbol (e.g., AAPL): ");
            String symbol = scanner.nextLine().trim();
            if (symbol.isEmpty()) {
                System.out.println("[ERROR] Symbol cannot be empty.");
                return;
            }

            System.out.print("  Side (BUY/SELL) [BUY]: ");
            String sideStr = scanner.nextLine().trim().toUpperCase();
            char side = sideStr.isEmpty() || sideStr.equals("BUY") ? Side.BUY : Side.SELL;

            System.out.print("  Quantity [100]: ");
            String qtyStr = scanner.nextLine().trim();
            double quantity = qtyStr.isEmpty() ? 100 : Double.parseDouble(qtyStr);

            System.out.print("  Order type (LIMIT/MARKET) [LIMIT]: ");
            String typeStr = scanner.nextLine().trim().toUpperCase();
            boolean isMarket = typeStr.equals("MARKET");

            if (isMarket) {
                orderService.sendMarketOrder(sessionId, symbol, side, quantity);
            } else {
                System.out.print("  Price [100.00]: ");
                String priceStr = scanner.nextLine().trim();
                double price = priceStr.isEmpty() ? 100.00 : Double.parseDouble(priceStr);
                orderService.sendLimitOrder(sessionId, symbol, side, quantity, price);
            }
        } catch (NumberFormatException e) {
            System.out.println("[ERROR] Invalid number format: " + e.getMessage());
        }
    }

    /**
     * Handles the interactive "batch" command.
     * Prompts the user for parameters and sends a batch of orders for latency benchmarking.
     *
     * @param scanner      console input scanner
     */
    private void handleBatchCommand(Scanner scanner) {
        if (!isLoggedOn()) {
            System.out.println("[ERROR] Cannot send orders — session is not logged on.");
            return;
        }

        SessionID sessionId = getSessionId();
        if (sessionId == null) {
            System.out.println("[ERROR] No session available.");
            return;
        }

        try {
            System.out.print("  Symbol [AAPL]: ");
            String symbol = scanner.nextLine().trim();
            if (symbol.isEmpty()) symbol = "AAPL";

            System.out.print("  Side (BUY/SELL) [BUY]: ");
            String sideStr = scanner.nextLine().trim().toUpperCase();
            char side = sideStr.isEmpty() || sideStr.equals("BUY") ? Side.BUY : Side.SELL;

            System.out.print("  Quantity per order [100]: ");
            String qtyStr = scanner.nextLine().trim();
            double quantity = qtyStr.isEmpty() ? 100 : Double.parseDouble(qtyStr);

            System.out.print("  Price [100.00]: ");
            String priceStr = scanner.nextLine().trim();
            double price = priceStr.isEmpty() ? 100.00 : Double.parseDouble(priceStr);

            System.out.print("  Number of orders [10]: ");
            String countStr = scanner.nextLine().trim();
            int count = countStr.isEmpty() ? 10 : Integer.parseInt(countStr);

            System.out.printf("[Batch] Sending %d orders: %s %s %.0f @ %.2f ...%n",
                    count, side == Side.BUY ? "BUY" : "SELL", symbol, quantity, price);

            long startTime = System.nanoTime();
            int sent = orderService.sendBatch(sessionId, symbol, side, quantity, price, count);
            long elapsed = System.nanoTime() - startTime;

            System.out.printf("[Batch] Sent %d/%d orders in %.3f ms (%.0f orders/sec)%n",
                    sent, count,
                    elapsed / 1_000_000.0,
                    sent > 0 ? sent / (elapsed / 1_000_000_000.0) : 0);
            System.out.println("[Batch] Use 'stats' command to view round-trip latency after ExecutionReports arrive.");

        } catch (NumberFormatException e) {
            System.out.println("[ERROR] Invalid number format: " + e.getMessage());
        }
    }

    /**
     * Handles the interactive "generate" command.
     * Loads configuration from properties file and starts automated order generation.
     *
     * @param scanner      console input scanner
     */
    private void handleGenerateCommand(Scanner scanner) {
        if (!isLoggedOn()) {
            System.out.println("[ERROR] Cannot start generator — session is not logged on.");
            return;
        }

        SessionID sessionId = getSessionId();
        if (sessionId == null) {
            System.out.println("[ERROR] No session available.");
            return;
        }

        if (orderGeneratorService.isRunning()) {
            System.out.println("[ERROR] Order generator is already running. Use 'genstop' to stop it first.");
            return;
        }

        System.out.print("  Properties file [order-generator.properties]: ");
        String propsPath = scanner.nextLine().trim();
        if (propsPath.isEmpty()) {
            propsPath = OrderGeneratorService.DEFAULT_PROPERTIES_FILE;
        }

        try {
            orderGeneratorService.loadConfig(propsPath);
            orderGeneratorService.printConfig();

            System.out.print("  Start generation? (y/n) [y]: ");
            String confirm = scanner.nextLine().trim().toLowerCase();
            if (confirm.isEmpty() || confirm.equals("y") || confirm.equals("yes")) {
                orderGeneratorService.start(sessionId);
            } else {
                System.out.println("[INFO] Generation cancelled.");
            }
        } catch (Exception e) {
            System.out.println("[ERROR] Failed to start generator: " + e.getMessage());
        }
    }

    /**
     * Handles the interactive "genstop" command.
     * Stops the currently running order generator.
     */
    private void handleGenStopCommand() {
        if (!orderGeneratorService.isRunning()) {
            System.out.println("[INFO] Order generator is not running.");
            return;
        }

        orderGeneratorService.stop();
        System.out.printf("[INFO] Generator stopped. Sent: %d, Failed: %d%n",
                orderGeneratorService.getTotalSent(), orderGeneratorService.getTotalFailed());
    }

    /**
     * Handles the interactive "genconf" command.
     * Loads and displays the generator configuration from a properties file.
     *
     * @param scanner      console input scanner
     */
    private void handleGenConfCommand(Scanner scanner) {
        System.out.print("  Properties file [order-generator.properties]: ");
        String propsPath = scanner.nextLine().trim();
        if (propsPath.isEmpty()) {
            propsPath = "order-generator.properties";
        }

        try {
            orderGeneratorService.loadConfig(propsPath);
            orderGeneratorService.printConfig();
        } catch (Exception e) {
            System.out.println("[ERROR] Failed to load config: " + e.getMessage());
        }
    }

    // ── Order command handlers ──────────────────────────────────────────

    /**
     * Handles the interactive "replace" command.
     * Prompts the user for the ClOrdID of the order to replace and the new parameters.
     *
     * @param scanner      console input scanner
     */
    private void handleReplaceCommand(Scanner scanner) {
        if (!isLoggedOn()) {
            System.out.println("[ERROR] Cannot send replace — session is not logged on.");
            return;
        }

        SessionID sessionId = getSessionId();
        if (sessionId == null) {
            System.out.println("[ERROR] No session available.");
            return;
        }

        var activeOrders = orderService.getActiveOrders();
        if (activeOrders.isEmpty()) {
            System.out.println("[INFO] No active orders to replace.");
            return;
        }

        System.out.println("\n  Active orders:");
        activeOrders.forEach((id, details) ->
                System.out.printf("    %s — %s%n", id, details));

        System.out.print("  ClOrdID to replace: ");
        String origClOrdId = scanner.nextLine().trim();
        if (origClOrdId.isEmpty()) {
            System.out.println("[ERROR] ClOrdID cannot be empty.");
            return;
        }

        var details = activeOrders.get(origClOrdId);
        if (details == null) {
            System.out.println("[ERROR] Order not found: " + origClOrdId);
            return;
        }

        try {
            System.out.printf("  New Quantity [%.0f] (press ENTER to keep): ", details.getQuantity());
            String qtyStr = scanner.nextLine().trim();
            double newQuantity = qtyStr.isEmpty() ? 0 : Double.parseDouble(qtyStr);

            double newPrice = 0;
            if (details.getOrdType() == quickfix.field.OrdType.LIMIT) {
                System.out.printf("  New Price [%.2f] (press ENTER to keep): ", details.getPrice());
                String priceStr = scanner.nextLine().trim();
                newPrice = priceStr.isEmpty() ? 0 : Double.parseDouble(priceStr);
            }

            if (newQuantity == 0 && newPrice == 0) {
                System.out.println("[INFO] No changes specified — replace not sent.");
                return;
            }

            orderService.sendCancelReplaceOrder(sessionId, origClOrdId, newQuantity, newPrice);
        } catch (NumberFormatException e) {
            System.out.println("[ERROR] Invalid number format: " + e.getMessage());
        }
    }

    /**
     * Handles the interactive "cancel" command.
     * Prompts the user for the ClOrdID of the order to cancel and sends an OrderCancelRequest.
     *
     * @param scanner      console input scanner
     */
    private void handleCancelCommand(Scanner scanner) {
        if (!isLoggedOn()) {
            System.out.println("[ERROR] Cannot send cancel — session is not logged on.");
            return;
        }

        SessionID sessionId = getSessionId();
        if (sessionId == null) {
            System.out.println("[ERROR] No session available.");
            return;
        }

        var activeOrders = orderService.getActiveOrders();
        if (activeOrders.isEmpty()) {
            System.out.println("[INFO] No active orders to cancel.");
            return;
        }

        System.out.println("\n  Active orders:");
        activeOrders.forEach((id, details) ->
                System.out.printf("    %s — %s%n", id, details));

        System.out.print("  ClOrdID to cancel: ");
        String origClOrdId = scanner.nextLine().trim();
        if (origClOrdId.isEmpty()) {
            System.out.println("[ERROR] ClOrdID cannot be empty.");
            return;
        }

        orderService.sendCancelOrder(sessionId, origClOrdId);
    }

    /**
     * Handles the interactive "orders" command.
     * Displays all active (cancellable) orders.
     *
     */
    private void handleListOrdersCommand() {
        var activeOrders = orderService.getActiveOrders();
        if (activeOrders.isEmpty()) {
            System.out.println("\n[INFO] No active orders.");
            return;
        }

        System.out.println("\n  Active orders (" + activeOrders.size() + "):");
        activeOrders.forEach((id, details) ->
                System.out.printf("    %s — %s%n", id, details));
    }

    /**
     * Prints information about configured sessions.
     */
    private void printSessionInfo() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║       FIX Initiator — Session Info           ║");
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
