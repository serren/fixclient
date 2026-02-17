package com.example.quickfix.service;

import quickfix.SessionID;

import java.io.IOException;

/**
 * Automated order generator that sends orders at a configurable rate
 * for a specified duration.
 */
public interface IOrderGeneratorService {

    /**
     * Loads generator configuration from the specified properties file.
     * Falls back to classpath if the file is not found on the filesystem.
     *
     * @param propertiesPath path to the properties file
     * @throws IOException if the file cannot be read
     */
    void loadConfig(String propertiesPath) throws IOException;

    /**
     * Loads generator configuration from the default properties file.
     *
     * @throws IOException if the file cannot be read
     */
    void loadConfig() throws IOException;

    /**
     * Starts the order generation process.
     * Orders are sent at the configured rate for the configured duration.
     *
     * @param sessionId the FIX session to send orders on
     * @throws IllegalStateException if the generator is already running
     */
    void start(SessionID sessionId);

    /**
     * Stops the order generation process.
     * If the generator is not running, this method does nothing.
     */
    void stop();

    /**
     * Returns whether the generator is currently running.
     *
     * @return {@code true} if the generator is actively sending orders
     */
    boolean isRunning();

    /**
     * Returns the total number of orders successfully sent during the current (or last) run.
     *
     * @return total sent count
     */
    long getTotalSent();

    /**
     * Returns the total number of orders that failed to send during the current (or last) run.
     *
     * @return total failed count
     */
    long getTotalFailed();

    /**
     * Prints the current configuration to the console.
     */
    void printConfig();
}