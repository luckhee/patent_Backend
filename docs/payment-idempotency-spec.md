# 결제 멱등성(Idempotency) 요구사항 명세서

> 대상 도메인: `com.back.domain.trade`
> 작성 목적: 결제 API에 멱등성을 적용하기 위한 서비스 로직 구현 전, "무엇을 / 왜" 만들어야 하는지 정의한다.
> (서비스 코드는 학습용으로 직접 구현하므로 이 문서에는 포함하지 않는다.)

---

## 1. 배경 & 문제 정의

결제는 **정확히 한 번(exactly-once)** 실행되어야 하는 대표적인 작업이다. 하지만 현실에서는 다음 상황이 발생한다.

- 사용자가 결제 버튼을 두 번 누름 (더블 클릭)
- 네트워크 타임아웃 후 클라이언트가 **같은 요청을 재전송(retry)**
- 응답은 유실됐지만 서버는 이미 처리 완료한 상태

이때 아무 장치가 없으면 **같은 주문에 대해 결제가 중복 발생**한다 (돈이 두 번 빠져나감).

**멱등성**이란: *동일한 요청을 여러 번 보내도 결과가 한 번 보낸 것과 같아야 한다*는 성질이다.
이를 위해 클라이언트가 요청마다 고유한 `Idempotency-Key`를 발급하고, 서버는 그 키를 기준으로 "이미 처리했는지"를 판별한다.

---

## 2. 핵심 개념 및 엔티티 역할

| 구성요소 | 저장소 | 역할 |
|---|---|---|
| `IdempotencyRecord` | RDB (JPA) | 멱등 키별 처리 상태와 성공 응답(JSON)을 **영구 저장**. 최종 진실(source of truth). |
| `IdempotencyCacheModel` | Redis (`@TimeToLive` 24h) | 멱등 키 상태를 **빠르게 조회 / 선점(lock)**. TTL로 자동 만료. |
| `PaymentHistoryRepository` | RDB (JPA) | 실제 결제 이력. `idempotencyKey` 에 **unique 제약** → DB 레벨 중복 방지 최후 보루. |
| `IdempotencyStatus` | enum | `PROCESSING`, `COMPLETED`, `FAILED` |
| `PaymentStatus` | enum | `PENDING`, `SUCCESS`, `FAILED` |

> 참고: 멱등 상태(`IdempotencyStatus`)와 결제 상태(`PaymentStatus`)는 다른 층위다.
> - 멱등 상태 = "이 요청을 처리 중/처리 완료 했는가" (요청 관점)
> - 결제 상태 = "돈이 실제로 결제 됐는가" (도메인 관점)

---

## 3. 기능 요구사항 (FR)

### FR-1. 멱등 키 수신
- 클라이언트는 결제 요청 시 HTTP 헤더 `Idempotency-Key` (권장) 또는 요청 바디로 멱등 키를 전달한다.
- 키 형식: 최대 64자 문자열 (엔티티 컬럼 길이 제약과 일치). 없으면 `400` 에러.

### FR-2. 최초 요청 처리 (Happy Path)
1. 멱등 키로 기존 기록을 조회한다.
2. 기록이 없으면 → 상태 `PROCESSING` 으로 기록을 **선점 생성**한다.
3. 실제 결제 로직(PG사 승인 등)을 수행한다.
4. 성공하면:
   - `PaymentHistoryRepository` 저장 (`PaymentStatus.SUCCESS`, `pgTransactionId` 포함)
   - `IdempotencyRecord.complete(responseJson)` 호출 → 상태 `COMPLETED` + 응답 JSON 저장
5. 성공 응답을 반환한다.

### FR-3. 중복 요청 처리 — 이미 완료됨 (`COMPLETED`)
- 같은 멱등 키의 기록이 `COMPLETED` 상태면, **결제 로직을 재실행하지 않고** 저장된 `responseJson` 을 그대로 반환한다.
- 반환 결과는 최초 성공 응답과 **완전히 동일**해야 한다.

### FR-4. 중복 요청 처리 — 처리 중 (`PROCESSING`)
- 같은 멱등 키의 요청이 아직 `PROCESSING` 상태(= 앞선 요청이 아직 끝나지 않음)면:
  - 새 결제를 시작하면 **안 된다.**
  - `409 Conflict` (예: "이미 처리 중인 요청입니다. 잠시 후 다시 시도하세요.") 를 반환한다.
- 이 규칙이 동시성(더블 클릭·동시 요청) 방어의 핵심이다.

### FR-5. 실패 처리 (`FAILED`)
- 결제 로직이 실패하면 상태를 `FAILED` 로 기록하고 실패 응답을 반환한다.
- 정책 결정 필요 (아래 열린 질문 Q1 참고):
  - (A) 실패한 키는 **재시도 허용** → 같은 키로 다시 오면 재처리
  - (B) 실패도 최종 상태로 굳혀 재시도 시 저장된 실패 응답 반환
- 권장 기본값: **(A) 재시도 허용** (일시적 오류/타임아웃 대응). 단, "이미 돈은 빠졌는데 응답만 실패"한 케이스와 구분 주의.

### FR-6. 금액/주문 일치 검증
- 같은 멱등 키인데 **요청 내용(orderId, amount)이 다르면** 이는 키 재사용 오용이다.
- 이 경우 `422 Unprocessable Entity` (예: "멱등 키가 다른 요청에 재사용되었습니다") 를 반환한다.

---

## 4. 처리 흐름 (상태 다이어그램)

```
요청 도착 (Idempotency-Key)
        │
        ▼
 [Redis/DB에서 키 조회]
        │
   ┌────┴───────────────┬──────────────────┬───────────────┐
 없음                COMPLETED           PROCESSING        FAILED
   │                    │                   │               │
 선점(PROCESSING)    저장된 응답        409 반환        정책에 따라
   │                  그대로 반환      (재실행 금지)    재처리 or 응답반환
 결제 실행
   │
 ┌─┴─────┐
성공     실패
 │        │
COMPLETED FAILED
저장     저장
 │        │
응답반환  에러반환
```

---

## 5. 동시성 & 원자성 요구사항 (가장 중요)

멱등성의 진짜 어려움은 **"조회했더니 없더라 → 그럼 만들자" 사이의 경쟁 상태(race condition)** 다.
두 요청이 동시에 "없음"을 읽고 둘 다 결제를 시작할 수 있다.

방어는 **다층(defense in depth)** 으로 설계한다:

1. **1차 (빠름): Redis 원자적 선점**
   - `SETNX` (set if not exists) 성격의 연산으로 `PROCESSING` 을 선점.
   - 선점 실패 → 다른 요청이 이미 처리 중 → `409`.
   - TTL(24h)로 좀비 락 자동 해제.

2. **2차 (최종 보루): DB unique 제약**
   - `PaymentHistory.idempotencyKey` 의 `unique=true` 인덱스.
   - Redis가 뚫려도(장애/키 만료) 중복 INSERT 시 DB가 `DataIntegrityViolationException` 을 던진다.
   - 이 예외를 잡아 "이미 처리됨"으로 우아하게 변환한다.

3. **트랜잭션 경계**
   - 기존 `TradeService.createTrade` 가 쓰는 `@Transactional` + 비관적 락(`findByIdForUpdate`) 패턴과 일관되게 설계.
   - 외부 PG 호출은 **DB 트랜잭션 밖**에서 하는 것을 권장 (커넥션 장시간 점유 방지). 이 경계 설계는 열린 질문 Q2.

> **불변식 (Invariant):** 어떤 상황에서도 하나의 `idempotencyKey` 에 대해 `PaymentStatus.SUCCESS` 인 `PaymentHistoryRepository` 는 **최대 1건**이어야 한다.

---

## 6. 비기능 요구사항 (NFR)

- **응답 일관성:** 중복 요청의 응답은 최초 응답과 바이트 단위로 동일해야 한다 (`responseJson` 그대로 반환).
- **성능:** 중복 요청 판별은 Redis 우선 조회로 DB 부하를 줄인다.
- **만료 정책:** 멱등 키 유효기간 24시간 (Redis TTL). 이후 같은 키는 새 요청으로 간주.
- **관측성:** 중복 감지(409), 키 재사용(422), DB unique 충돌 발생 시 로그를 남긴다.
- **에러 응답 포맷:** 기존 `RsData` / `ServiceException(code, message)` 패턴을 따른다.

---

## 7. 에러 코드 (제안)

| 상황 | HTTP | code (예시) | 메시지 |
|---|---|---|---|
| 멱등 키 누락 | 400 | `400-IDEM-1` | 멱등 키가 필요합니다. |
| 처리 중 중복 요청 | 409 | `409-IDEM-1` | 이미 처리 중인 요청입니다. |
| 키 재사용(내용 불일치) | 422 | `422-IDEM-1` | 멱등 키가 다른 요청에 재사용되었습니다. |
| 결제 실패 | 402/400 | `402-PAY-1` | 결제에 실패했습니다. |

---

## 8. 학습용 열린 질문 (직접 결정해볼 것)

- **Q1.** `FAILED` 상태의 키를 재시도 허용할지, 실패 응답을 캐싱할지? (돈은 빠졌는데 응답만 실패한 경우 어떻게 구분?)
- **Q2.** PG 외부 호출을 DB 트랜잭션 안에 둘지 밖에 둘지? (원자성 vs 커넥션 점유 트레이드오프)
- **Q3.** Redis와 DB 중 무엇을 "진실의 원천"으로 삼을지? 둘의 상태가 어긋나면(Redis엔 COMPLETED, DB엔 없음) 어떻게 복구?
- **Q4.** `IdempotencyCacheModel` 은 왜 `Serializable` 이어야 하나? (엔티티 주석의 힌트 = Redis 직렬화. `@RedisHash` 도 아직 안 붙어있음 — 확인 필요)
- **Q5.** `IdempotencyRecord` 와 `PaymentHistoryRepository` 둘 다 필요한가, 하나로 합칠 수 있나? (관심사 분리 관점에서 판단)
- **Q6.** 멱등 키는 클라이언트가 생성하나 서버가 발급하나? UUID? 요청 내용 해시?

---

## 9. 구현 체크리스트 (서비스 로직 짤 때 순서)

- [ ] 멱등 키 파라미터/헤더 수신 & 유효성 검사 (FR-1)
- [ ] Redis 원자적 선점 (`PROCESSING`) — 실패 시 409 (FR-4, §5-1)
- [ ] 기존 기록 상태 분기: `COMPLETED` → 캐시 응답 반환 (FR-3)
- [ ] 결제 로직 실행 (PG 호출 stub 가능)
- [ ] 성공: `PaymentHistoryRepository` 저장 + `IdempotencyRecord.complete()` (FR-2)
- [ ] DB unique 충돌 예외 처리 → 중복으로 변환 (§5-2)
- [ ] 실패: `FAILED` 기록 (FR-5)
- [ ] 요청 내용 불일치 검증 (FR-6)
- [ ] 단위/동시성 테스트: 같은 키 2번 동시 호출 시 결제 1건만 발생하는지
```
