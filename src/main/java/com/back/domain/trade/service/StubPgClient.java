package com.back.domain.trade.service;

import com.back.domain.trade.dto.PayDto;
import org.springframework.stereotype.Component;

/**
 * PG 연동 전 임시 구현체. 항상 승인 성공(true)을 반환한다.
 * (실제 애플리케이션이 부팅될 수 있도록 하는 기본 빈. 테스트에서는 이 대신 mock 을 주입한다.)
 */
@Component
public class StubPgClient implements PgClient {
    @Override
    public boolean approve(PayDto payDto) {
        return true;
    }
}
