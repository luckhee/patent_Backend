package com.back.domain.trade.entity;

import com.back.global.jpa.entity.BaseEntity;
import org.springframework.data.redis.core.TimeToLive;

public class IdempotencyCacheModel  extends BaseEntity { // 여기서 Sereializable 사용하라고 하는데 이유 알아올것 (Hint 캐시 관련)

    private String idempotencyKey;

    private String status; // PROCESSING, COMPLETED

    private String responsePayload; // JSON 형태의 응답 결과값

    @TimeToLive
    private Long timeoutInSeconds = 86400L; // TTL: 24시간 (초 단위)

    public IdempotencyCacheModel(String idempotencyKey, String status, String responsePayload) {
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.responsePayload = responsePayload;
    }

    // Getter, Setter 생략
}
