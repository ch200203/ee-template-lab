templateVersion: 0.1.0

### TX-01 트랜잭션 안에서 외부 API를 호출함
id: TX-01
area: null
description: 트랜잭션 안에서 외부 API를 호출하지 않는다.
severity: must
active: true
rationale: 외부 지연이 트랜잭션 점유 시간을 늘린다.
fix: 외부 호출을 트랜잭션 밖으로 옮긴다.

### TX-03 반복문 안에서 쿼리를 실행함
id: TX-03
area: null
description: 반복문 쿼리로 N+1을 만들지 않는다.
severity: must
active: true
rationale: 데이터 수만큼 DB 왕복이 늘어난다.
fix: 일괄 조회로 바꾼다.

### TX-04 건수 제한 없이 전체를 조회함
id: TX-04
area: null
description: 목록 조회에 건수 제한을 둔다.
severity: must
active: true
rationale: 전체 조회는 메모리와 응답 시간을 증가시킨다.
fix: 페이지 크기 상한을 적용한다.

### TEST-01 새 분기에 테스트 없음
id: TEST-01
area: null
description: 새 분기를 검증하는 테스트를 추가한다.
severity: should
active: true
rationale: 분기 회귀를 발견할 수 있어야 한다.
fix: 정상과 예외 분기 테스트를 추가한다.

### ERR-03 공통 예외를 쓰지 않음
id: ERR-03
area: null
description: 공통 예외 정책을 따른다.
severity: should
active: false
rationale: 오류 응답의 일관성을 유지한다.
fix: 저장소의 공통 예외로 바꾼다.

### AI-02 필요 없는 추상화 추가
id: AI-02
area: null
description: 현재 요구에 필요 없는 추상화를 추가하지 않는다.
severity: should
active: false
rationale: 불필요한 간접 계층은 변경 비용을 늘린다.
fix: 사용처가 하나인 불필요한 계층을 제거한다.
