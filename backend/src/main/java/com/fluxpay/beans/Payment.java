package com.fluxpay.beans;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="payments")
public class Payment {
 @Id private UUID id; @Column(name="sender_wallet_id",nullable=false) private UUID sourceWalletId; @Column(name="recipient_id",nullable=false) private UUID recipientId;
 @Column(name="amount",nullable=false) private BigDecimal sourceAmount; @Column(name="currency",nullable=false) private String sourceCurrency;
 @Column(name="m3_flow_version") private int flowVersion; @Column(name="sender_id") private UUID senderId; @Column(name="payout_currency") private String payoutCurrency;
 @Enumerated(EnumType.STRING) private PaymentPurpose purpose; @Enumerated(EnumType.STRING) private QuoteRoute preference;
 @Column(name="recipient_version") private long recipientVersion; @Lob @Column(name="recipient_snapshot") private String recipientSnapshot;
 @Column(name="selected_quote_id") private UUID selectedQuoteId; @Column(name="current_quote_generation") private Integer currentQuoteGeneration;
 @Column(name="quote_generation_counter") private int quoteGenerationCounter; @Enumerated(EnumType.STRING) private PaymentLifecycleStatus status;
 @Version private long version; @Column(name="created_at") private Instant createdAt; @Column(name="updated_at") private Instant updatedAt;
 protected Payment(){}
 public Payment(UUID id,UUID sender,UUID wallet,Recipient r,BigDecimal amount,String source,String payout,PaymentPurpose purpose,QuoteRoute preference,String snapshot,Instant now){this.id=id;senderId=sender;sourceWalletId=wallet;recipientId=r.id();sourceAmount=amount;sourceCurrency=source;payoutCurrency=payout;this.purpose=purpose;this.preference=preference;recipientVersion=r.version();recipientSnapshot=snapshot;flowVersion=1;status=PaymentLifecycleStatus.DRAFT;createdAt=now;updatedAt=now;}
 public UUID id(){return id;} public UUID senderId(){return senderId;} public UUID sourceWalletId(){return sourceWalletId;} public UUID recipientId(){return recipientId;} public BigDecimal sourceAmount(){return sourceAmount;} public String sourceCurrency(){return sourceCurrency;} public String payoutCurrency(){return payoutCurrency;} public PaymentPurpose purpose(){return purpose;} public QuoteRoute preference(){return preference;} public long recipientVersion(){return recipientVersion;} public String recipientSnapshot(){return recipientSnapshot;} public PaymentLifecycleStatus status(){return status;} public UUID selectedQuoteId(){return selectedQuoteId;} public Integer currentQuoteGeneration(){return currentQuoteGeneration;} public int nextQuoteGeneration(){return ++quoteGenerationCounter;} public void quoted(int generation,Instant now){currentQuoteGeneration=generation;status=PaymentLifecycleStatus.QUOTED;updatedAt=now;} public void cancel(Instant now){status=PaymentLifecycleStatus.CANCELLED;updatedAt=now;} public void selectAndProcess(UUID quote,Instant now){selectedQuoteId=quote;status=PaymentLifecycleStatus.PROCESSING;updatedAt=now;} public void underReview(Instant now){status=PaymentLifecycleStatus.UNDER_REVIEW;updatedAt=now;}
}
