# CI/CD 및 Dependabot

## Workflow

[.github/workflows/build-logreedy.yaml](../.github/workflows/build-logreedy.yaml)의 이름은 **logreedy - Java CI**다. Infrastructure의 소문자·하이픈 파일명, 영어 Step 이름, `verify`/`publish` Job과 SHA 옆 버전 주석 관례를 따른다.

| 이벤트 | verify | publish |
| --- | --- | --- |
| main 대상 PR opened/synchronize/reopened/ready_for_review | Ubuntu + Windows | 실행하지 않음 |
| main Push | Ubuntu + Windows | 두 검증이 모두 성공한 후 실행 |
| workflow_dispatch | Ubuntu + Windows | 실행하지 않음 |
| 작업 브랜치 Push / Tag Push | 트리거 없음 | 트리거 없음 |

`publish`는 `needs: verify`와 명시적인 main Push 조건을 모두 요구한다. 배포 Python 진입점도 GitHub Actions의 main Push만 허용한다. PR에 쓰기 토큰을 제공하는 `pull_request_target`은 사용하지 않는다.

전체 기본 권한은 `contents: read`, publish Job만 `contents: write`다. 추가 PAT 없이 기본 `GITHUB_TOKEN`을 사용한다. Checkout은 `persist-credentials: false`이며 publish는 정확한 `github.sha`를 Checkout한다. PR 캐시는 읽기 전용이고 배포 토큰은 publish의 마지막 Step에만 전달한다.

검증 동시성은 PR/브랜치·OS별이다. PR의 오래된 검증은 취소할 수 있으며 main 검증은 진행 중 취소하지 않는다. Release는 별도 `logreedy-publish` 그룹에서 `cancel-in-progress: false`로 실행한다. GitHub Concurrency는 실행 중 1개·대기 1개만 유지하므로 빠른 연속 Push에서 오래된 대기 Job이 대체될 수 있다. 모든 중간 커밋의 Release를 발행하는 큐라고 간주하지 않는다.

## 빌드와 Runtime 검사

setup-java로 Temurin 8, 11, 17, 21을 준비한 뒤 **마지막에 25**를 활성화한다. Gradle은 항상 JDK 25에서 실행한다. VS Code의 `LoGreedy: build`와 같은 Wrapper `build` Task를 사용하며 CI에서는 깨끗한 상태를 위해 `clean`을 먼저 수행한다.

1. Checkout과 Gradle Wrapper 공식 체크섬 검증
2. `./gradlew clean build --no-daemon`: 기존 검사와 Windows Glob 회귀 JUnit 32개, 별도 CLI 5개, 패키징·JAR별 CLI·클래스 버전 검사
3. `python -m unittest discover -s scripts/tests -v`: Release 상태 전이·충돌·중단 복구·체크섬·게시 조건 검사. 실제 API에 쓰지 않음
4. `python scripts/verify-runtime.py --require-versions 8,11,17,21,25`: Legacy 8/11/17/21/25, Modern 21/25에서 CLI 및 Append·멀티라인 → HTTP Mock 전송, 순서, WAL 복구
5. `python scripts/artifacts.py --prepare`: Manifest/버전/실행 Main·클래스 버전·외부 런타임 의존성 경계 검증, 실제 세 파일로 SHA256SUMS 생성
6. JUnit 실패는 Job Annotation에 이름·원인(제한된 Stack)과 요약을 남기며, OS별 배포 Artifact 30일·테스트 결과 Artifact 14일 보관

현재 배포 JAR는 외부 런타임 의존성이 없다. 검증기는 `com/dalae37/logreedy` 밖의 클래스를 거부한다. 향후 의존성을 넣으면 Java 8 API·클래스 버전·배포 패키징을 검토하고 실제 Runtime 검사까지 통과시키도록 검증 정책을 명시적으로 변경해야 한다. 테스트 전용 JUnit/Gson은 배포하지 않는다.

Actions Artifact는 실행 재시도에서 같은 이름을 갱신할 수 있다. Release Asset의 불변 정책과는 별개다. publish는 다른 Run/PR에서 파일을 찾지 않고 **현재 Workflow Run의 동일 SHA Ubuntu Artifact**만 받는다. Windows 산출물도 검증·보관하지만 공개 Release에는 Ubuntu 산출물을 사용한다.

Windows에서 `Process.destroy`/Python terminate는 JVM의 정상 종료 신호가 아니라 강제 종료다. 해당 하위 프로세스 검사는 WAL 재생·잠금 해제를 확인한다. 정상 종료는 양쪽 OS의 in-process `Agent.Stop()` 검사로 검증하고, UNIX의 별도 프로세스에서는 Graceful shutdown 로그도 확인한다. Sparse File 검사는 Java NIO의 SPARSE 생성 옵션을 사용한다. 실제 Windows Runner 성공 여부는 PR의 Checks에서 확인하며 로컬 Linux 성공과 혼동하지 않는다.

## Action 고정

2026-10-26에 공식 저장소 Tag와 전체 Commit을 Git 읽기로 대조하고 action.yml을 확인했다. Node 24 기반의 안정 버전을 사용하므로 최신 GitHub-hosted Runner를 사용한다.

| Action | 버전 | 고정 Commit |
| --- | --- | --- |
| actions/checkout | v7.0.1 | 3d3c42e5aac5ba805825da76410c181273ba90b1 |
| actions/setup-java | v5.7.0 | b6effb05e454b25005698d916606bdc6ffcbf961 |
| gradle/actions (wrapper-validation, setup-gradle) | v5.0.2 | 0723195856401067f7a2779048b490ace7a47d7c |
| actions/upload-artifact | v7.0.2 | cf430e030ddbb5b0abf93d22962f4752f3646cd9 |
| actions/download-artifact | v8.0.2 | 9000827ccba6bdab643e8b6fd33ac0654aef8333 |

actionlint 1.7.12를 공식 체크섬으로 확인한 뒤 로컬 Workflow 검증에 사용했다. SHA 유효성·YAML 정적 검증은 실제 GitHub Job 실행 성공을 뜻하지 않는다.

## Release 조건과 Asset

프로젝트 버전은 `gradle.properties`의 `version` 하나로 관리한다. 중복 값, 선행 0, `v` 접두어, RC 문자열은 허용하지 않고 `MAJOR.MINOR.PATCH`만 허용한다. 첫 버전은 0.1.0, Tag는 **v0.1.0**, Release 이름은 **LoGreedy v0.1.0**이다. 0.1.1, 0.2.0, 1.0.0도 동일 경로로 처리한다.

`publish`는 검증 Artifact를 다시 검사하고, `scripts/release.py`가 다음 순서로 처리한다.

1. 이미 게시된 동일 버전이면 기존 Tag가 main 이력에 있고 필수 Asset·기존 체크섬·JAR가 정상인지 확인한 뒤 **중복 발행하지 않는다**. 새 main의 바이너리와 기존 Asset을 비교해 덮어쓰지 않는다.
2. 신규 발행이면 검증 SHA가 main 이력에 있어야 한다. 아직 Tag/Draft가 없으면 현재 main tip도 같아야 한다. 더 최신 main이 있으면 그 검증을 기다리며 실패 사유를 남긴다.
3. 이전 게시 버전보다 증가했는지 확인한다. `refs/tags/v<version>`을 정확한 검증 SHA로 생성한다. 다른 SHA의 기존 Tag는 거부한다. Annotated Tag도 Commit까지 해석한다.
4. 한국어 [Release Notes 템플릿](RELEASE_NOTES.md)에 버전을 넣어 Draft를 만든다. Draft에는 정확한 Commit을 나타내는 관리용 Marker를 넣는다.
5. 누락된 Asset만 업로드한다. 이미 있는 파일은 삭제/교체하지 않는다. 네 파일을 다시 다운로드해 바이트·크기·SHA-256(제공 시 GitHub digest 포함)을 확인한다.
6. Tag가 여전히 같은 SHA인지 재확인하고 Draft를 공개한다.

첨부 파일은 다음 네 개다.

```text
LoGreedy-jdk8.jar
LoGreedy-jdk21.jar
LoGreedy.properties
SHA256SUMS
```

SHA256SUMS는 두 JAR와 Properties의 실제 바이트로 생성하며 자기 자신의 해시는 포함하지 않는다. 다운로드 무결성 확인은 UNIX에서 아래와 같이 한다.

```shell
sha256sum --check SHA256SUMS
```

Windows PowerShell에서는 `Get-FileHash .\LoGreedy-jdk8.jar -Algorithm SHA256` 등으로 세 파일을 계산해 SHA256SUMS와 비교한다. 저장소에서 해당 버전을 Checkout했다면 배포 파일을 `build/distributions/all`에 두고 `python scripts/artifacts.py`로 세 파일·Manifest·클래스 버전까지 확인할 수 있다.

## 중단과 충돌 복구

| 상태 | 자동 처리 / 운영 조치 |
| --- | --- |
| 같은 버전의 정상 공개 Release | 검증 후 skip. Dependabot 병합으로 다시 만들지 않음 |
| Tag만 있고 정확한 검증 SHA와 같음 | 같은 원래 Push Run에서 Draft 생성 후 계속 진행 |
| Tag가 다른 SHA | 실패. Tag를 이동하거나 강제로 삭제하지 않음 |
| 이 스크립트의 같은 SHA Draft, 일부 Asset 누락 | 원래 Run의 실패한 publish를 재실행해 누락 파일만 업로드 |
| Draft의 기존 Asset 바이트가 다름 | 실패·보존. 운영자가 원래 Artifact와 대조해 수동 검토 |
| 수동 생성 Draft 또는 다른 SHA Marker | 자동 인수하지 않고 실패. 소유권·대상 Commit 검토 |
| 이미 공개한 Release의 Asset 누락/손상 | 자동 덮어쓰기 금지. 기존 배포 소비자 영향과 파일 출처를 검토해 수동 복구 |
| 업로드/게시 응답이 유실됨 | 원래 실패 Job 재실행. 서버 상태를 다시 조회해 중복 생성 방지 |

재시도는 **원래 main Push Run의 실패 Job**에서 수행한다. Tag가 가리키는 SHA와 다른 새 커밋으로 복구를 시도하지 않는다. Artifact가 만료됐다면 원래 검증 커밋의 CI를 다시 실행하고 산출물 동일성을 확인한다. JDK 패치 변경 등으로 기존 Draft 파일과 달라지면 자동으로 교체하지 않는다. 최초 발행 전이든 복구든 사용자가 임의 Tag/Release를 미리 만들 필요는 없다.

공식 API 외 업로드 Endpoint를 허용하지 않는다. Asset 다운로드 리다이렉트는 HTTPS의 알려진 GitHub 호스트만 허용하고 Authorization을 전달하지 않는다. 통신 오류는 기록을 보존한 채 실패하며 같은 쓰기 요청을 무조건 반복하지 않는다.

## Dependabot

[.github/dependabot.yml](../.github/dependabot.yml)은 Gradle와 GitHub Actions를 각각 `/`에서 감시한다. 의존성과 Plugin은 루트 build.gradle에 선언되어 있고 모든 하위 모듈을 루트 settings.gradle에서 포함하므로 단일 루트 항목으로 현재 멀티모듈을 포괄한다. 별도 독립 빌드가 생기면 directories 확장을 검토한다.

두 항목 모두 매주 월요일 **09:00 Asia/Seoul**, 열린 PR 최대 5개다. main에 설정이 들어간 뒤 GitHub가 업데이트 PR을 생성한다. 기본 `Bump ...` Commit은 Infrastructure에서 확인된 Dependabot 메시지와 같으므로 별도 Conventional Commit 접두어를 강제하지 않는다. Action은 40자리 SHA와 버전 주석을 함께 유지하고 업데이트 PR에서도 공식 버전·Commit을 검토한다.

자동 병합 Workflow는 없으며 Repository Settings의 Allow auto-merge도 끈다. Major 업데이트를 자동 승인하지 않는다. Action Runtime/Runner 요구사항, Gradle의 JDK 25 실행 지원, Java 8/21 호환성, 라이선스·API 변경을 검토하고 같은 CI를 통과시킨다. 프로젝트 버전이 그대로인 의존성 업데이트는 기존 Release를 재발행하지 않는다. 배포가 필요하면 별도 PR에서 프로젝트 버전을 올린다.

## GitHub Settings와 병합 순서

저장소 관리자가 다음을 확인한다. 로컬 파일만으로 설정 완료를 주장하지 않는다.

* **Actions > General:** Actions 허용, 고정한 공식 Actions 사용 허용, GitHub-hosted Ubuntu/Windows Runner 사용 가능. Workflow permissions 정책에서 publish Job의 contents: write가 허용되어야 한다. 기본 토큰 권한은 Read를 유지한다. Actions의 PR 생성·승인 권한은 이 Workflow에 필요하지 않다.
* **General > Pull Requests:** Allow auto-merge 비활성화. 별도 GitHub App/Ruleset 자동 병합도 검토한다.
* **Rules > Rulesets / Branch protection:** main 직접 Push/Force Push 제한, PR 리뷰와 `verify (ubuntu-latest)`, `verify (windows-latest)` 성공을 필수로 설정한다. 첫 실행 후 실제 Check 이름을 선택한다. 관리자 우회 정책도 확인한다.
* **Tag Ruleset:** `v*` Tag의 삭제·갱신을 제한하고 이 Workflow의 최초 Tag 생성에 필요한 정책을 허용한다. 조직 정책에 막히면 PAT를 추가하는 대신 해당 정책을 검토한다.
* **Code security / Advanced Security 또는 Security & analysis:** Dependabot Alerts와 Dependabot Security Updates 활성화. 메뉴 이름은 GitHub 계정/플랜에 따라 다를 수 있다. 주간 Version Updates와 Security Updates는 별도 기능이다.
* **Actions PR 승인:** 외부 기여자·새 Workflow의 실행 승인이 필요하면 관리자가 검토 후 승인한다. Dependabot PR에는 쓰기 권한이나 배포 비밀을 추가하지 않는다.

PR의 두 OS 검증 결과와 변경 내용을 확인한 뒤 사용자가 병합한다. 이후 main Push의 verify가 다시 모두 통과하면 publish가 첫 Release를 만든다. 이번 개발 작업에서는 main을 병합하거나 실제 Tag/Release를 미리 생성하지 않는다.

## 실제 검증과 미검증

로컬 Debian 13 Linux x86_64에서 JDK 25 전체 빌드·기존 JUnit·Python 배포 상태 전이 검사·actionlint/YAML/Action SHA 검증을 수행한다. Java 8/11/17/21/25의 실제 Legacy 실행과 Java 21/25 Modern 실행도 로컬 Mock으로 확인한다. 상세 실행 버전과 결과는 [TESTING.md](TESTING.md)를 참고한다.

GitHub-hosted Ubuntu/Windows 실행, 저장소 보호·보안 설정, 실제 GitHub Release API 쓰기는 GitHub 결과가 나오기 전까지 미검증이다. 모의 API의 성공을 실제 Release 발행 성공으로 보고하지 않는다.
