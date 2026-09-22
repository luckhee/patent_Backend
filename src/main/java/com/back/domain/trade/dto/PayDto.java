package com.back.domain.trade.dto;

import com.back.domain.trade.entity.PaymentHistory;
import com.mongodb.lang.NonNull;

import java.math.BigDecimal;

public record PayDto(
        @NonNull String IdempotencyKey,
        @NonNull String orderID,
        @NonNull BigDecimal amount,
        @NonNull String pgTransactionalId

) {

    public PayDto(PaymentHistory paymentHistory) {
        this(
                paymentHistory.getIdempotencyKey(),
                paymentHistory.getOrderId(),
                paymentHistory.getAmount(),
                paymentHistory.getPgTransactionId()
        );
    }
}
