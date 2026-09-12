package com.fluxpay.repository;

import com.fluxpay.beans.PaymentEvent;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Duplicate-safe {@link PaymentEventStore} over Oracle {@code payment_events}.
 *
 * <p>UUID strategy: {@code id} is RAW(16) bound as 16 bytes from the {@code UUID} event id; {@code
 * payment_id} is the business key (P-001/P-002) bound as VARCHAR2. The insert goes through {@link
 * NamedParameterJdbcTemplate} so a unique-key violation on a replayed {@code eventId} only yields
 * {@code false} instead of leaving a Hibernate persistence context unusable. Reads delegate to
 * {@link PaymentEventRepository}.
 */
@Repository
public class JdbcPaymentEventStore implements PaymentEventStore {

  private static final String INSERT_SQL =
      "INSERT INTO payment_events"
          + " (id, payment_id, event_type, event_payload, kafka_topic, correlation_id, occurred_at)"
          + " VALUES"
          + " (:eventId, :paymentId, :eventType, :eventPayload, :kafkaTopic, :correlationId, :occurredAt)";

  private final NamedParameterJdbcTemplate jdbc;
  private final PaymentEventRepository repository;

  public JdbcPaymentEventStore(NamedParameterJdbcTemplate jdbc, PaymentEventRepository repository) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
  }

  @Override
  public boolean appendIfAbsent(PaymentEvent event) {
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("eventId", toRaw16(event.eventId()))
            .addValue("paymentId", event.paymentId())
            .addValue("eventType", event.eventType())
            .addValue("eventPayload", event.payload())
            .addValue("kafkaTopic", event.kafkaTopic())
            .addValue("correlationId", event.correlationId())
            .addValue("occurredAt", Timestamp.from(event.occurredAt()));
    try {
      jdbc.update(INSERT_SQL, params);
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  /**
   * Converts the UUID event id to the 16-byte RAW(16) {@code id} form. Payment business keys stay
   * as VARCHAR2 and are bound directly.
   */
  private static byte[] toRaw16(UUID eventId) {
    ByteBuffer buf = ByteBuffer.allocate(16);
    buf.putLong(eventId.getMostSignificantBits());
    buf.putLong(eventId.getLeastSignificantBits());
    return buf.array();
  }

  @Override
  public List<PaymentEvent> timeline(String paymentId) {
    return repository.findByPaymentIdOrderByOccurredAtAscEventIdAsc(paymentId);
  }

  @Override
  public boolean contains(String paymentId, String eventType) {
    return repository.existsByPaymentIdAndEventType(paymentId, eventType);
  }
}
