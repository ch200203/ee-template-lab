# order-service

Java 21, Spring Boot 3 서비스 템플릿이다. Claude Code 훅이 수정 직후와 응답 종료 직전에 같은 Gradle 구조 검사를 돌리고, 로컬 AI 리뷰 도구가 REVIEW.md 규칙으로 변경을 검토한다. 최종 판정은 PR의 필수 CI 체크다.

## 사전 조건

- JDK 21 (Gradle toolchain이 찾을 수 있어야 한다. 다른 JDK로 Gradle을 실행해도 된다)
- Git, Python 3.9 이상
- 훅과 로컬 리뷰를 쓰려면 인증된 Claude Code CLI(`claude`)
- 포트 8080이 비어 있어야 한다
- Windows는 WSL에서 실행한다

## 실행

```sh
./gradlew test archCheck        # 일반 테스트와 구조 검사
./gradlew bootRun               # 로컬 실행
curl --fail http://localhost:8080/actuator/health
```

## 구조 검사

| 명령 | 내용 |
|---|---|
| `./gradlew archCheck` | 일반 테스트 후 ArchUnit 구조 검사(ARCH-01~05). CI와 같은 기본 모드 |
| `./gradlew archCheck -ParchFast=true` | 컴파일과 구조 검사만. 수정 훅이 쓴다 |
| `./gradlew build archCheck` | 빌드, 일반 테스트, 구조 검사. 완료 조건이며 Stop 훅이 쓴다 |

위반이 있으면 `[ARCH-xx] 위반 내용 (경로:줄) | 고치는 방법: 조치` 줄로 실패한다. 결과는 위반 0건이어도 `build/reports/ee/structure-report.json`에 남는다. 로컬 보고서는 지표에 쓰지 않는다.

허용 방향은 api → application, application → domain·infra, infra → domain이고 네 계층 모두 common을 쓸 수 있다. 루트 패키지 `com.example.order`의 클래스는 부트스트랩이다. 그 밖의 하위 패키지는 만들지 않는다.

## Claude Code 훅

`.claude/settings.json`이 공유 훅을 등록한다. 저장소 루트에서 `claude`를 실행하면 적용된다.

| 이벤트 | 명령 | 결과 |
|---|---|---|
| Edit·Write 성공 뒤 (`src/`, `gradle/`, Gradle 설정 파일만) | `./gradlew archCheck -ParchFast=true` | 실패 원문이 에이전트에게 돌아간다. 수정은 되돌리지 않는다 |
| 응답 종료 직전 | `./gradlew build archCheck` | 첫 실패는 응답 종료를 막는다. 고친 뒤에도 실패하면 반복을 멈추고 사람에게 알린다 |

실패 로그 전체는 `build/hook-logs/`에 남는다. 마지막 성공 뒤 검사 범위가 그대로면 Stop 검사를 생략한다.

## 로컬 AI 리뷰

```sh
python3 tools/ee_review.py              # origin/main(없으면 main) 대비 작업 트리 변경을 리뷰
python3 tools/ee_review.py --dry-run    # 모델 호출 없이 규칙·변경 줄만 확인
python3 tools/ee_review.py --base develop --rules-from worktree
```

- 규칙은 기준 커밋의 REVIEW.md에서 `active: true`인 것만 쓴다. 규칙을 고치는 중이면 `--rules-from worktree`로 시험한다.
- 커밋하지 않은 변경과 추적하지 않는 새 파일도 대상이다. 변경이 1,000줄 또는 50파일을 넘으면 생략한다.
- 모델은 `Read`, `Glob`, `Grep`만 쓰고 훅은 꺼진 상태로 돈다. 지적은 규칙·변경 줄·마커 검사를 통과한 것만 보이며 GitHub에 쓰지 않는다.
- 결과는 `build/reports/ee/local-review.json`에도 남는다. 지적은 참고 정보이며 병합 조건이 아니다.
- 모델 호출은 Claude Code 계정의 사용량을 쓴다.
