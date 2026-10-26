# 테스트와 검증

## 기본 실행

빌드와 JUnit 실행은 JDK 25에서 한다. 운영 WAS, 실제 Loki, Docker는 필요하지 않다. JUnit 4.13.2와 Gson 2.13.2는 Maven Central에서 받는 테스트 전용 의존성이다. Core/Runtime 배포 JAR에는 들어가지 않는다. 테스트 소스만 JDK 25로 컴파일하고 배포 Core는 `--release 8`을 유지한다.

```shell
./gradlew --version
./gradlew clean build
./gradlew :logreedy-core:test
./gradlew verifyArtifacts
```

Gradle `test`는 JUnit 검사, 별도 `cliTest`는 CLI 검사 5개, `verifyArtifacts`는 두 JAR의 모든 클래스 버전과 JAR별 도움말·버전·잘못된 인수 3개를 검사한다. Runtime 모듈 `test NO-SOURCE`를 테스트 통과 개수로 세지 않는다. `CliTest` JavaExec 결과도 JUnit 개수와 구분한다.

## 추가 Java 8/21 런타임 검사

기본 빌드는 JDK 25만으로 가능하다. 실제 이전 JVM 검사를 원하면 각 런타임 경로를 환경 변수로 제공한다. JDK 8을 빌드에 사용하는 것이 아니다.

```shell
export LOGREEDY_JAVA8_HOME=/path/to/java8-runtime
export LOGREEDY_JAVA21_HOME=/path/to/java21-runtime
./gradlew clean build
```

Windows에서는 같은 환경 변수와 `gradlew.bat`를 사용한다. 두 변수가 없으면 `AdditionalJava8And21RuntimeMatrix` 한 검사는 JUnit skipped로 보고한다. JDK 25에서 두 JAR를 비교하는 필수 검사는 그대로 실행한다. 위 변수를 지정한 이번 실제 실행에서는 이 검사도 수행했으며 skipped는 0이다.

## 검사 범위

| 테스트 | 실제 검증 내용 |
| --- | --- |
| QueueTest | fsync된 PUT/ACK와 재시작, 용량 초과 시 Commit 불변, 부분 기록 ENOSPC 주입, CRC 손상 시 실패, 중복 잠금 |
| ActualProcessHaltsAtDurabilityBoundaries | 자식 JVM의 Runtime.halt(77): PUT 부분/force 후, Checkpoint 부분/force 후, ACK 부분/force 후, Compaction 부분 기록 |
| LokiAcceptedBeforeLocalAckMayDuplicateButNeverLose | Mock 성공 응답 직후 halt, 재시작 후 동일 이벤트 재전송·중복 확인 |
| QuarantineReplayNeverRewindsLatestCheckpointAndSurvivesRestart | 격리 재전송 영속성, 최신 Cursor 유지, 새 단조 증가 Timestamp |
| TailerTest | 다중 파일·독립 Offset, 최초 end와 신규 begin, 10GB 초과 Sparse File의 long Offset과 5바이트만 읽기 |
| Rotation 검사 | Append, Rename 후 늦은 Append, Replace, Truncate, Copytruncate 재성장, 삭제/재생성, fileKey null, 재시작 중 Rotation |
| DateBasedDiscovery 검사 | 날짜별 Glob 파일, 세대 정리와 Source 이력 재시작 |
| Source 핸들 검사 | Queue 기록/force 시점 원본 핸들 없음(Linux fd 관찰), 작은 줄 다수와 Buffer 잔여 처리 |
| Encoding 검사 | 256바이트 Buffer 경계 UTF-8/MS949/EUC-KR/UTF-16LE, CRLF, 불완전 UTF-8, 개행 없는 마지막 줄 |
| Multiline 검사 | Stack Trace, Timeout, 줄 수·바이트 제한, 미Commit 이벤트 재시작 |
| QueueFull 검사 | Full 이후 읽기 정지, ACK 후 재개, 원문 전체 비교 |
| LokiTest | JSON 파싱, Gzip, 추출 Metadata, 고정 Label, 시간 순서, 429/500/Timeout 재시도, 영구 400 격리 |
| TLS 검사 | 로컬 HttpsServer의 신뢰하지 않는 인증서 거부, 명시 Truststore로 성공. 인증서 검증 비활성화 없음 |
| Agent 통합 | 실제 파일 Append → Queue → HTTP Mock, 정상 Stop 반환 0, SIGTERM 후 종료 완료·잠금 해제·Queue 복구 |
| 배포판 비교 | JDK 25에서 실제 Legacy/Modern JAR 실행, 한국어·멀티라인 결과 일치 |
| 추가 런타임 | Java 8 Legacy와 Java 21 Modern에서 실제 파일 Append·Mock 전송·Graceful Shutdown |

Faults 인터페이스는 테스트 생성자에만 주입한다. 운영 Properties로 장애나 강제 종료 Hook을 활성화할 수 없다. 테스트의 ENOSPC는 IOException 주입이며 호스트 디스크를 실제로 가득 채우지 않았다. Sparse File은 논리 크기 10GB 이상이며 10GB를 실제 저장하거나 읽는 부하 검사가 아니다.

## 이번 실행 환경과 결과

2026-10-26, Debian GNU/Linux 13 (trixie), Linux x86_64에서 실행했다.

| JVM | 용도 |
| --- | --- |
| Oracle JDK 25.0.2+10 | Gradle 9.8.2, JUnit, 두 배포판 실행·비교 |
| Eclipse Temurin 1.8.0_472-b08 JRE | Legacy JAR 실제 파일 수집·Mock 전송·종료 |
| Debian OpenJDK 21.0.12.1+1 | Modern JAR 실제 파일 수집·Mock 전송·종료 |

JDK/JRE와 Wrapper는 공식 배포본 SHA-256을 확인한 후 사용했다. JDK 25만 선택하도록 Toolchain 자동 탐색을 끈 빌드도 검증한다. 클래스 버전은 JAR 전체를 검사하며 Legacy의 모든 클래스는 52, Modern의 Core는 52·Runtime은 65다.

이번 최종 실행은 **JUnit 31개 통과, 실패 0, 오류 0, skipped 0**, 별도 CLI 5개와 JAR별 실행 3개 통과다. `git diff --check`, README 마지막 Codex 문구, LICENSE 보존, VS Code 모듈/Main 연결도 확인했다.

최종 실행의 JUnit 수·실패·skipped는 `logreedy-core/build/test-results/test/TEST-*.xml`에서 확인하고, HTML은 `logreedy-core/build/reports/tests/test/index.html`에 생성된다. 결과 파일은 빌드 산출물이므로 커밋하지 않는다.

Windows VS Code 설정은 JSON·실제 모듈/Main·Task 연결을 정적으로 확인한다. Windows 배치/NTFS, AIX/Solaris/HP-UX, VS Code UI, 실제 Loki/WAS, 전원 차단 및 장기 성능 부하는 미검증이다. 이 테스트 결과로 전체 OS 운영 인증이나 Exactly-once를 주장하지 않는다.

## 3단계 CI/CD 검증

추가 Workflow는 Ubuntu/Windows 각각에서 같은 Gradle build Task와 전체 JUnit을 실행한다. Gradle의 실행 JVM은 항상 JDK 25다. Windows에서는 Process.destroy가 강제 종료이므로 패키징 하위 프로세스의 WAL 재생·잠금 해제를 검증하고, 정상 종료는 기존 in-process Agent.Stop 검사로 별도 확인한다. Sparse File 생성은 NIO CREATE_NEW/WRITE/SPARSE를 사용한다. 테스트 Timeout 여유와 손상 바이트 XOR도 실행 환경의 변동성을 고려해 보완했다. Agent 처리 코드는 3단계에서 변경하지 않았다.

```shell
python -m unittest discover -s scripts/tests -v
python scripts/verify-runtime.py --require-versions 8,11,17,21,25
python scripts/artifacts.py --prepare
python scripts/artifacts.py
```

Runtime 스크립트는 LOGREEDY_JAVA8_HOME, LOGREEDY_JAVA11_HOME, LOGREEDY_JAVA17_HOME, LOGREEDY_JAVA21_HOME, LOGREEDY_JAVA25_HOME을 사용한다. 25는 JAVA_HOME으로 대체할 수 있다. 기본 요구값은 25이며 CI는 다섯 JVM을 모두 요구한다. CLI 3개와 한국어·멀티라인·Append·고정 Label·시간 순서·Mock 전송·종료 후 WAL 복구를 각 조합에서 확인한다.

이번 로컬 추가 검증: Temurin JRE 11.0.29+7, 17.0.17+10을 공식 체크섬으로 확인했다. 기존 8u472/21.0.12.1/25.0.2와 함께 Legacy 5개, Modern 2개 조합이 통과했다. JDK 25 전체 build는 JUnit 31개(실패/오류/skip 0), CLI 5개, JAR별 실행 3개 및 모든 클래스 버전 검사를 통과했다. Python Release 자동화 16개가 통과했고 actionlint 1.7.12, Workflow YAML 정책·Dependabot 일정, 공식 Action Tag/전체 SHA 대조와 실제 Asset 체크섬도 확인했다.

Release 검사는 메모리 API로 신규 발행·같은 버전 skip·Tag만 존재·중단된 Draft·불일치 Asset·잘못된 버전·이동된 Tag·미완료 공개 Release·PR/수동 실행 차단·인증 헤더 없는 다운로드 리다이렉트를 검증한다. 실제 GitHub에 Tag/Release를 만들지 않는다. GitHub-hosted Ubuntu/Windows의 실행 결과는 별도이며, 결과를 얻기 전까지 미검증으로 보고한다. API/Push 접근이 막힌 경우 PR 생성 및 GitHub CI 결과도 미완료로 기록한다.
