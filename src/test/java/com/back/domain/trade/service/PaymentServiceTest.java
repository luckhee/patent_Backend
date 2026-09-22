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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PaymentService 의 기능 요구사항(FR) 단위 테스트.
 *
 * ── 이 테스트가 가정하는 계약(구현하면서 맞춰야 하는 목표) ──────────────────
 *  1. PaymentService 는 다음 3개를 주입받는다(@InjectMocks 가 필드/생성자 주입 모두 채워준다):
 *       - IdempotencyRecordRepository idempotencyRecordRepository
 *       - PaymentHistoryRepository    paymentHistoryRepository
 *       - PgClient                    pgClient   ← private payForPg() 를 대체
 *  2. 진입점:  RsData<PayResultDto> payRequest(PayDto payDto)
 *       - 제네릭 T = PayResultDto : 결제 결과 응답 본문(주문번호/금액/상태/PG승인번호).
 *         성공 시 이 DTO 를 data 로 담고, 동시에 JSON 으로 responseJson 에 저장해 재요청 때 그대로 돌려준다.
 *  3. resultCode 규칙(스펙 §7). RsData 는 resultCode 앞자리를 statusCode(int)로 파싱하므로
 *     테스트는 주로 statusCode() 로 단언한다("400" 이든 "400-IDEM-1" 이든 400 으로 파싱됨):
 *       - 200 : 최초 결제 성공 / COMPLETED 재요청 replay   (FR-2, FR-3)
 *       - 400 : 멱등 키 누락/공백/64자 초과                (FR-1)
 *       - 409 : PROCESSING 상태 중복 요청                  (FR-4)
 *       - 402 : PG 승인 실패                              (FR-5)
 *       - 422 : 같은 키 다른 요청내용(키 재사용)            (FR-6)
 *
 * 참고:
 *  - 현재 PaymentService 는 컴파일 에러(RsData<> 빈 제네릭, 지역변수 static, 없는 setter,
 *    끝부분 return HttpStatus.OK 등)와 private payForPg() 를 쓰고 있어, 이 테스트를 통과시키려면
 *    먼저 위 계약대로 리팩터링해야 한다. (그게 이 학습의 핵심 — 테스트가 곧 명세)
 *  - 동시성/원자성(스펙 §5) 은 순수 단위테스트로 재현이 어렵다. 맨 아래 TODO 참고.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService 멱등 결제 FR 테스트")
class PaymentServiceTest {

    @Mock
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Mock
    private PaymentHistoryRepository paymentHistoryRepository;

    @Mock
    private PgClient pgClient;

    @InjectMocks
    private PaymentService paymentService;

    private static final String KEY = "idem-key-1234";

    private PayDto payDto(String key, String orderId, String amount) {
        return new PayDto(key, orderId, new BigDecimal(amount), "pg-tx-1");
    }

    private PayDto validPayDto() {
        return payDto(KEY, "order-1", "10000");
    }

    // ────────────────────────────────────────────────────────────────────
    // FR-1. 멱등 키 수신 & 유효성 검사
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-1: 멱등 키 유효성 검사")
    class Fr1 {

        @Test
        @DisplayName("멱등 키가 공백이면 400 이고, 아무 것도 저장/결제하지 않는다")
        void blankKey_returns400() {
            RsData<PayResultDto> rs = paymentService.payRequest(payDto("", "order-1", "10000"));

            assertThat(rs.statusCode()).isEqualTo(400);
            assertThat(rs.data()).isNull();
            verify(idempotencyRecordRepository, never()).save(any());
            verify(paymentHistoryRepository, never()).save(any());
            verifyNoInteractions(pgClient);
        }

        @Test
        @DisplayName("멱등 키가 64자를 초과하면 400 이다")
        void tooLongKey_returns400() {
            RsData<PayResultDto> rs = paymentService.payRequest(payDto("x".repeat(65), "order-1", "10000"));

            assertThat(rs.statusCode()).isEqualTo(400);
            verify(idempotencyRecordRepository, never()).save(any());
            verifyNoInteractions(pgClient);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // FR-2. 최초 요청 처리 (Happy Path)
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-2: 최초 요청 성공")
    class Fr2 {

        @Test
        @DisplayName("기록이 없으면 결제 후 PaymentHistory(SUCCESS) 저장 + 기록 COMPLETED, 200 과 결과 본문을 반환한다")
        void firstRequest_success() {
            PayDto req = validPayDto();
            given_noExistingRecord();
            when(pgClient.approve(any(PayDto.class))).thenReturn(true);

            RsData<PayResultDto> rs = paymentService.payRequest(req);

            // 응답: 200 + 결과 본문(data)이 채워지고 SUCCESS 다
            assertThat(rs.statusCode()).isEqualTo(200);
            assertThat(rs.data()).isNotNull();
            assertThat(rs.data().orderId()).isEqualTo("order-1");
            assertThat(rs.data().amount()).isEqualByComparingTo("10000");
            assertThat(rs.data().status()).isEqualTo(PaymentStatus.SUCCESS);

            // PG 는 정확히 한 번 호출된다
            verify(pgClient, times(1)).approve(any(PayDto.class));

            // 결제 이력이 SUCCESS 로 저장된다
            ArgumentCaptor<PaymentHistory> phCaptor = ArgumentCaptor.forClass(PaymentHistory.class);
            verify(paymentHistoryRepository).save(phCaptor.capture());
            PaymentHistory saved = phCaptor.getValue();
            assertThat(saved.getIdempotencyKey()).isEqualTo(KEY);
            assertThat(saved.getOrderId()).isEqualTo("order-1");
            assertThat(saved.getAmount()).isEqualByComparingTo("10000");
            assertThat(saved.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

            // 멱등 기록이 최종적으로 COMPLETED + 응답 JSON 을 가진다
            ArgumentCaptor<IdempotencyRecord> recCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
            verify(idempotencyRecordRepository, atLeastOnce()).save(recCaptor.capture());
            IdempotencyRecord finalRecord = recCaptor.getValue();
            assertThat(finalRecord.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
            assertThat(finalRecord.getResponseJson()).isNotBlank();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // FR-3. 중복 요청 — 이미 완료(COMPLETED) → 재실행 없이 저장된 응답 반환
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-3: 완료된 키 재요청은 결제를 다시 하지 않는다")
    class Fr3 {

        @Test
        @DisplayName("COMPLETED 기록이 있으면 PG 미호출·새 결제 미저장, 200 을 반환한다")
        void completed_replaysWithoutReprocessing() {
            PayDto req = validPayDto();
            IdempotencyRecord completed = new IdempotencyRecord(
                    KEY, IdempotencyStatus.COMPLETED,
                    "{\"idempotencyKey\":\"idem-key-1234\",\"orderId\":\"order-1\",\"amount\":10000,\"status\":\"SUCCESS\",\"pgTransactionId\":\"pg-tx-1\"}");
            when(idempotencyRecordRepository.findByIdempotencyKey(KEY)).thenReturn(completed);
            // 재사용(FR-6) 검증용: 같은 키의 결제가 "같은 내용"으로 존재
            lenient().when(paymentHistoryRepository.findByIdempotencyKey(KEY))
                    .thenReturn(Optional.of(new PaymentHistory(KEY, "order-1", new BigDecimal("10000"), PaymentStatus.SUCCESS, "pg-tx-1")));

            RsData<PayResultDto> rs = paymentService.payRequest(req);

            assertThat(rs.statusCode()).isEqualTo(200);
            // 저장된 응답을 그대로 돌려준다(NFR 응답 일관성): 최초 결과와 동일한 내용
            assertThat(rs.data()).isNotNull();
            assertThat(rs.data().orderId()).isEqualTo("order-1");
            assertThat(rs.data().status()).isEqualTo(PaymentStatus.SUCCESS);
            // 결제 로직 재실행 금지
            verifyNoInteractions(pgClient);
            verify(paymentHistoryRepository, never()).save(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // FR-4. 중복 요청 — 처리 중(PROCESSING) → 409, 재실행 금지
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-4: 처리 중 키 재요청은 409")
    class Fr4 {

        @Test
        @DisplayName("PROCESSING 기록이 있으면 409 이고 새 결제를 시작하지 않는다")
        void processing_returns409() {
            IdempotencyRecord processing =
                    new IdempotencyRecord(KEY, IdempotencyStatus.PROCESSING, "idempotencyKey 수신 성공");
            when(idempotencyRecordRepository.findByIdempotencyKey(KEY)).thenReturn(processing);

            RsData<PayResultDto> rs = paymentService.payRequest(validPayDto());

            assertThat(rs.statusCode()).isEqualTo(409);
            verifyNoInteractions(pgClient);
            verify(paymentHistoryRepository, never()).save(any());
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // FR-5. 결제 실패 처리 → FAILED 기록, SUCCESS 이력 없음
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-5: PG 승인 실패")
    class Fr5 {

        @Test
        @DisplayName("PG 가 승인 실패(false)면 402 이고 SUCCESS 이력을 남기지 않으며 기록은 FAILED 가 된다")
        void pgDeclined_marksFailed() {
            PayDto req = validPayDto();
            given_noExistingRecord();
            when(pgClient.approve(any(PayDto.class))).thenReturn(false);

            RsData<PayResultDto> rs = paymentService.payRequest(req);

            assertThat(rs.statusCode()).isEqualTo(402);

            // 성공 결제 이력이 저장되면 안 된다 (스펙 §5 불변식: SUCCESS 최대 1건)
            ArgumentCaptor<PaymentHistory> phCaptor = ArgumentCaptor.forClass(PaymentHistory.class);
            verify(paymentHistoryRepository, atMost(1)).save(phCaptor.capture());
            phCaptor.getAllValues().forEach(ph ->
                    assertThat(ph.getStatus()).isNotEqualTo(PaymentStatus.SUCCESS));

            // 멱등 기록은 FAILED 로 마감된다
            ArgumentCaptor<IdempotencyRecord> recCaptor = ArgumentCaptor.forClass(IdempotencyRecord.class);
            verify(idempotencyRecordRepository, atLeastOnce()).save(recCaptor.capture());
            assertThat(recCaptor.getValue().getStatus()).isEqualTo(IdempotencyStatus.FAILED);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // FR-6. 같은 키 다른 요청내용(키 재사용) → 422
    // ────────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("FR-6: 멱등 키 재사용 오용")
    class Fr6 {

        @Test
        @DisplayName("같은 키인데 금액이 다르면 422 이고 결제를 다시 하지 않는다")
        void sameKeyDifferentAmount_returns422() {
            // 원래 이 키로 order-1 / 10000 이 결제되어 완료됨
            IdempotencyRecord completed =
                    new IdempotencyRecord(KEY, IdempotencyStatus.COMPLETED, "{\"orderId\":\"order-1\",\"amount\":10000}");
            when(idempotencyRecordRepository.findByIdempotencyKey(KEY)).thenReturn(completed);
            when(paymentHistoryRepository.findByIdempotencyKey(KEY))
                    .thenReturn(Optional.of(new PaymentHistory(KEY, "order-1", new BigDecimal("10000"), PaymentStatus.SUCCESS, "pg-tx-1")));

            // 같은 키인데 금액이 다른 요청
            RsData<PayResultDto> rs = paymentService.payRequest(payDto(KEY, "order-1", "99999"));

            assertThat(rs.statusCode()).isEqualTo(422);
            verifyNoInteractions(pgClient);
            verify(paymentHistoryRepository, never()).save(any());
        }
    }

    // ── 헬퍼: 기존 기록이 없는 최초 요청 상황 ────────────────────────────────
    private void given_noExistingRecord() {
        when(idempotencyRecordRepository.findByIdempotencyKey(KEY)).thenReturn(null);
        lenient().when(paymentHistoryRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
    }

    // ── TODO (별도 통합테스트로 다룰 것): 스펙 §5 동시성/원자성 ──────────────────
    //  같은 멱등 키로 2개의 스레드가 "동시에" payRequest 를 호출해도
    //   - PaymentHistory 는 정확히 1건만 SUCCESS 로 남고
    //   - 한쪽은 409(또는 캐시된 응답)로 처리되는지
    //  를 @SpringBootTest + 실제 DB unique 제약 + ExecutorService/CountDownLatch 로 검증한다.
}
