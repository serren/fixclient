package com.example.quickfix.service;

import quickfix.FieldNotFound;
import quickfix.IncorrectDataFormat;
import quickfix.IncorrectTagValue;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous processor for incoming FIX application messages.
 * <p>
 * Decouples message handling from the QuickFIX/J session thread by placing
 * incoming messages into a bounded queue and processing them in a dedicated
 * thread pool. This prevents slow message handling logic (logging, state updates,
 * response generation) from blocking the session thread and delaying reception
 * of subsequent messages.
 * <p>
 * The processor uses a {@link ThreadPoolExecutor} backed by a {@link LinkedBlockingQueue}.
 * If the queue is full, the <b>caller-runs</b> policy is applied — the submitting
 * (session) thread will process the message itself, providing natural back-pressure.
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 *   IncomingMessageProcessor processor = new IncomingMessageProcessor(4, 10_000);
 *   processor.submit(message, sessionId, handler);
 *   // ...
 *   processor.shutdown();
 * }</pre>
 */
public class IncomingMessageProcessor {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Default number of worker threads */
    private static final int DEFAULT_THREAD_COUNT = 4;

    /** Default maximum queue capacity */
    private static final int DEFAULT_QUEUE_CAPACITY = 10_000;

    private final ExecutorService executor;

    /**
     * Creates an IncomingMessageProcessor with the specified thread pool size and queue capacity.
     *
     * @param threadCount   number of worker threads in the pool
     * @param queueCapacity maximum number of messages that can be queued before back-pressure kicks in
     */
    public IncomingMessageProcessor(int threadCount, int queueCapacity) {
        LinkedBlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(queueCapacity);

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "FIX-MsgProcessor-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };

        // CallerRunsPolicy provides natural back-pressure: if the queue is full,
        // the session thread will handle the message directly instead of dropping it.
        this.executor = new ThreadPoolExecutor(
                threadCount,
                threadCount,
                60L, TimeUnit.SECONDS,
                workQueue,
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        log("Initialized with " + threadCount + " threads, queue capacity=" + queueCapacity);
    }

    /**
     * Creates an IncomingMessageProcessor with default settings
     * ({@value #DEFAULT_THREAD_COUNT} threads, queue capacity {@value #DEFAULT_QUEUE_CAPACITY}).
     */
    public IncomingMessageProcessor() {
        this(DEFAULT_THREAD_COUNT, DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * Submits an incoming FIX message for asynchronous processing.
     * <p>
     * The message and sessionId are captured and passed to the provided handler
     * in a worker thread. If the internal queue is full, the calling (session) thread
     * will execute the handler directly (CallerRunsPolicy).
     *
     * @param message   the incoming FIX application message
     * @param sessionId the session the message was received on
     * @param handler   the callback that performs the actual message processing
     */
    public void submit(Message message, SessionID sessionId, MessageHandler handler) {
        executor.submit(() -> {
            try {
                handler.handle(message, sessionId);
            } catch (Exception e) {
                log("ERROR processing message: " + e.getMessage()
                        + " | Raw: " + message.toRawString().replace('\001', '|'));
            }
        });
    }

    /**
     * Gracefully shuts down the processor, waiting for queued messages to be processed.
     * <p>
     * Waits up to 5 seconds for pending tasks to complete. If tasks are still running
     * after the timeout, a forced shutdown is performed.
     */
    public void shutdown() {
        log("Shutting down...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log("Forcing shutdown — " + executor);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log("Shut down complete.");
    }

    /**
     * Functional interface for message handling callbacks.
     * <p>
     * Implementations perform the actual business logic for processing
     * incoming FIX messages (parsing, logging, state updates, response generation, etc.).
     */
    @FunctionalInterface
    public interface MessageHandler {

        /**
         * Handles an incoming FIX application message.
         *
         * @param message   the incoming message
         * @param sessionId the session the message was received on
         * @throws FieldNotFound          if a required field is missing
         * @throws IncorrectDataFormat    if the data format is incorrect
         * @throws IncorrectTagValue      if a tag value is invalid
         * @throws UnsupportedMessageType if the message type is not supported
         */
        void handle(Message message, SessionID sessionId)
                throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType;
    }

    private void log(String msg) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        System.out.printf("[%s] [MsgProcessor] %s%n", timestamp, msg);
    }
}
