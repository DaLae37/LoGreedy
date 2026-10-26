# LoGreedy

서버의 파일 로그를 수집하여 Grafana Loki로 전송하는 독립 Java Agent

현재 버전은 **0.1.0**입니다. 서버마다 한 프로세스를 실행하며, WebLogic·Tomcat·EAI·일반 애플리케이션의 파일 로그를 여러 Source로 감시합니다. `-javaagent`, WAS 코드 수정, 별도 Vector나 중앙 수집기가 필요하지 않습니다.

## 주요 기능

* **파일 수집** - Polling, 여러 파일·와일드카드, 신규 파일 발견, 파일별 long Byte Offset
* **로테이션 추적** - fileKey, 경로, 크기, 수정 시각, 제한된 지문으로 Rename·Replace·Truncate·Copytruncate·날짜별 파일 변경 감지
* **장애 복구** - fsync된 영속 Queue와 재생 가능한 Checkpoint, Queue Full 시 읽기 일시 정지
* **로그 이벤트** - UTF-8·EUC-KR·MS949 등 Charset, LF/CRLF, 문자 경계, 마지막 줄 Timeout, 정규식 멀티라인과 크기·줄 수 제한
* **Loki 전송** - HTTP Push API, HTTPS 검증, 환경 변수 인증, Gzip·Batch·Timeout·Backoff·Retry, 4xx 격리와 운영자 재전송
* **운영** - 설정 검증, Spool 중복 실행 잠금, Graceful Shutdown, 자체 로그와 통계

전송 목표는 **At-least-once**입니다. Loki 성공 후 로컬 ACK 전에 종료되면 중복될 수 있습니다. 원본 파일 삭제·Copytruncate 경합·식별 지문 충돌 등에서 완전한 무손실을 보장하지 않습니다. 상세 조건은 [아키텍처](docs/ARCHITECTURE.md)와 [운영 문서](docs/OPERATIONS.md)를 확인하세요.

## 개발 환경

* **개발 환경:** Codex Cloud
* **IDE:** VS Code — Extension Pack for Java, Gradle for Java
* **빌드 JDK:** 25
* **Build Tool:** Gradle 9.8.2, Groovy DSL, Gradle Wrapper
* **기본 패키지:** `com.dalae37.logreedy`
* **외부 실행 의존성:** 없음. JUnit/Gson은 테스트에서만 사용

Windows, Linux, AIX, Solaris, HP-UX를 지원 목표로 Java API만 사용하며 JNI/JNA를 사용하지 않습니다. 실제 검증은 **Debian 13 Linux x86_64**에서 수행했습니다. 다른 OS의 파일 시스템·잠금·원자적 Rename 동작은 아직 실기 검증하지 않았습니다.

## Java 8 / 21 구분

| 구분 | 컴파일 대상 | 클래스 버전 | 실행 파일 |
| --- | --- | --- | --- |
| Core | `--release 8` | 52 | 공통 수집·Queue·전송 로직 |
| Legacy | `--release 8` | 52 | `LoGreedy-jdk8.jar`, 제한된 Platform Thread Executor |
| Modern | `--release 21` | 65 | `LoGreedy-jdk21.jar`, Virtual Thread와 Semaphore |

Modern JAR의 Core 클래스는 52입니다. 한 전송 작업자가 순서를 유지하므로 실제 전송 동시성은 1이며 `send.concurrency`는 Executor 상한입니다. JDK 25에서도 Modern JAR를 실행할 수 있습니다. 실제 검증 JVM은 Temurin 8u472, Debian OpenJDK 21.0.12.1, Oracle JDK 25.0.2입니다.

## 빌드 및 테스트

`JAVA_HOME`을 JDK 25 설치 디렉터리로 지정하고 루트에서 실행합니다. 빌드에는 JDK 25 하나만 필요하며 Wrapper 배포본 SHA-256을 검증합니다.

```shell
java -version
./gradlew --version
./gradlew clean build
./gradlew legacy
./gradlew modern
```

Windows에서는 `gradlew.bat clean build`처럼 같은 인수를 사용합니다. `build` 또는 `dist`는 `build/distributions/all/`에 다음 파일을 생성합니다.

```text
LoGreedy-jdk8.jar
LoGreedy-jdk21.jar
LoGreedy.properties
```

`legacy`, `modern`은 각각 `build/distributions/legacy/`, `modern/`에 해당 JAR와 설정을 생성합니다. 기본 테스트는 실제 WAS·Loki·Docker 없이 동작합니다. [테스트 문서](docs/TESTING.md)에 테스트 목록과 Java 8/21 추가 실행 방법을 기록했습니다.

## 설정 및 실행 방법

1. [config/LoGreedy.properties](config/LoGreedy.properties)를 운영 경로에 복사합니다. 상대 경로는 **설정 파일 디렉터리**를 기준으로 해석합니다.
2. Agent ID, Source 경로·Charset, Spool·Checkpoint 경로, Loki URL을 수정합니다. 기존 파일 최초 위치는 기본 `end`이며 `begin`을 지정하면 처음부터 읽습니다.
3. 필요한 인증 헤더 전체를 `loki.auth.env`에 지정한 환경 변수로 제공합니다. 비밀값을 저장소에 넣지 않습니다.
4. 해당 서버 Java 버전에 맞춰 실행합니다.

```shell
java -jar LoGreedy-jdk8.jar --config /opt/logreedy/LoGreedy.properties
java -jar LoGreedy-jdk21.jar --config /opt/logreedy/LoGreedy.properties
java -jar LoGreedy-jdk21.jar --version
java -jar LoGreedy-jdk21.jar --help
```

옵션 없이 실행하면 현재 디렉터리의 `LoGreedy.properties`를 사용합니다. 저장소의 시작 스크립트는 기본적으로 `config/LoGreedy.properties`를 사용하며, 먼저 빌드해야 합니다.

```shell
./scripts/start.sh jdk8 --version
./scripts/start.sh jdk21 --config /opt/logreedy/LoGreedy.properties
```

Windows에서는 `scripts\start.bat jdk21 --config C:\LoGreedy\LoGreedy.properties`를 사용합니다. Ctrl+C 또는 UNIX의 SIGTERM으로 종료합니다. 수집하지 못한 미완성 바이트는 원본 파일에서 재시작 시 다시 읽습니다.

## VS Code 개발

1. [AGENTS.md](AGENTS.md)와 [스타일 가이드](docs/STYLE_GUIDE.md)를 먼저 읽습니다.
2. JDK 25의 `JAVA_HOME`을 설정한 터미널에서 `code .`로 루트를 엽니다. 사용자별 JDK 절대 경로를 설정 파일에 넣지 않습니다.
3. 권장 확장을 설치하고 Gradle 프로젝트를 가져옵니다.
4. `LoGreedy: build`, `LoGreedy: legacy`, `LoGreedy: modern` Task를 실행합니다. Task는 OS에 맞는 Wrapper를 사용합니다.
5. Run and Debug에서 `LoGreedy: jdk8` 또는 `LoGreedy: jdk21`을 선택합니다. 기본 인수는 `--help`이며 Agent 디버깅은 `--config config/LoGreedy.properties`로 변경합니다. 두 디버그 설정 모두 개발 JDK 25에서 실행합니다.

## 프로젝트 구조 및 문서

```text
logreedy-core/              # Java 8 수집, WAL, Checkpoint, Loki, JUnit 검사
logreedy-runtime-jdk8/      # Legacy 진입점
logreedy-runtime-jdk21/     # Modern 진입점과 Virtual Thread Executor
config/LoGreedy.properties # 전체 설정 예제
scripts/                   # Windows / UNIX 시작 명령
.vscode/                   # 확장, 설정, 빌드 Task, 디버깅
gradle/wrapper/             # 체크섬 고정 Wrapper
```

* [아키텍처](docs/ARCHITECTURE.md): 영속성, 복구, 파일 추적과 한계
* [설정](docs/CONFIGURATION.md): 전체 Properties와 예제
* [운영](docs/OPERATIONS.md): 시작·종료·용량·장애·격리 복구
* [테스트](docs/TESTING.md): 검증 범위와 실제 JVM/OS

## GitHub Actions 및 업데이트

[logreedy - Java CI](https://github.com/DaLae37/LoGreedy/actions/workflows/build-logreedy.yaml)는 main 대상 PR과 main Push에서 Ubuntu·Windows 빌드 및 테스트를 수행합니다. Gradle은 JDK 25에서 실행하고, 별도로 Legacy를 Java 8/11/17/21/25, Modern을 Java 21/25에서 검사합니다. 수동 실행은 CI만 수행하며 PR에서는 Tag/Release를 만들지 않습니다.

Dependabot은 Gradle 의존성·Plugin과 GitHub Actions를 매주 월요일 09:00(Asia/Seoul)에 확인해 업데이트 PR을 만듭니다. 자동 병합하지 않으며 Major와 Java 호환성·Action SHA를 검토합니다. Security Alerts/Updates는 저장소 Settings에서 별도 활성화합니다.

## 다운로드 및 배포

[GitHub Releases](https://github.com/DaLae37/LoGreedy/releases/latest)에서 두 JAR, `LoGreedy.properties`, `SHA256SUMS`를 받습니다. 첫 Release는 이 PR을 사용자가 main에 병합한 뒤 CI가 모두 성공하면 **LoGreedy v0.1.0** / **v0.1.0**으로 자동 생성됩니다. 현재 작업에서 미리 발행하지 않습니다.

```shell
sha256sum --check SHA256SUMS
```

Windows에서는 `Get-FileHash .\LoGreedy-jdk8.jar -Algorithm SHA256` 등으로 세 배포 파일의 해시를 SHA256SUMS와 비교합니다.

배포가 필요하면 `gradle.properties`의 버전을 올린 PR을 검증·병합합니다. main 검증 후 정확한 커밋의 Artifact를 Draft에 올리고, 원격 파일과 체크섬을 확인한 뒤 게시합니다. 동일 버전의 Dependabot 업데이트는 기존 Release를 재발행하지 않습니다. Tag/Release/Asset을 강제로 덮어쓰지 않습니다. 권한, 저장소 Settings, 중단 복구 절차는 [CI/CD 문서](docs/CI-CD.md)를 확인하세요.

## 라이선스 (License)

[BSD 3-Clause License](LICENSE)

## 개발

이 프로젝트는 OpenAI Codex를 활용하여 개발되었습니다.
