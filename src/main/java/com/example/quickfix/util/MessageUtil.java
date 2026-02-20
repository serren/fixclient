package com.example.quickfix.util;

import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecType;
import quickfix.field.OrdStatus;
import quickfix.field.Text;

public class MessageUtil {

    /**
     * Safely extracts a string field from a message. Returns "N/A" if the field is missing.
     */
    public static String getFieldSafe(Message message, int field) {
        try {
            return message.getString(field);
        } catch (FieldNotFound e) {
            return "N/A";
        }
    }

    /**
     * Safely extracts a double field from a message. Returns 0 if the field is missing.
     */
    public static double getDoubleSafe(Message message, int field) {
        try {
            return message.getDouble(field);
        } catch (FieldNotFound e) {
            return 0;
        }
    }

    /**
     * Returns a human-readable description of the CxlRejReason (102) field.
     */
    public static String describeCxlRejReason(int reason) {
        return switch (reason) {
            case CxlRejReason.TOO_LATE_TO_CANCEL -> "TOO_LATE_TO_CANCEL";
            case CxlRejReason.UNKNOWN_ORDER -> "UNKNOWN_ORDER";
            case 2 -> "BROKER_OPTION";
            case 3 -> "ALREADY_PENDING";
            default -> "OTHER(" + reason + ")";
        };
    }

    /**
     * Returns a human-readable description of the CxlRejResponseTo (434) field.
     */
    public static String describeCxlRejResponseTo(char responseTo) {
        return switch (responseTo) {
            case CxlRejResponseTo.ORDER_CANCEL_REQUEST -> "ORDER_CANCEL_REQUEST";
            case CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST -> "ORDER_CANCEL_REPLACE_REQUEST";
            default -> "UNKNOWN(" + responseTo + ")";
        };
    }

    /**
     * Extracts the text field (58=Text) from the message, if present.
     */
    public static String extractText(Message message) {
        try {
            return message.getString(Text.FIELD);
        } catch (FieldNotFound e) {
            return "";
        }
    }


    /**
     * Returns a human-readable description of the ExecType (150) field.
     */
    public static String describeExecType(char execType) {
        return switch (execType) {
            case ExecType.NEW -> "NEW";
            case ExecType.PARTIAL_FILL -> "PARTIAL_FILL";
            case ExecType.FILL -> "FILL";
            case ExecType.CANCELED -> "CANCELED";
            case ExecType.REPLACED -> "REPLACED";
            case ExecType.PENDING_CANCEL -> "PENDING_CANCEL";
            case ExecType.REJECTED -> "REJECTED";
            case ExecType.PENDING_NEW -> "PENDING_NEW";
            case ExecType.PENDING_REPLACE -> "PENDING_REPLACE";
            case ExecType.TRADE -> "TRADE";
            case ExecType.ORDER_STATUS -> "ORDER_STATUS";
            default -> "UNKNOWN(" + execType + ")";
        };
    }


    /**
     * Returns a human-readable description of the OrdStatus (39) field.
     */
    public static String describeOrdStatus(char ordStatus) {
        return switch (ordStatus) {
            case OrdStatus.NEW -> "NEW";
            case OrdStatus.PARTIALLY_FILLED -> "PARTIALLY_FILLED";
            case OrdStatus.FILLED -> "FILLED";
            case OrdStatus.CANCELED -> "CANCELED";
            case OrdStatus.REPLACED -> "REPLACED";
            case OrdStatus.PENDING_CANCEL -> "PENDING_CANCEL";
            case OrdStatus.REJECTED -> "REJECTED";
            case OrdStatus.PENDING_NEW -> "PENDING_NEW";
            case OrdStatus.PENDING_REPLACE -> "PENDING_REPLACE";
            default -> "UNKNOWN(" + ordStatus + ")";
        };
    }
}
