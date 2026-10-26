# LoGreedy 개발 규칙

- 작업 시작 전 이 `AGENTS.md`와 [docs/STYLE_GUIDE.md](docs/STYLE_GUIDE.md)를 반드시 먼저 읽는다. 하위 지침과 충돌하면 범위를 확인한다.
- 기존 체크아웃과 작업 브랜치를 이어 사용한다. Codex Cloud 작업은 이미 격리되어 있으므로 사용자의 명시적 요청 없이 worktree를 만들지 않는다.
- 기본 패키지는 `com.dalae37.logreedy`, 버전은 `gradle.properties`의 `0.1.0`이다.
- JDK 25와 저장소의 Gradle Wrapper만 사용한다. Core/Legacy는 `--release 8`, Modern은 `--release 21`을 유지한다. 오래된 JDK 설치로 빌드 문제를 우회하지 않는다.
- 공통 기능은 Core에 구현한다. Runtime에 중복 구현하지 않는다. Java 8 API 경계와 클래스 버전 52/65를 유지한다.
- 클래스는 PascalCase, 자체 정의 메서드는 PascalCase, 변수는 camelCase, 상수는 UPPER_SNAKE_CASE를 사용한다. `main`, Java 표준 계약, override, 라이브러리 요구 이름은 원래 이름을 지킨다. 기존 코드의 오타나 혼재를 복제하지 않는다.
- UTF-8, 공백 4칸을 사용한다. JSON은 2칸, 배치 파일은 CRLF, 나머지 텍스트는 LF로 관리한다.
- README는 한국어로 작성한다. 마지막에는 반드시 `## 개발`과 `이 프로젝트는 OpenAI Codex를 활용하여 개발되었습니다.`를 두고, 그 뒤에 다른 섹션을 추가하지 않는다.
- LICENSE와 기존 커밋 기록을 보존한다. 별도 작업 브랜치를 사용하며, 사용자 요청 없이 main 병합·PR 생성·Release 발행을 하지 않는다.
- 현재 3단계는 기존 Agent를 보존하며 CI/CD와 Dependabot을 구성하고 1~3단계를 커밋해 main 대상 PR을 만드는 작업이다. 사용자가 병합하기 전 main 병합·수동 Tag·실제 Release 발행을 하지 않는다.
- 변경 후 `./gradlew clean build`, 두 JAR 실행, 클래스 버전 검증, VS Code 모듈/Main 경로 확인, `git diff --check`, README 마지막 문구 확인을 수행한다. Windows에서는 `gradlew.bat`를 사용한다.
- 실제 실행한 검사와 정적 검사, 미실행 검사를 구분해 보고한다. 비밀값, 사용자별 JDK 절대 경로, 생성된 빌드 결과를 커밋하지 않는다.

## CI/CD 및 Dependabot

- 변경 전 `docs/CI-CD.md`도 확인한다. Workflow는 `build-logreedy.yaml`, 이름은 `logreedy - Java CI`, Job은 `verify`와 `publish`를 유지한다.
- PR·수동 실행은 검증만 수행한다. `publish`는 main Push 및 `needs: verify` 성공을 모두 요구한다. PR에 쓰기 권한을 주거나 pull_request_target을 사용하지 않는다.
- 기본 contents: read, publish만 contents: write. 기본 GITHUB_TOKEN을 사용하고 PAT를 추가하지 않는다. Checkout 자격 증명을 남기지 않는다.
- Action은 실제 공식 Tag와 확인한 40자리 Commit SHA로 고정하고 버전 주석을 유지한다. 검증과 Release의 Concurrency를 분리하고 진행 중 Release를 자동 취소하지 않는다.
- Gradle은 JDK 25와 Wrapper로 실행한다. VS Code와 CI는 같은 build Task를 사용한다. Ubuntu/Windows 검증과 Legacy 8/11/17/21/25, Modern 21/25 Runtime 검사를 유지한다.
- 버전은 gradle.properties의 단일 값이다. Release는 vMAJOR.MINOR.PATCH, 이름은 LoGreedy v<version>. 동일 버전을 재발행하지 않으며 기존 Tag/Release/Asset을 삭제·이동·덮어쓰지 않는다.
- 정확한 검증 main SHA의 Artifact로 Draft에 네 Asset을 올리고 다운로드·체크섬 검증 후 게시한다. 충돌은 보존하고 원래 Run에서 복구한다.
- Dependabot은 Gradle/Actions 주간 월요일 09:00 Asia/Seoul, 항목별 PR 최대 5개다. 자동 병합하지 않고 Major·Java 호환성·SHA 갱신을 리뷰한다.
- 검증 후 Python 배포 단위 검사, Runtime Smoke, Artifact 체크섬 검증, actionlint, git diff --check를 수행한다. 실제 GitHub 실행과 로컬/모의 검증을 구분해 보고한다.
