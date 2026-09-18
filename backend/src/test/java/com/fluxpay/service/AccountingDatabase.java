package com.fluxpay.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.*;
import com.fluxpay.repository.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Real balances, ledger rows, row locks and Spring transactions over a controlled H2 store.
 * Repository doubles replace only the Oracle/JPA transport; posting and validation stay real.
 */
final class AccountingDatabase {
  final JdbcTemplate jdbc;
  final DataSourceTransactionManager transactions;
  final WalletRepository wallets = mock(WalletRepository.class);
  final LedgerEntryRepository entries = mock(LedgerEntryRepository.class);
  final LedgerJournalRepository journalHeaders = mock(LedgerJournalRepository.class);
  final LedgerJournalLockRepository journalLocks = mock(LedgerJournalLockRepository.class);
  final LedgerPostingContext context = new LedgerPostingContext();
  final PersistentLedgerWriter writer;
  final LedgerJournalService journals;
  final UUID user = UUID.randomUUID();
  final UUID system = UUID.randomUUID();
  final UUID customer = UUID.fromString("00000000-0000-0000-0000-000000000003");
  final UUID clearing = UUID.fromString("00000000-0000-0000-0000-000000000001");
  final UUID fee = UUID.fromString("00000000-0000-0000-0000-000000000002");
  final List<UUID> locks = Collections.synchronizedList(new ArrayList<>());
  volatile java.util.function.Consumer<Integer> afterJournalLock = ignored -> {};
  volatile Runnable afterWalletLocks = () -> {};
  volatile String failKey;

  AccountingDatabase() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
    jdbc = new JdbcTemplate(source);
    transactions = new DataSourceTransactionManager(source);
    jdbc.execute(
        "create table wallets (id uuid primary key, owner uuid, role varchar(30), currency varchar(3), balance decimal(19,4), held decimal(19,4))");
    jdbc.execute(
        "create table entries (entry_key varchar(255) primary key, wallet uuid, kind varchar(10), amount decimal(19,4), currency varchar(3), journal varchar(64), narration varchar(255), rate decimal(19,8), quote_id varchar(36))");
    jdbc.execute(
        "create table journal_headers (journal varchar(64) primary key, category varchar(24), payload_hash char(64), created_at timestamp)");
    jdbc.execute("create table journal_locks (lock_id int primary key)");
    for (int lockId = 0; lockId < 64; lockId++) {
      jdbc.update("insert into journal_locks values (?)", lockId);
    }
    seed(customer, user, WalletAccountRole.CUSTOMER, "100.0000");
    seed(clearing, system, WalletAccountRole.PAYOUT_CLEARING, "0.0000");
    seed(fee, system, WalletAccountRole.FEE_REVENUE, "0.0000");
    when(wallets.findById(any())).thenAnswer(call -> wallet(call.getArgument(0), false));
    when(wallets.findByIdForUpdate(any()))
        .thenAnswer(
            call -> {
              UUID id = call.getArgument(0);
              locks.add(id);
              return wallet(id, true);
            });
    when(wallets.findAllByIdForUpdate(any()))
        .thenAnswer(
            call -> {
              Collection<UUID> requested = call.getArgument(0);
              List<Wallet> result = new ArrayList<>();
              requested.stream()
                  .distinct()
                  .sorted(Comparator.comparing(UUID::toString))
                  .forEach(id -> wallet(id, true).ifPresent(result::add));
              afterWalletLocks.run();
              return result;
            });
    when(wallets.findByUserIdAndCurrencyAndAccountRole(any(), any(), any()))
        .thenAnswer(
            call -> {
              List<UUID> ids =
                  jdbc.query(
                      "select id from wallets where owner=? and currency=? and role=?",
                      (rs, row) -> rs.getObject(1, UUID.class),
                      call.getArgument(0),
                      call.getArgument(1),
                      ((WalletAccountRole) call.getArgument(2)).name());
              if (ids.size() > 1)
                throw new org.springframework.dao.IncorrectResultSizeDataAccessException(
                    1, ids.size());
              return ids.isEmpty() ? Optional.empty() : wallet(ids.get(0), false);
            });
    when(wallets.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              Wallet wallet = call.getArgument(0);
              jdbc.update(
                  "update wallets set balance=?, held=? where id=?",
                  wallet.getBalance(),
                  wallet.getHeldBalance(),
                  wallet.getId());
              return wallet;
            });
    when(entries.findByIdempotencyKey(any()))
        .thenAnswer(
            call -> {
              List<LedgerEntry> found =
                  jdbc.query(
                      "select * from entries where entry_key=?",
                      (rs, row) ->
                          new LedgerEntry(
                              rs.getObject("wallet", UUID.class),
                              rs.getString("kind"),
                              rs.getBigDecimal("amount"),
                              rs.getString("currency"),
                              rs.getString("entry_key"),
                              rs.getString("journal"),
                              rs.getString("narration"),
                              rs.getBigDecimal("rate"),
                              rs.getString("quote_id"),
                              Instant.EPOCH),
                      (Object) call.getArgument(0));
              return found.stream().findFirst();
            });
    when(entries.findByJournalReference(any()))
        .thenAnswer(
            call ->
                jdbc.query(
                    "select * from entries where journal=?",
                    (rs, row) ->
                        new LedgerEntry(
                            rs.getObject("wallet", UUID.class),
                            rs.getString("kind"),
                            rs.getBigDecimal("amount"),
                            rs.getString("currency"),
                            rs.getString("entry_key"),
                            rs.getString("journal"),
                            rs.getString("narration"),
                            rs.getBigDecimal("rate"),
                            rs.getString("quote_id"),
                            Instant.EPOCH),
                    (Object) call.getArgument(0)));
    when(entries.save(any()))
        .thenAnswer(
            call -> {
              LedgerEntry entry = call.getArgument(0);
              if (entry.getIdempotencyKey().equals(failKey))
                throw new IllegalStateException("controlled persistence failure");
              jdbc.update(
                  "insert into entries values (?,?,?,?,?,?,?,?,?)",
                  entry.getIdempotencyKey(),
                  entry.getWalletId(),
                  entry.getEntryType(),
                  entry.getAmount(),
                  entry.getCurrency(),
                  entry.getJournalReference(),
                  entry.getNarration(),
                  entry.getRate(),
                  entry.getQuoteId());
              return entry;
            });
    when(journalLocks.findByIdForUpdate(any()))
        .thenAnswer(
            call -> {
              Integer lockId = call.getArgument(0);
              jdbc.queryForObject(
                  "select lock_id from journal_locks where lock_id=? for update",
                  Integer.class,
                  lockId);
              afterJournalLock.accept(lockId);
              return Optional.of(mock(LedgerJournalLock.class));
            });
    when(journalHeaders.findByJournalReference(any()))
        .thenAnswer(
            call ->
                jdbc.query(
                        "select * from journal_headers where journal=?",
                        (rs, row) -> {
                          String payloadHash = rs.getString("payload_hash");
                          LedgerTransactionCategory category =
                              LedgerTransactionCategory.valueOf(rs.getString("category"));
                          Instant createdAt = rs.getTimestamp("created_at").toInstant();
                          return payloadHash == null
                              ? LedgerJournal.historical(rs.getString("journal"), category, createdAt)
                              : new LedgerJournal(
                                  rs.getString("journal"), category, payloadHash, createdAt);
                        },
                        (Object) call.getArgument(0))
                    .stream()
                    .findFirst());
    when(journalHeaders.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              LedgerJournal journal = call.getArgument(0);
              jdbc.update(
                  "insert into journal_headers values (?,?,?,?)",
                  journal.getJournalReference(),
                  journal.getTransactionCategory().name(),
                  journal.getPayloadHash(),
                  java.sql.Timestamp.from(journal.getCreatedAt()));
              return journal;
            });
    writer =
        transactional(new PersistentLedgerWriter(wallets, entries, context, Clock.systemUTC()));
    journals =
        transactional(
            new LedgerJournalService(
                writer,
                context,
                journalHeaders,
                journalLocks,
                entries,
                wallets,
                Clock.systemUTC()));
  }

  <T> T transactional(T service) {
    ProxyFactory factory = new ProxyFactory(service);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return (T) factory.getProxy();
  }

  void seed(UUID id, UUID owner, WalletAccountRole role, String amount) {
    jdbc.update(
        "insert into wallets values (?,?,?,?,?,?)",
        id,
        owner,
        role.name(),
        "USD",
        new BigDecimal(amount),
        new BigDecimal("0.0000"));
  }

  Optional<Wallet> wallet(UUID id, boolean lock) {
    return jdbc
        .query(
            "select * from wallets where id=?" + (lock ? " for update" : ""),
            (rs, row) -> {
              Wallet wallet =
                  new Wallet(
                      rs.getObject("owner", UUID.class),
                      rs.getString("currency"),
                      WalletAccountRole.valueOf(rs.getString("role")));
              ReflectionTestUtils.setField(wallet, "id", rs.getObject("id", UUID.class));
              wallet.setBalance(rs.getBigDecimal("balance"));
              wallet.setHeldBalance(rs.getBigDecimal("held"));
              return wallet;
            },
            id)
        .stream()
        .findFirst();
  }

  BigDecimal balance(UUID id) {
    return jdbc.queryForObject("select balance from wallets where id=?", BigDecimal.class, id);
  }

  BigDecimal held(UUID id) {
    return jdbc.queryForObject("select held from wallets where id=?", BigDecimal.class, id);
  }

  void setHeld(UUID id, String amount) {
    jdbc.update("update wallets set held=? where id=?", new BigDecimal(amount), id);
  }

  int count() {
    return jdbc.queryForObject("select count(*) from entries", Integer.class);
  }

  int journalCount() {
    return jdbc.queryForObject("select count(*) from journal_headers", Integer.class);
  }

  LedgerEntry entry(String key) {
    return entries.findByIdempotencyKey(key).orElseThrow();
  }
}
