package com.back.domain.trade.repository;

import com.back.domain.trade.entity.PaymentHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentHistoryRepository extends JpaRepository<PaymentHistory, Long> {

    // FR-6: 같은 멱등 키로 이미 저장된 결제가 있는지 조회해 요청 내용(orderId/amount) 일치 여부를 검증한다.
    Optional<PaymentHistory> findByIdempotencyKey(String idempotencyKey);
}
