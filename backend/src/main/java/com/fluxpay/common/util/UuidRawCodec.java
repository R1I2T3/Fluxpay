package com.fluxpay.common.util;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Converts between {@link UUID} and the 16-byte layout used for Oracle {@code RAW(16)} columns.
 *
 * <p>This matches Hibernate's own default UUID-to-binary mapping (most significant bits first,
 * then least significant bits, both big-endian) -- the same layout you get "for free" whenever an
 * entity declares {@code @GeneratedValue(strategy = GenerationType.UUID)} on a UUID id. Native
 * JDBC code in this package uses this codec so that a UUID returned from a plain SQL query is
 * byte-for-byte identical to the UUID Hibernate would report for the same row.
 */
public final class UuidRawCodec {

  private UuidRawCodec() {}

  public static byte[] toBytes(UUID id) {
    ByteBuffer buffer = ByteBuffer.wrap(new byte[16]);
    buffer.putLong(id.getMostSignificantBits());
    buffer.putLong(id.getLeastSignificantBits());
    return buffer.array();
  }

  public static UUID fromBytes(byte[] bytes) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    long msb = buffer.getLong();
    long lsb = buffer.getLong();
    return new UUID(msb, lsb);
  }
}
