package com.example.quickfix.service;

import quickfix.field.OrdType;
import quickfix.field.Side;

/**
 * Holds the essential details of a sent order, needed for constructing
 * cancel and replace requests.
 */
public class OrderDetails {
    final String symbol;
    final char side;
    final double quantity;
    final double price;
    final char ordType;

    public OrderDetails(String symbol, char side, double quantity, double price, char ordType) {
        this.symbol = symbol;
        this.side = side;
        this.quantity = quantity;
        this.price = price;
        this.ordType = ordType;
    }

    public String getSymbol() {
        return symbol;
    }

    public char getSide() {
        return side;
    }

    public double getQuantity() {
        return quantity;
    }

    public double getPrice() {
        return price;
    }

    public char getOrdType() {
        return ordType;
    }

    @Override
    public String toString() {
        String base = String.format("Symbol=%s, Side=%s, Qty=%.0f",
                symbol, side == Side.BUY ? "BUY" : "SELL", quantity);
        if (ordType == OrdType.LIMIT) {
            base += String.format(", Price=%.2f", price);
        }
        return base + ", Type=" + (ordType == OrdType.LIMIT ? "LIMIT" : "MARKET");
    }
}