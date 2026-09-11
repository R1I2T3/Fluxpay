package com.fluxpay.repository;
import com.fluxpay.beans.PaymentQuote; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface PaymentQuoteRepository extends JpaRepository<PaymentQuote,UUID> { List<PaymentQuote> findByPaymentIdAndGenerationOrderByRouteAsc(UUID paymentId,int generation); Optional<PaymentQuote> findByIdAndPaymentId(UUID id,UUID paymentId); }
