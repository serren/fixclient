package com.example.quickfix.handler;

import com.example.quickfix.service.IExecutionReportService;

public abstract class AbstractExecutionMessageHandler extends AbstractMessageHandler {

    protected final IExecutionReportService executionReportService;

    public AbstractExecutionMessageHandler(IExecutionReportService executionReportService) {
        super();
        this.executionReportService = executionReportService;
    }
}
