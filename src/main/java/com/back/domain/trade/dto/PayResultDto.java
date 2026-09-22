package com.back.domain.trade.dto;

import com.back.domain.trade.entity.PaymentHistory;
import com.back.domain.trade.entity.PaymentStatus;

import java.math.BigDecimal;

/**
 * 결제 요청의 응답 본문(payload).
 *
 * RsData<PayResultDto> 의 제네릭 T 로 사용한다.
 * - 최초 성공 시 이 DTO 를 만들어 반환하고, 동시에 JSON 으로 직렬화해
 *   IdempotencyRecord.responseJson 에 저장해 둔다.
 * - 같은 키로 재요청(FR-3)이 오면 저장해 둔 JSON 을 이 DTO 로 역직렬화해 "동일한 응답"을 돌려준다.
 */
public record PayResultDto(
        String idempotencyKey,
        String orderId,
        BigDecimal amount,
        PaymentStatus status,
        String pgTransactionId
) {
    public PayResultDto(PaymentHistory paymentHistory) {
        this(
                paymentHistory.getIdempotencyKey(),
                paymentHistory.getOrderId(),
                paymentHistory.getAmount(),
                paymentHistory.getStatus(),
                paymentHistory.getPgTransactionId()
        );
    }
}
