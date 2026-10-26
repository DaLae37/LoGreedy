# 설정

단일 UTF-8 `LoGreedy.properties`를 사용한다. 원본 예제는 [config/LoGreedy.properties](../config/LoGreedy.properties)다. 경로는 설정 파일의 부모 기준이며 절대 경로도 가능하다. Windows 경로는 `C:/logs/*.log`처럼 `/` 사용을 권장한다. Properties에서 역슬래시는 이스케이프이므로 정규식의 `\d`는 `\\d`로 작성한다.

## Agent와 자원 한도

| Key | 기본값 | 설명 |
| --- | --- | --- |
| agent.id | logreedy | 낮은 Cardinality의 서버 Agent 식별자 |
| agent.host | 호스트명, 조회 실패 시 unknown-host | Host Label |
| agent.environment | default | Environment Label |
| agent.log.path | data/agent.log | 자체 로그. 1MB씩 현재 파일과 .1/.2 유지 |
| agent.log.level | INFO | Java Level 이름/값. INFO/WARNING/SEVERE 권장 |
| shutdown.timeout.ms | 15000 | 정상 종료 중 Queue 배출 제한 시간 |
| poll.ms | 1000 | 10~3600000ms |
| read.buffer.bytes | 65536 | 256~1048576 바이트 |
| read.max.bytes.per.file | 262144 | 파일당 Poll 한도. Buffer 이상, 최대 16MB |
| files.max | 128 | 파일 세대 및 Cursor 상한, 최대 4096 |
| scan.entries.max | 10000 | Source당 방문 항목 상한(파일+디렉터리) |
| scan.depth.max | 16 | 최대 탐색 깊이 |
| file.retire.ms | 60000 | 사라진 세대/Glob 밖 완료 세대 정리 유휴 시간 |
| event.max.bytes | 262144 | 바이트 범위별 분할. 최소 256, 최대 16MB |
| event.max.lines | 1000 | 이벤트 줄 수 상한 |
| spool.path | data/spool | WAL·Compaction·agent.lock 전용 디렉터리 |
| checkpoint.path | data/checkpoint/state.bin | WAL에서 재생성하는 스냅샷 |
| spool.max.bytes | 268435456 | WAL와 Compaction의 용량 예산. 실제 WAL 이용 한도는 절반 미만 |

Checkpoint와 자체 로그는 Spool 밖의 서로 다른 경로여야 한다. 자체 로그(회전 파일 포함), Checkpoint 임시 파일, Spool 내부는 Source에서 제외한다. 다른 프로세스가 이 경로를 쓰게 하지 않는다. 파일 시스템의 블록 할당·저널·Checkpoint·자체 로그는 Spool 바이트 한도와 별도로 디스크 여유가 필요하다.

이 구현은 대기 이벤트를 메모리에도 보관한다. 메모리는 Spool 한도와 `files.max × event.max.bytes`, Buffer, JSON/Gzip Batch 크기에 비례해 제한된다. 디스크만 늘려 무제한 Spool로 쓰지 않는다. 기본값에는 JVM Heap 512MB 이상에서 시작해 실제 최대 부하를 측정할 것을 권장한다. CPU 사용량은 읽기·탐색·Poll 한도로 조절하며 CPU 비율을 강제하는 스케줄러는 없다.

## Source

`sources=weblogic,tomcat,eai`처럼 쉼표로 구분한다. 이름은 영문·숫자·밑줄·하이픈을 사용하고 중복을 허용하지 않는다. 각 Source의 설정은 `source.<name>.*`다.

| Suffix | 기본값 | 설명 |
| --- | --- | --- |
| paths | 필수 | 여러 Glob을 세로줄 문자로 구분. 아래 예제 참조 |
| application | Source 이름 | Application Label |
| charset | UTF-8 | `Charset.forName`, 예: EUC-KR, MS949, UTF-16LE |
| initial | end | 최초 기존 파일 begin/end. 실행 후 신규 파일은 begin |
| multiline.start | 빈 값 | 빈 값이면 줄별 이벤트, 있으면 새 이벤트 시작 정규식 |
| multiline.timeout.ms | 2000 | 유휴 이벤트/마지막 줄 Flush. poll.ms 이상 |
| extract.regex | 빈 값 | 선택적 named group: timestamp, level, logger, message |

Glob은 Java PathMatcher 문법이다. `**`의 디렉터리 구분자 의미를 확인하고 루트 파일과 하위 파일을 모두 원하면 `logs/*.log|logs/**/*.log`처럼 적는다. Source를 중복 경로로 정의하면 의도적으로 각각 수집하므로 중복 전송된다. 심볼릭 링크 파일·디렉터리는 탐색하지 않는다. 탐색 한도를 넘으면 같은 앞부분만 반복 탐색할 수 있으므로 Glob을 좁힌다.

UTF-8, EUC-KR, MS949, UTF-16LE의 문자 경계는 검증했다. 다른 Java Charset도 지정할 수 있지만 상태를 줄 사이에 유지하는 인코딩이나 BOM 자동 판별 Charset의 중간 Offset 재시작은 보장하지 않는다. 운영에서는 상태 없는 Charset과 명시적인 Endian을 사용한다. 잘못된 바이트는 replacement 문자로 치환한다. 정규식은 신뢰하는 관리자가 작성하며, 이벤트 한도 안에서도 과도한 역추적을 유발하는 패턴은 피한다.

```properties
sources=was,eai
source.was.paths=/var/log/tomcat/*.log|/var/log/weblogic/*.out
source.was.application=portal
source.was.charset=UTF-8
source.was.initial=end
source.was.multiline.start=^\\d{4}-\\d{2}-\\d{2}
source.was.multiline.timeout.ms=2000
source.was.extract.regex=^(?<timestamp>\\S+) (?<level>\\S+) (?<logger>\\S+) (?<message>.*)
source.eai.paths=/opt/eai/log/*.log
source.eai.application=integration
source.eai.charset=MS949
source.eai.initial=begin
```

Timestamp 추출은 ISO Offset DateTime(예: `2026-10-26T12:00:00+09:00`)을 지원한다. 정규식 불일치·그룹 부재·시간 파싱 실패는 원문 수집을 막지 않는다. Level·Logger를 기본 Label로 승격하지 않는다.

## Loki

| Key | 기본값 | 설명 |
| --- | --- | --- |
| loki.url | http://127.0.0.1:3100/loki/api/v1/push | HTTP(S), 정확한 Push 경로. 예제 파일은 HTTPS 사용 |
| loki.allow.http | false | HTTP는 명시적 true 필요. Mock/신뢰 네트워크 한정 |
| loki.auth.env | 빈 값 | Authorization 헤더 전체를 담은 환경 변수 이름 |
| loki.tenant | 빈 값 | 선택적 X-Scope-OrgID |
| loki.tls.truststore.path | 빈 값 | 비어 있으면 JVM 기본 Truststore |
| loki.tls.truststore.type | JVM 기본 형식 | 예: PKCS12, JKS. 예제는 PKCS12 |
| loki.tls.truststore.password.env | 빈 값 | Truststore 암호 환경 변수. 비면 빈 암호 |
| loki.gzip | true | Gzip JSON 요청 |
| loki.batch.events | 100 | Batch 이벤트 수 상한 |
| loki.batch.bytes | 1048576 | JSON 크기의 보수적 추정 한도. 첫 이벤트 하나는 초과 허용 |
| loki.connect.timeout.ms | 5000 | 연결 Timeout |
| loki.read.timeout.ms | 10000 | 응답 Timeout |
| loki.retry.initial.ms | 1000 | 최초 Backoff |
| loki.retry.max.ms | 60000 | 최대 Backoff와 숫자 Retry-After 한도 |
| loki.timestamp.mode | ingest | ingest/parsed. 전송 시각은 전역 단조 증가 보정 |
| loki.structured.metadata | false | Loki의 Structured Metadata 지원 필요 |
| send.concurrency | 1 | 1~16, Executor 상한. 실제 Sender는 한 개 |

인증서 체인과 호스트명 검증을 끄는 설정은 없다. URL에 자격 증명을 넣지 않으며 리다이렉트를 따라가지 않는다. 토큰과 Truststore 암호는 설정 파일·명령행·자체 로그에 기록하지 않는다. 인증 예: `loki.auth.env=LOGREEDY_LOKI_AUTH`를 두고 운영 서비스 관리자의 보안 환경 설정으로 전체 `Bearer ...` 또는 `Basic ...` 헤더를 제공한다. Agent는 대화형 로그인을 하지 않는다.

원격 연결은 Java URLConnection 설정을 따른다. 프록시가 필요한 환경은 신뢰하는 `JAVA_OPTS`/`JAVA_TOOL_OPTIONS`의 JVM 프록시·CA 설정을 사용한다. `HTTPS_PROXY`만으로 Java가 자동 프록시를 사용한다고 가정하지 않는다.

잘못된 숫자·범위·Boolean·Charset·정규식·Source 목록·경로 중첩·URL은 시작 시 거부한다. 알 수 없는 Properties Key는 무시하므로 오타도 예제 및 이 표와 대조한다. 설정 Hot Reload는 구현하지 않았다. Source 이름·Charset·경로·환경 변경 시 기존 Cursor와 Queue의 의미를 확인하고 정상 종료 후 변경한다.
