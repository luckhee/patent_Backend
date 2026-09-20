package com.back.domain.trade.entity;

import com.back.global.jpa.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(
        name="payment_history",
        indexes = {
                @Index(name = "idx_idempotency_key" , columnList = "idempotencyKey", unique = true)
        }
)
@NoArgsConstructor(access= AccessLevel.PROTECTED)
@Getter
public class PaymentHistory extends BaseEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @Column(nullable = false)
    private String orderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private String pgTransactionId; //pg사 승인 번호

    public PaymentHistory(String idempotencyKey, String orderId, BigDecimal amount, PaymentStatus status, String pgTransactionId) {
        this.idempotencyKey = idempotencyKey;
        this.orderId = orderId;
        this.amount = amount;
        this.status = status;
        this.pgTransactionId = pgTransactionId;
    }
}

