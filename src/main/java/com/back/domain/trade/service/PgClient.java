package com.back.domain.trade.service;

import com.back.domain.trade.dto.PayDto;

/**
 * PG사 결제 승인 seam.
 *
 * 아직 실제 PG 연동이 정해지지 않았으므로 "승인 성공/실패"를 boolean 으로만 표현한다.
 * 이렇게 인터페이스로 분리해 두면 테스트에서 성공/실패를 강제로 주입(mock)할 수 있고,
 * 나중에 실제 PG SDK 구현체로 갈아끼우기도 쉽다.
 *
 * PaymentService 는 private payForPg() 대신 이 PgClient 를 주입받아 사용한다.
 */
public interface PgClient {
    boolean approve(PayDto payDto);
}
