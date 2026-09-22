package com.back.domain.trade.repository;

import com.back.domain.trade.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    IdempotencyRecord findByIdempotencyKey(String idempotencyRecord);
}
