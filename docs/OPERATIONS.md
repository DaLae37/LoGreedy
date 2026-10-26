# 운영

## 배치와 시작

서버마다 하나의 프로세스와 전용 Spool을 사용한다. 소스 파일은 읽기 권한만 부여하고, Spool·Checkpoint·자체 로그는 Agent 계정만 수정할 수 있게 한다. 디스크·파일 시스템은 파일 fsync와 원자적 Replace를 지원해야 한다. NFS 등 공유 Spool은 지원 대상으로 검증하지 않았다.

JAR와 `LoGreedy.properties`를 운영 디렉터리에 두고 [설정 문서](CONFIGURATION.md)를 적용한다. Heap은 예를 들어 `-Xms32m -Xmx512m`부터 시작해 최대 Queue 부하로 측정한다. 기본 Spool은 디스크 전용 저장 구조가 아니라 메모리에도 대기 이벤트를 유지한다. 서버당 여러 Source를 한 설정에 넣으며 WAS를 변경하지 않는다.

```shell
java -Xms32m -Xmx512m -jar LoGreedy-jdk8.jar --config /opt/logreedy/LoGreedy.properties
java -Xms32m -Xmx512m -jar LoGreedy-jdk21.jar --config /opt/logreedy/LoGreedy.properties
```

저장소에서는 빌드 후 `scripts/start.sh jdk21 --config ...`, Windows에서는 `scripts\start.bat jdk21 --config ...`를 사용한다. 시작 스크립트는 JAVA_HOME과 신뢰하는 JAVA_OPTS를 사용하고, 경로의 공백을 인용한다. 백그라운드화, 서비스 등록, 인증 정보 입력은 수행하지 않는다. 운영 서비스 관리자에서 재시작 정책과 제한된 환경 변수를 관리한다.

Spool의 `agent.lock`에 OS FileLock을 잡는다. 파일이 남아 있다는 사실은 실행 중이라는 뜻이 아니며 강제 종료 후 OS가 잠금을 해제한다. 같은 Spool의 중복 프로세스는 종료 코드 4다. 서로 다른 Spool을 사용하면 기술적으로 여러 프로세스를 시작할 수 있으므로 서버 운영 정책으로 하나만 등록한다. 설정 파일/Agent ID만으로 서버 전체를 독점하는 전역 잠금은 아니다.

## 종료와 종료 코드

Ctrl+C 또는 SIGTERM은 읽기를 중지하고 완성 가능한 마지막 줄·멀티라인을 Queue에 기록한 후 제한 시간 동안 전송한다. Timeout 후에도 남은 이벤트는 WAL에 유지한다. UTF-8 문자의 일부만 들어온 경우 Commit하지 않으며 원본이 남아 있으면 재시작 시 다시 읽는다. 기본 Poll이 길어도 종료 신호는 대기에서 즉시 깨어나게 한다.

| 코드 | 의미 |
| --- | --- |
| 0 | 도움말/버전/상태/재전송 준비 또는 정상 Run 종료. 전송 대기 이벤트가 0이라는 뜻은 아님 |
| 2 | 잘못된 CLI 또는 설정 |
| 3 | 저장소·전송 작업자·실행 오류. Spool 보존 후 원인 해결 |
| 4 | 동일 Spool 잠금 충돌 |

UNIX의 SIGTERM 종료 상태는 JVM/OS 관례에 따라 143 등 신호 종료 코드가 될 수 있다. 테스트는 종료 완료, 로그의 Graceful shutdown, 잠금 해제, Queue 복구를 함께 확인한다. SIGKILL·강제 프로세스 종료·전원 차단은 Graceful Shutdown을 실행하지 않는다.

## 통계와 자체 로그

기본 자체 로그는 `agent.log`, 회전 파일은 `.1`, `.2`이며 각 파일은 약 1MB 한도다. LogManager의 JVM 종료 Hook에 의존하지 않고 종료 완료 기록까지 직접 쓴다. INFO에는 시작·종료와 30초 간격 `readBytes`, `queued`, `sent`, `pending`, `quarantined`, `retries`, `backpressure`, `spoolBytes`가 나온다. 프로세스 누계는 재시작 시 초기화되고 대기·격리·Cursor는 영속 상태에서 복구된다.

Source가 자체 로그 디렉터리를 포함해도 자체 로그 접두어, Checkpoint와 임시 파일, Spool을 제외한다. 이러한 보호 경로를 서로 겹치게 설정하거나 다른 애플리케이션 로그 이름과 공유하지 않는다. HTTP 응답 본문이나 Authorization 값을 진단에 출력하지 않는다. 오류 유형·HTTP 코드·격리 이벤트 ID를 제공한다.

## Backpressure와 디스크 부족

`spool.max.bytes`의 절반에서 제어 레코드 여유를 뺀 범위가 현재 WAL 이용 한도다. 나머지는 Compaction을 위한 예산이다. ACK 후 새 스냅샷으로 정리한다. Checkpoint 임시 파일, 자체 로그와 파일 시스템 메타데이터를 위한 추가 여유도 확보한다.

Queue Full이면 파일 읽기를 멈추고 Pending/격리 상태가 변할 때 재개한다. WAS에는 어떠한 쓰기 제한도 전달하지 않는다. 따라서 WAS는 계속 로그를 기록하고, 원본 보존 용량·기간은 별도로 충분해야 한다. 격리 이벤트도 용량에 포함되므로 인증 오류 등이 오래 지속되면 읽기가 정지할 수 있다.

실제 디스크 I/O 오류는 실패한 저널 뒤에 계속 기록하지 않고 Agent를 종료한다. 공간·권한·파일 시스템을 복구한 뒤 같은 설정으로 재시작한다. CRC가 맞는 레코드까지 재생하고 부분 마지막 레코드만 버린다. WAL 전체를 지워서 공간을 만드는 것은 미전송 로그를 잃는 조치이므로 복구 절차로 사용하지 않는다. Spool 상한은 충분히 배출한 뒤 줄이고, 파일 수 상한도 기존 Cursor보다 작게 줄이지 않는다.

## Loki 오류와 격리 복구

429·408·5xx·연결/응답 Timeout은 지수 Backoff로 재시도한다. 응답 성공 후에만 ACK한다. 인증 실패, 잘못된 Payload, 오래된 Timestamp 등 영구 오류는 개별 이벤트로 확인해 WAL에 격리한다. 격리 원인 상태 코드는 자체 로그에서 확인한다.

1. Agent를 정상 종료한다.
2. URL, Tenant, 인증 환경 변수, 신뢰 CA, Loki Timestamp/Metadata 정책을 수정한다.
3. 상태를 확인하고 격리 이벤트를 다시 Pending으로 옮긴다.

```shell
java -jar LoGreedy-jdk21.jar --status --config /opt/logreedy/LoGreedy.properties
java -jar LoGreedy-jdk21.jar --replay-quarantine --config /opt/logreedy/LoGreedy.properties
java -jar LoGreedy-jdk21.jar --config /opt/logreedy/LoGreedy.properties
```

상태/재전송 명령도 Spool 잠금을 요구하므로 실행 중인 Agent와 동시에 사용하지 않는다. 격리 재전송은 Cursor를 뒤로 돌리지 않고 전송 Timestamp를 새 단조 증가 시각으로 갱신한다. 원문과 추출한 Timestamp는 유지한다. 이미 보낸 후속 로그보다 늦게 도착하므로 수동 재전송으로 원래 파일의 시간 순서를 복원하지는 못한다. 잘못된 설정을 고치지 않고 반복 재전송하면 다시 격리된다.

## 복구·보존·업그레이드

백업은 정상 종료 후 Spool 전체와 설정·Checkpoint를 함께 복사한다. WAL이 기준이고 Checkpoint는 재생성할 수 있다. 복구 시 단일 Spool 백업 세트를 사용한다. 서로 다른 시점의 WAL 파일을 섞지 않는다. 원본에 남은 미완성 바이트는 백업 WAL에 없으므로 원본 로그도 충분히 보존한다.

완전 레코드 CRC 손상·지원하지 않는 형식·Atomic Move 실패 시 원본을 보존하고 저장소/파일 시스템 문제를 조사한다. 자동으로 손상 이벤트를 건너뛰지 않는다. 기존 설정의 Source 이름·Charset·경로를 변경하면 Cursor 의미가 달라질 수 있으므로 배출·백업 후 별도 마이그레이션 계획을 세운다. 현재 형식 버전은 1이며 다른 버전의 호환을 주장하지 않는다.

## 지원 목표와 검증 한계

실제 검증 OS는 Debian 13 Linux x86_64와 GitHub-hosted Ubuntu/Windows다. AIX, Solaris, HP-UX는 Java API로 고려한 지원 목표다. 각 OS에서 사용 가능한 Java 8/21 배포판, EUC-KR/MS949 Charset, FileLock, fileKey null, Atomic Move, Directory fsync 정책을 배치 전에 확인해야 한다. Windows 배치·서비스 실행, AIX/Solaris/HP-UX 실기, 실제 NTFS/WAS/Loki 운영 부하 및 전원 차단은 검증하지 않았다.

원본 삭제, Copytruncate 경합, 동일 지문 충돌, 탐색 범위 밖 이동, 한도 초과, 정리된 세대의 늦은 쓰기는 [아키텍처의 복구 한계](ARCHITECTURE.md)를 따른다. 상태형 Charset/BOM 자동 판별 인코딩의 중간 Offset 재시작도 검증·보장 범위가 아니다. 운영에서는 UTF-8, EUC-KR, MS949 또는 명시적 Endian의 상태 없는 Charset을 사용한다.
