package com.back.domain.trade.service;

import com.back.domain.trade.dto.PayDto;
import com.back.domain.trade.dto.PayResultDto;
import com.back.domain.trade.entity.IdempotencyRecord;
import com.back.domain.trade.entity.IdempotencyStatus;
import com.back.domain.trade.entity.PaymentHistory;
import com.back.domain.trade.entity.PaymentStatus;
import com.back.domain.trade.repository.IdempotencyRecordRepository;
import com.back.domain.trade.repository.PaymentHistoryRepository;
import com.back.global.rsData.RsData;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

//FR-1. 멱등 키 수신
//클라이언트는 결제 요청 시 HTTP 헤더 Idempotency-Key (권장) 또는 요청 바디로 멱등 키를 전달한다.
//키 형식: 최대 64자 문자열 (엔티티 컬럼 길이 제약과 일치). 없으면 400 에러.
@Service
public class PaymentService {

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private PaymentHistoryRepository paymentHistoryRepository;

    //static IdempotencyRecord idempotencyRecord;

    @Transactional
    public RsData<PayResultDto> payRequest(PayDto payDto) {
        IdempotencyRecord idempotencyRecord = new IdempotencyRecord();
        try {
            if(payDto.IdempotencyKey().isBlank() || payDto.IdempotencyKey().length() > 64) {
                return new RsData("400", "잚못된 요청입니다.");
            }

            //FR-2. 최초 요청 처리 (Happy Path)
            //멱등 키로 기존 기록을 조회한다. ok
            //기록이 없으면 → 상태 PROCESSING 으로 기록을 선점 생성한다. ok
            //실제 결제 로직(PG사 승인 등)을 수행한다.
            //성공하면:
            //PaymentHistoryRepository 저장 (PaymentStatus.SUCCESS, pgTransactionId 포함)ok
            //IdempotencyRecord.complete(responseJson) 호출 → 상태 COMPLETED + 응답 JSON 저장ok
            //성공 응답을 반환한다.ok

            PaymentHistory paymentHistory = new PaymentHistory();

            //최초 결제 요청
            if(firstPayRequest(idempotencyRecord, payDto)) {
                if(payForPg()) {
                    paymentHistory.setIdempotencyKey(payDto.IdempotencyKey());
                    paymentHistory.setOrderId(payDto.orderID());
                    paymentHistory.setAmount(payDto.amount());
                    paymentHistory.setStatus(PaymentStatus.SUCCESS);
                    paymentHistory.setPgTransactionId(payDto.pgTransactionalId());

                    paymentHistoryRepository.save(paymentHistory);
                    idempotencyRecord.complete("결제 성공 하였습니다.");
                    idempotencyRecord.setStatus(IdempotencyStatus.COMPLETED);
                    idempotencyRecord.setResponseJson("결제 성공 하였습니다.");
                    idempotencyRecordRepository.save(idempotencyRecord);



                    return new RsData<>("200", "결제 성공 하였습니다.", new PayResultDto(paymentHistory));
                } else {
                    //FR-3. 중복 요청 처리 — 이미 완료됨 (COMPLETED)
                    //같은 멱등 키의 기록이 COMPLETED 상태면, 결제 로직을 재실행하지 않고 저장된 responseJson 을 그대로 반환한다.
                    //반환 결과는 최초 성공 응답과 완전히 동일해야 한다.

                    return new RsData<>("200", "결제 성공 하였습니다.", new PayResultDto(paymentHistory));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }


        return new RsData<>("200", "결제 성공 하였습니다.");
    }

    private boolean firstPayRequest(IdempotencyRecord idempotencyRecord,PayDto payDto) {
        boolean result = true;
        try {
            IdempotencyStatus status = idempotencyRecordRepository.findByIdempotencyKey(payDto.IdempotencyKey()).getStatus();
            //if(status != IdempotencyStatus.COMPLETED) {
            if(status == null) {
                idempotencyRecord.setIdempotencyKey(payDto.IdempotencyKey());
                idempotencyRecord.setStatus(IdempotencyStatus.PROCESSING);
                idempotencyRecord.setResponseJson("idempotencyKey 수신 성공");

                idempotencyRecordRepository.save(idempotencyRecord);
            } else result = false;
        } catch (Exception e) {
            e.printStackTrace();
        }


        return result;
    }

    private boolean payForPg() {
        /* TODO
            PG사 실제 결제 로직
            */
        return true;
    }






    //FR-4. 중복 요청 처리 — 처리 중 (PROCESSING)
    //같은 멱등 키의 요청이 아직 PROCESSING 상태(= 앞선 요청이 아직 끝나지 않음)면:
    //새 결제를 시작하면 안 된다.
    //409 Conflict (예: "이미 처리 중인 요청입니다. 잠시 후 다시 시도하세요.") 를 반환한다.
    //이 규칙이 동시성(더블 클릭·동시 요청) 방어의 핵심이다.
    //FR-5. 실패 처리 (FAILED)
    //결제 로직이 실패하면 상태를 FAILED 로 기록하고 실패 응답을 반환한다.
    //정책 결정 필요 (아래 열린 질문 Q1 참고):
    //(A) 실패한 키는 재시도 허용 → 같은 키로 다시 오면 재처리
    //(B) 실패도 최종 상태로 굳혀 재시도 시 저장된 실패 응답 반환
    //권장 기본값: (A) 재시도 허용 (일시적 오류/타임아웃 대응). 단, "이미 돈은 빠졌는데 응답만 실패"한 케이스와 구분 주의.
    //FR-6. 금액/주문 일치 검증
    //같은 멱등 키인데 요청 내용(orderId, amount)이 다르면 이는 키 재사용 오용이다.
    //이 경우 422 Unprocessable Entity (예: "멱등 키가 다른 요청에 재사용되었습니다") 를 반환한다.
}
