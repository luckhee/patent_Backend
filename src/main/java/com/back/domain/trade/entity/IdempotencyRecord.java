package com.back.domain.trade.entity;

import com.back.global.jpa.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "idempotency_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord extends BaseEntity {
    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdempotencyStatus status;

    // 기존 성공 응답을 저장해 두었다가 그대로 반환하기 위한 JSON 컬럼
    @Column(columnDefinition = "TEXT")
    private String responseJson;

    public IdempotencyRecord(String idempotencyKey, IdempotencyStatus status, String responseJson) {
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.responseJson = responseJson;
    }

    public void complete(String responseJson) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseJson = responseJson;
    }

    public IdempotencyRecord setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        return this;
    }

    public IdempotencyRecord setStatus(IdempotencyStatus status) {
        this.status = status;
        return this;
    }

    public IdempotencyRecord setResponseJson(String responseJson) {
        this.responseJson = responseJson;
        return this;
    }
}

