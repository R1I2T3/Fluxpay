# Member 4 — bare-metal KRaft Kafka (M4 slice)

Scope: seven M4 topics on bare-metal KRaft at `${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}`.
Constraint: no Docker Kafka commands — `scripts/start-infra.py` and `scripts/test-all.py` use
host Kafka CLIs (`KAFKA_HOME/bin` or `PATH`) only. `docker compose up -d` still brings up Oracle
on Linux; the lead-owned split making Oracle startup independent of Kafka is pending and untouched
by this task.

## 1. Install (Linux / Windows)

- Java 17 (`java -version`), matching `backend/pom.xml`.
- Apache Kafka 3.7+ binary tarball/zip (KRaft, no ZooKeeper); set `KAFKA_HOME` to the unpacked dir
  so `$KAFKA_HOME/bin/kafka-topics.sh` (Linux) or `%KAFKA_HOME%\bin\kafka-topics.bat` (Windows) runs.

## 2. Format once, then start KRaft

Linux:

```bash
KAFKA_HOME=/opt/kafka
"$KAFKA_HOME/bin/kafka-storage.sh" random --config "$KAFKA_HOME/config/kraft/server.properties" --format
"$KAFKA_HOME/bin/kafka-server-start.sh" "$KAFKA_HOME/config/kraft/server.properties"
```

Windows:

```bat
%KAFKA_HOME%\bin\kafka-storage.bat random --config %KAFKA_HOME%\config\kraft\server.properties --format
%KAFKA_HOME%\bin\kafka-server-start.bat %KAFKA_HOME%\config\kraft\server.properties
```

## 3. Bootstrap via `start-infra.py`

```bash
python scripts/start-infra.py            # Oracle via compose + bare-metal topic bootstrap
python scripts/start-infra.py --skip-oracle   # Kafka topics only
```

What the Kafka-owned block does: reads `KAFKA_BOOTSTRAP_SERVERS` (default `localhost:9092`),
waits for the port, probes `kafka-topics.sh --bootstrap-server <bootstrap> --list`, then creates
the seven timeline topics plus `payout.recovery.dlt` idempotently (`--if-not-exists`, 3 partitions,
replication factor 1):

`payment.initiated`, `payment.route.selected`, `payment.screening.completed`, `payout.submitted`,
`payout.failed`, `payout.completed`, `payment.refunded`, `payout.recovery.dlt` (poison quarantine).

Verify:

```bash
kafka-topics.sh --bootstrap-server localhost:9092 --list
```

## 4. CLI produce / consume

```bash
kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payout.submitted
kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic payout.submitted --from-beginning
```

Envelopes are canonical JSON (`PaymentEventEnvelope`); see `EventEnvelopeCodec`.

## 5. Troubleshooting

- Port 9092 busy: another broker is running — stop it or point `KAFKA_BOOTSTRAP_SERVERS` elsewhere.
- Cluster-id mismatch after re-format: stop the broker, wipe the KRaft log dir, re-run one format.
- Delete a topic: `kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic <name>`.
- Reset data dir: stop the broker, delete `log.dirs`, re-format (step 2), restart.
- `kafka-topics.sh not found`: set `KAFKA_HOME` or add Kafka `bin` to `PATH`.
