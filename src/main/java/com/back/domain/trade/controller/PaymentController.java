package com.back.domain.trade.controller;


import com.back.domain.trade.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PaymentController {

    @Autowired
    private final PaymentService paymentService;

    //FR-1. 멱등 키 수신
    //클라이언트는 결제 요청 시 HTTP 헤더 Idempotency-Key (권장) 또는 요청 바디로 멱등 키를 전달한다.
    //키 형식: 최대 64자 문자열 (엔티티 컬럼 길이 제약과 일치). 없으면 400 에러.
    @GetMapping("/getIdempotencyKey")
    public HttpStatus payRequest(@RequestBody HashMap<String, String> idempotencyKey) {
        return paymentService.payRequest(idempotencyKey);
    }

}
