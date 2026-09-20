package com.back.domain.trade.service;

import com.back.domain.trade.entity.IdempotencyRecord;
import com.back.domain.trade.entity.IdempotencyStatus;
import com.back.domain.trade.repository.IdempotencyRecordRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;

//FR-1. 멱등 키 수신
//클라이언트는 결제 요청 시 HTTP 헤더 Idempotency-Key (권장) 또는 요청 바디로 멱등 키를 전달한다.
//키 형식: 최대 64자 문자열 (엔티티 컬럼 길이 제약과 일치). 없으면 400 에러.
@Service
public class PaymentService {

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Transactional
    public HttpStatus payRequest(HashMap<String, String> map) {
        try {
            if(map.get("idempotencyKey").isEmpty()) {
                return HttpStatus.BAD_REQUEST;
            }

            IdempotencyRecord idempotencyRecord = new IdempotencyRecord(
                    map.get("idempotencyKey"), IdempotencyStatus.PROCESSING, "idempotencyKey 수신 성공"
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


        return HttpStatus.OK;
    }


}
