"""Date presentation fixtures for completed API-seeded transactions only."""

import hashlib
import re
import uuid
from datetime import datetime, timedelta, timezone


def seed_key_order(key, user_id):
    """Accept exact identities from the two shipped seed batches, never arbitrary activity."""
    match = re.fullmatch(
        rf"seed-local:v([12]):{re.escape(str(user_id))}:"
        r"(add-money|withdraw|wallet-transfer|exchange|send):(\d+)(:draft)?", key
    )
    if not match or (match[2] == "send") != bool(match[4]) or int(match[3]) < 1:
        return None
    kinds = ("add-money", "withdraw", "wallet-transfer", "exchange", "send")
    return int(match[1]), int(match[3]), kinds.index(match[2])


def history_schedule(keys, user_id, now):
    """Interleave transaction types across 23 hours, with repeatable varied gaps."""
    ordered = sorted(set(keys), key=lambda key: seed_key_order(key, user_id))
    end = now.astimezone(timezone.utc).replace(second=0, microsecond=0) - timedelta(minutes=2)
    start = end - timedelta(hours=23)
    weights = [30 + hashlib.sha256(key.encode()).digest()[0] % 31 for key in ordered[1:]]
    total = sum(weights) or 1
    elapsed = 0
    result = {}
    for index, key in enumerate(ordered):
        if index:
            elapsed += weights[index - 1]
        result[key] = start + (end - start) * (elapsed / total)
    return result


def utc_stamp(value):
    return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value.astimezone(timezone.utc)


def remap_stamp(value, original_start, original_end, target):
    """Keep recorded lifecycle ordering; fit interrupted runs into a 45-second fixture."""
    if value is None:
        return None
    span = max(1.0, (original_end - original_start).total_seconds())
    scale = min(1.0, 45.0 / span)
    return (target + (utc_stamp(value) - original_start) * scale).replace(tzinfo=None)


def _records(cursor, table, identity, where, bindings, columns):
    # All identifiers come from the static call sites below, values are bound.
    # Normalize Oracle region-based time zones before the thin driver reads them.
    # The caller uses a UTC session, including for columns stored without a zone.
    timestamps = ', '.join(
        f"CAST({column} AT TIME ZONE '+00:00' AS TIMESTAMP)" for column in columns
    )
    cursor.execute(f"SELECT {identity}, {timestamps} FROM {table} WHERE {where}", **bindings)
    return [(table, identity, row[0], dict(zip(columns, row[1:]))) for row in cursor.fetchall()]


def spread_seed_transaction_times(cursor, user_id, now=None):
    """Called in one local Oracle transaction. Caller commits, or rolls everything back."""
    user_id = str(uuid.UUID(str(user_id)))
    owner = uuid.UUID(user_id).bytes
    binding = {"owner": owner, "prefix": f"seed-local:%:{user_id}:%"}
    cursor.execute(
        """SELECT id, client_key, journal_reference FROM wallet_operations
           WHERE user_id = :owner AND status = 'COMPLETED' AND client_key LIKE :prefix
             AND operation_type IN ('BANK_TOPUP', 'WITHDRAW', 'TRANSFER', 'CONVERT')""", **binding
    )
    wallets = [row for row in cursor.fetchall() if seed_key_order(row[1], user_id) is not None
               and not row[1].endswith(":draft")]
    # The internal rail stores transfers under Java's nameUUIDFromBytes-derived
    # payout key, rather than the original request key used by other operations.
    transfer_keys = {}
    for version, count in ((1, 8), (2, 6)):
        for index in range(1, count + 1):
            key = f"seed-local:v{version}:{user_id}:wallet-transfer:{index}"
            digest = hashlib.md5(f"wallet-p2p:{user_id}:{key}".encode()).digest()
            transfer_keys['payout:' + str(uuid.UUID(bytes=digest, version=3))] = key
    cursor.execute(
        """SELECT id, client_key, journal_reference FROM wallet_operations
           WHERE user_id = :owner AND status = 'COMPLETED'
             AND operation_type = 'TRANSFER' AND client_key LIKE 'payout:%'""", owner=owner
    )
    wallets.extend((row[0], transfer_keys[row[1]], row[2]) for row in cursor.fetchall()
                   if row[1] in transfer_keys)
    cursor.execute(
        """SELECT p.id, o.client_key FROM payments p JOIN payment_operations o ON o.payment_id = p.id
           WHERE p.sender_id = :owner AND o.user_id = :owner AND p.status = 'COMPLETED'
             AND o.status = 'COMPLETED' AND o.operation_type = 'DRAFT' AND o.client_key LIKE :prefix""", **binding
    )
    payments = [row for row in cursor.fetchall() if seed_key_order(row[1], user_id) is not None
                and row[1].endswith(":draft")]
    batches = []
    for operation_id, key, journal in wallets:
        records = _records(cursor, "wallet_operations", "id", "id = :id AND user_id = :owner",
                           {"id": operation_id, "owner": owner}, ("created_at",))
        records += _records(cursor, "ledger_entries", "id", "journal_reference = :journal",
                            {"journal": journal}, ("created_at",))
        records += _records(cursor, "ledger_journals", "journal_reference", "journal_reference = :journal",
                            {"journal": journal}, ("created_at",))
        batches.append((key, records))
    for payment_id, key in payments:
        payment_text = str(uuid.UUID(bytes=bytes(payment_id)))
        # Wait for ingestion before dating timeline rows. Published envelopes and replay
        # snapshots retain their original transport times and immutable request identities.
        cursor.execute(
            """SELECT COUNT(*) FROM outbox_delivery d
               WHERE d.payment_id = :id AND (d.state <> 'SENT' OR NOT EXISTS
                 (SELECT 1 FROM payment_events e WHERE e.id = d.event_id))""", id=payment_id
        )
        if cursor.fetchone()[0]:
            raise RuntimeError(f"{payment_text}: timeline is still syncing. Keep Kafka/backend running and rerun; no timestamps were changed")
        records = _records(cursor, "payments", "id", "id = :id AND sender_id = :owner AND status = 'COMPLETED'",
                           {"id": payment_id, "owner": owner}, ("created_at", "updated_at", "posted_at"))
        records += _records(cursor, "payment_quotes", "id", "payment_id = :id",
                            {"id": payment_id}, ("created_at", "expires_at"))
        records += _records(cursor, "payout_attempts", "id", "payment_id = :id",
                            {"id": payment_text}, ("initiated_at", "completed_at"))
        records += _records(cursor, "payment_events", "id", "payment_id = :id",
                            {"id": payment_text}, ("occurred_at",))
        records += _records(cursor, "payment_operations", "id", "payment_id = :id AND user_id = :owner",
                            {"id": payment_id, "owner": owner}, ("created_at",))
        journal = "payment:" + payment_text
        records += _records(cursor, "ledger_entries", "id", "journal_reference = :journal",
                            {"journal": journal}, ("created_at",))
        records += _records(cursor, "ledger_journals", "journal_reference", "journal_reference = :journal",
                            {"journal": journal}, ("created_at",))
        batches.append((key, records))
    schedule = history_schedule([key for key, _ in batches], user_id, now or datetime.now(timezone.utc))
    for key, records in batches:
        actual = [utc_stamp(value) for _, _, _, stamps in records
                  for column, value in stamps.items() if value is not None and column != "expires_at"]
        if not actual:
            raise RuntimeError("Seed transaction has no recorded timestamps")
        first, last = min(actual), max(actual)
        for table, identity, row_id, stamps in records:
            bindings = {column: remap_stamp(value, first, last, schedule[key]) for column, value in stamps.items()}
            bindings["row_id"] = row_id
            assignments = ", ".join(f"{column} = :{column}" for column in stamps)
            cursor.execute(f"UPDATE {table} SET {assignments} WHERE {identity} = :row_id", **bindings)
    return len(batches)
