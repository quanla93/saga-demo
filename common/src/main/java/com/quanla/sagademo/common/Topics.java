package com.quanla.sagademo.common;

public final class Topics {

    private Topics() {}

    public static final String INVENTORY_COMMANDS = "inventory.commands";
    public static final String INVENTORY_EVENTS = "inventory.events";

    public static final String PAYMENT_COMMANDS = "payment.commands";
    public static final String PAYMENT_EVENTS = "payment.events";

    public static final String ORDER_EVENTS = "order.events";

    public static String dlt(String topic) {
        return topic + ".DLT";
    }
}