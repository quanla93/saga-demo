package com.quanla.sagademo.common.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.quanla.sagademo.common.event.payload.ChargePaymentCommand;
import com.quanla.sagademo.common.event.payload.InventoryReleasedEvent;
import com.quanla.sagademo.common.event.payload.InventoryReservationFailedEvent;
import com.quanla.sagademo.common.event.payload.InventoryReservedEvent;
import com.quanla.sagademo.common.event.payload.OrderItemDto;
import com.quanla.sagademo.common.event.payload.PaymentCompletedEvent;
import com.quanla.sagademo.common.event.payload.PaymentFailedEvent;
import com.quanla.sagademo.common.event.payload.PaymentRefundedEvent;
import com.quanla.sagademo.common.event.payload.RefundPaymentCommand;
import com.quanla.sagademo.common.event.payload.ReleaseInventoryCommand;
import com.quanla.sagademo.common.event.payload.ReserveInventoryCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EventContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID customerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final UUID reservationId = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private final UUID paymentId = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @Test
    void envelopeFactoryUsesCurrentSchemaVersion() {
        EventEnvelope envelope = EventEnvelope.of(orderId, EventTypes.RESERVE_INVENTORY, "{}");

        assertThat(envelope.schemaVersion()).isEqualTo(EventEnvelope.CURRENT_SCHEMA_VERSION);
        assertThat(envelope.messageId()).isNotNull();
        assertThat(envelope.occurredAt()).isNotNull();
    }

    @Test
    void commandAndEventPayloadsMatchVersionOneSchemas() throws Exception {
        assertMatchesSchema("reserve-inventory-command.v1.schema.json", new ReserveInventoryCommand(
                orderId,
                List.of(new OrderItemDto(productId, 2, new BigDecimal("19.99")))
        ));
        assertMatchesSchema("release-inventory-command.v1.schema.json", new ReleaseInventoryCommand(orderId, reservationId));
        assertMatchesSchema("charge-payment-command.v1.schema.json", new ChargePaymentCommand(orderId, customerId, new BigDecimal("39.98")));
        assertMatchesSchema("refund-payment-command.v1.schema.json", new RefundPaymentCommand(orderId, paymentId));
        assertMatchesSchema("inventory-reserved-event.v1.schema.json", new InventoryReservedEvent(orderId, reservationId));
        assertMatchesSchema("inventory-reservation-failed-event.v1.schema.json", new InventoryReservationFailedEvent(orderId, "insufficient stock"));
        assertMatchesSchema("inventory-released-event.v1.schema.json", new InventoryReleasedEvent(orderId, reservationId));
        assertMatchesSchema("payment-completed-event.v1.schema.json", new PaymentCompletedEvent(orderId, paymentId, new BigDecimal("39.98")));
        assertMatchesSchema("payment-failed-event.v1.schema.json", new PaymentFailedEvent(orderId, "card declined"));
        assertMatchesSchema("payment-refunded-event.v1.schema.json", new PaymentRefundedEvent(orderId, paymentId));
    }

    @Test
    void additiveFieldsRemainCompatibleWithVersionOneSchemas() throws Exception {
        JsonNode payload = OBJECT_MAPPER.readTree("""
                {
                  "orderId": "00000000-0000-0000-0000-000000000001",
                  "paymentId": "00000000-0000-0000-0000-000000000005",
                  "amount": 39.98,
                  "processorReference": "future-field"
                }
                """);

        Set<ValidationMessage> errors = loadSchema("payment-completed-event.v1.schema.json").validate(payload);

        assertThat(errors).isEmpty();
    }

    @Test
    void removingRequiredFieldsBreaksVersionOneCompatibility() throws Exception {
        JsonNode payload = OBJECT_MAPPER.readTree("""
                {
                  "orderId": "00000000-0000-0000-0000-000000000001",
                  "amount": 39.98
                }
                """);

        Set<ValidationMessage> errors = loadSchema("payment-completed-event.v1.schema.json").validate(payload);

        assertThat(errors).isNotEmpty();
    }

    private void assertMatchesSchema(String schemaFileName, Object payload) {
        JsonNode payloadJson = OBJECT_MAPPER.valueToTree(payload);
        Set<ValidationMessage> errors = loadSchema(schemaFileName).validate(payloadJson);

        assertThat(errors)
                .describedAs("%s should match %s", payloadJson, schemaFileName)
                .isEmpty();
    }

    private JsonSchema loadSchema(String schemaFileName) {
        return SCHEMA_FACTORY.getSchema(getClass().getResourceAsStream("/schemas/events/" + schemaFileName));
    }
}
