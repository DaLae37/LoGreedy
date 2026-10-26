# LoGreedy 개발 스타일

## 조사 범위와 근거

2026-10-26에 공개 프로필, 실제 Git 체크아웃, Release·PR HTML 페이지를 확인했다. GitHub API는 환경 정책상 차단되어 공개 페이지와 Git 읽기를 사용했다. 아래는 조사 표본의 사실이며 계정 전체의 영구 규칙이 아니다.

| 대상 | 조사 기준 | 확인 범위 |
| --- | --- | --- |
| [DaLae37](https://github.com/DaLae37) | 공개 프로필 | `Hello World!`, 여러 언어의 DL-Engine 프로젝트. 프로필만으로 Java 규칙을 추정하지 않음 |
| [AmazingJson](https://github.com/DaLae37/AmazingJson/tree/c52f19a) | `c52f19a` | Java 코드, README, Commit, Branch, Release |
| [Infrastructure](https://github.com/DaLae37/Infrastructure/tree/69ffa4b) | `69ffa4b` | README, Workflow, Commit, PR |
| [MakeEPUB](https://github.com/DaLae37/MakeEPUB/tree/8e6ed0d) | `8e6ed0d` | README, Commit, Release |

## 실제 확인된 기존 관례

- **클래스·패키지:** AmazingJson의 `JsonReader`, `JsonWriter`, `JsonModifier`, `Main`, `com.dalae37`, Maven 표준 `src/main/java` 구조.
- **메서드:** `JsonReader.ReadJsonFiles`, `JsonWriter.WriteJsonFiles`처럼 PascalCase가 보인다. `JsonModifier`에는 `createDefaultDepths`, `appendJsonNode`, `matchesType` 같은 camelCase도 있다. `Main.main`은 표준 Java 진입점이다. `ModifiyJson` 같은 오타는 관례로 채택하지 않는다.
- **변수·상수:** `fileName`, `filePath`, `jsonMap`은 camelCase, `MACRO_FILE_NAME`은 대문자 밑줄이다. 기존 `MACRO_FILE_NAME`은 인스턴스 final 필드이므로 무조건 static인 것으로 간주하지 않는다.
- **들여쓰기:** `JsonReader`는 공백 4칸, `JsonModifier`는 탭도 사용한다. 기존 전체가 일관된 것은 아니다.
- **README:** AmazingJson은 한국어 제목·짧은 소개·기술 배지·다운로드·주요 기능·번호 순서 사용법·빌드 정보·라이선스 링크를 쓴다. Infrastructure는 한국어 소개와 경로별 설명, 코드 블록을 쓴다. MakeEPUB은 영어 요구사항·사용법·스크린샷을 제공한다. 모든 프로젝트가 한국어라는 주장은 하지 않는다.
- **Commit:** AmazingJson `1fa22b6 Update README.md`, `c52f19a Add json merge Functions`, MakeEPUB `9ff8761 Add Script files`, Infrastructure `79375fe Changed letsencrypt renew method`. 짧은 영어 동작 문구가 많지만 시제·대소문자는 혼재한다. Conventional Commits는 공통 관례로 확인되지 않았다.
- **Actions:** Infrastructure의 `.github/workflows/build-web-api-dalae37-com.yaml`, `build-web-www-dalae37-com.yaml`; Workflow 이름은 `web-api-dalae37-com - Container Image CI`, `web-www-dalae37-com - Container Image CI`; Job 키는 `verify`, `publish`. 기존 사례이며 LoGreedy 3단계에서 같은 파일명·Workflow/Job 이름 경향을 채택했다.
- **Release·Tag:** [AmazingJson Releases](https://github.com/DaLae37/AmazingJson/releases)의 이름과 Tag는 `v1.0`, `v1.1`, `v1.2`; [MakeEPUB Releases](https://github.com/DaLae37/MakeEPUB/releases)의 이름과 Tag는 `v1.0.0`. `v` 접두어는 확인되지만 자리 수는 일치하지 않는다.
- **Branch·PR:** AmazingJson 원격에는 `main`, `feature/issue-1-json-merge`가 있다. 조사 시 공개 PR 목록에서는 PR을 확인하지 못했다. [Infrastructure PR #7](https://github.com/DaLae37/Infrastructure/pull/7)은 `Bump docker/setup-buildx-action from 4.3.0 to 4.4.1`, 병합 커밋은 `Merge pull request #7 from DaLae37/dependabot/github_actions/docker/setup-buildx-action-4.4.1`이다. 자동화 PR 표본을 사용자 작성 PR의 보편 규칙으로 단정하지 않는다.

## LoGreedy에서 새로 정한 규칙

아래는 기존 경향을 참고한 이 프로젝트의 결정이다.

| 항목 | LoGreedy 규칙 |
| --- | --- |
| 패키지 | `com.dalae37.logreedy`, Runtime은 `.runtime.jdk8`, `.runtime.jdk21` |
| 클래스 | PascalCase, public 클래스와 파일명 일치 |
| 자체 정의 메서드 | PascalCase (`Run`, `ReadVersion`), 대표적인 기존 스타일을 채택 |
| 변수·매개변수 | camelCase (`fileName`), 의미 있는 이름 |
| 상수 | UPPER_SNAKE_CASE, 공유 불변값은 `static final`. 모든 final 변수를 상수명으로 쓰지 않음 |
| 형식 | UTF-8, 공백 4칸, JSON 2칸, 같은 줄 여는 중괄호, 마지막 개행. 배치는 CRLF, 그 외 LF |
| Commit | 짧은 영어 동사 문구, 예: `Add Gradle project setup`, `Update README.md` |
| Branch | `feature/<topic>` 권장. 현재 `feature/project-setup`을 다음 단계에서도 이어 사용 |
| PR | 명시적 요청 시에만 생성. 짧은 동사 제목, 본문에 변경 동작·검증·제한. 3단계에서 사용자 요청에 따라 main 대상 PR 생성 |
| 향후 버전 | 프로젝트 버전 `0.1.0`. 향후 Tag/Release 이름은 `v0.1.0` 형식 권장. 실제 Tag/Release는 PR 병합 후 CI에서 생성 |
| 문서 | 한국어 소개, 실제 실행 명령과 구조, 구현 상태, 라이선스 링크. 검증하지 않은 배지는 넣지 않음 |

### Java 계약과 호환성 예외

`public static void main(String[] args)`, `toString`, `equals`, `hashCode`, `run`, override, 인터페이스·프레임워크·직렬화 규약의 이름은 계약 그대로 유지한다. PascalCase를 위해 계약을 깨뜨리지 않는다. 외부 API 호출 이름도 바꾸지 않는다.

Core/Legacy는 JDK 25의 `--release 8`로 빌드하므로 Java 8 문법과 API만 사용한다. Modern만 `--release 21`을 사용한다. 공통 기능은 Core에 한 번만 구현하고 Runtime은 진입점이나 런타임 고유 기능만 담당한다. Modern JAR 내부 Core 클래스는 52, Modern 진입점은 65가 정상이다.

### 도구와 검증

JDK 25에서 Gradle Wrapper 9.8.2를 사용한다. `gradle.properties`의 버전을 CLI 리소스와 JAR manifest에 전달한다. 사용자 JDK 경로, 비밀값, 생성 결과는 커밋하지 않는다. Wrapper 배포본 SHA-256을 유지한다.

`./gradlew clean build`는 JUnit 단위·통합 검사, Core CLI 검사 5개, 패키징 JAR별 실행 검사 3개와 모든 클래스 버전을 검증한다. 1단계의 `cliTest` JavaExec를 유지하며 2단계에서 JUnit을 추가했다. Runtime 모듈의 `test NO-SOURCE`를 통과 개수로 세지 않는다. 빌드·JUnit은 JDK 25에서 실행하고, 선택적 실제 Java 8/21 실행은 `docs/TESTING.md`의 환경 변수를 사용해 구분한다.

작업 전 항상 루트 `AGENTS.md`와 이 파일을 먼저 읽는다. README 마지막 섹션은 아래 문구로 고정하고 이후에도 그 아래에 내용을 추가하지 않는다.

```markdown
## 개발

이 프로젝트는 OpenAI Codex를 활용하여 개발되었습니다.
```

## CI/CD 스타일

3단계에서 새로 정한 LoGreedy 규칙: `build-logreedy.yaml` / `logreedy - Java CI`, Job은 verify/publish, 간결한 영어 Step 이름, 공식 Action 전체 SHA와 버전 주석, 기본 Read 권한을 사용한다. 자동화 Python 함수는 의미 있는 PascalCase를 쓰되 unittest/HTTP 핸들러 등 표준 계약 이름은 유지한다. Dependabot 기본 `Bump ...` 메시지는 실제 Infrastructure 사례와 같으므로 별도 접두어를 넣지 않는다. Release Notes는 짧은 한국어 소개·기능 목록·실행 명령·검증 및 제한으로 작성한다. 보안·발행·복구 규칙은 `CI-CD.md`와 `AGENTS.md`를 따른다.
