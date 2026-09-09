package com.fluxpay.repository;

import com.fluxpay.beans.PaymentEvent;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Duplicate-safe {@link PaymentEventStore} over Oracle {@code payment_events}.
 *
 * <p>The insert goes through {@link NamedParameterJdbcTemplate} so a unique-key violation on a
 * replayed {@code eventId} only yields {@code false} instead of leaving a Hibernate persistence
 * context unusable. Reads delegate to {@link PaymentEventRepository}.
 */
@Repository
public class JdbcPaymentEventStore implements PaymentEventStore {

  private static final String INSERT_SQL =
      "INSERT INTO payment_events"
          + " (payment_id, event_type, event_payload, kafka_topic, correlation_id, occurred_at)"
          + " VALUES"
          + " (:paymentId, :eventType, :eventPayload, :kafkaTopic, :correlationId, :occurredAt)";

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

  @Override
  public List<PaymentEvent> timeline(String paymentId) {
    return repository.findByPaymentIdOrderByOccurredAtAscEventIdAsc(paymentId);
  }

  @Override
  public boolean contains(String paymentId, String eventType) {
    return repository.existsByPaymentIdAndEventType(paymentId, eventType);
  }
}
