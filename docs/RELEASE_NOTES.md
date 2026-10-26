# LoGreedy v{{VERSION}}

서버의 파일 로그를 수집하여 Grafana Loki로 전송하는 독립 Java Agent

## 주요 기능

* 여러 Source와 Glob 기반 파일 발견, long Byte Offset, 제한된 Buffer/Polling
* 파일 로테이션 추적, 영속 Queue와 Checkpoint, Backpressure 및 장애 복구
* UTF-8·EUC-KR·MS949, 정규식 멀티라인, 로그 필드 추출
* Loki HTTP Push, HTTPS 검증, Gzip/Batch, Retry, 영구 오류 격리
* Graceful Shutdown, 중복 실행 잠금, 자체 로그와 통계

## 다운로드 및 실행

* **LoGreedy-jdk8.jar:** Java 8 이상, Platform Thread Runtime
* **LoGreedy-jdk21.jar:** Java 21 이상, Virtual Thread Runtime
* **LoGreedy.properties:** 운영 경로·Loki·인증 환경 변수를 수정할 설정 예제
* **SHA256SUMS:** 세 배포 파일의 SHA-256

```shell
sha256sum --check SHA256SUMS
java -jar LoGreedy-jdk21.jar --config LoGreedy.properties
```

빌드는 JDK 25와 Gradle Wrapper를 사용합니다. GitHub Actions의 Ubuntu/Windows 검증을 통과한 커밋의 Ubuntu 산출물을 첨부합니다. 공통/Legacy 클래스 버전은 52, Modern Runtime은 65입니다. Legacy는 Java 8/11/17/21/25, Modern은 Java 21/25에서 CLI 및 파일 → Mock 전송 검사를 수행합니다.

## 운영 시 확인 사항

전송 보장은 At-least-once이며 중복될 수 있습니다. 원본 삭제·Copytruncate 경합·식별 지문 충돌에서 완전한 무손실을 보장하지 않습니다. AIX/Solaris/HP-UX와 실제 WAS/Loki 운영 부하는 미검증입니다. Windows의 강제 프로세스 종료와 정상 종료를 구분하며, 상태형 Charset의 중간 Offset 재시작은 보장 범위가 아닙니다. Queue는 대기 이벤트를 메모리에도 유지하므로 Heap과 Spool 한도를 함께 설정하세요.

설정·보존 기간·복구 한계는 저장소의 README와 docs/OPERATIONS.md를 확인하세요.
