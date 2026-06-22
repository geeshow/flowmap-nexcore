# flowmap-nexcore

NEXCORE(BizUnit 프레임웍) 코드베이스를 **JavaParser** 로 정적 분석해
`flowmap-spring` 과 **동일한 형태의 산출물**(node-link 콜그래프 JSON,
`_combined.json`, `_manifest.json`, OpenAPI 3.1, 변경영향도 impact JSON)을 만드는 **Java/Gradle 분석기**.

기존 IntelliJ 플러그인 `nexcore-hierarchy` 의 호출 16종(CallKind)·헬퍼 인라인·cross-module resolve
로직을 standalone 으로 재구현하고, 출력은 flowmap 스키마로 맞춰 같은 웹 렌더러에 그대로 투입할 수 있다.

> **이 저장소는 flowmap 분석 파이프라인의 nexcore 분석기**다. 백엔드(`flowmap-spring`)·프론트
> (`flowmap-react`) 분석기와 웹 허브(`flowmap`)를 **형제 디렉터리로 함께 체크아웃**하는 것을
> 전제로 한다 — 아래 `../web/data` 같은 상대경로가 그 레이아웃 기준이다.
> 통합 모노레포: <https://github.com/geeshow/flowmap5>

## 설정 (필수)

`flowmap.config.example` → `flowmap.config` 로 복사한 뒤 아래 키를 채운다. real `flowmap.config` 은
머신별 설정이라 **gitignore 대상**이고, 템플릿 `flowmap.config.example` 만 추적된다.

| 키 | 필수 | 설명 |
|---|:--:|---|
| `REPO` | ✅ | 분석 대상 NEXCORE repo 루트 |
| `NAMESPACE` | | 산출물 트리 최상위 네임스페이스(예: `kakaopay`). nexcore 는 origin remote 가 없는 모노레포라 직접 지정(미지정 시 origin owner→repo 명 폴백) |
| `OUT_DIR` | | 산출물 스테이징 디렉터리(기본 `json`) — `json/projects/<namespace>/<repo>/<per-root>/` 트리 생성 |
| `SYNC_DIR` | | `sync` 가 스테이징을 펼쳐 넣을 웹앱 data 디렉터리 |
| `EXTRA_ARGS` | | 추가 플래그(예: `--no-impact --impact-depth 4`) |

```bash
cp flowmap.config.example flowmap.config   # REPO 등 값 작성 후 ./gradlew run
```

## 빠른 시작

```bash
# 전부 한 방에 (모든 모듈 콜그래프 + openapi + combine + impact + manifest)
./gradlew run --args="refresh --repo ../nexcore --out-dir ./json"

# staging 산출물을 flowmap 웹앱 data 디렉토리로 동기화 (refresh 와 분리된 별도 단계)
./gradlew run --args="sync --out-dir ./json --sync-dir ../web/data"

# 인자 없이 → flowmap.config 의 설정으로 실행 (flowmap.config.example 복사)
./gradlew run
```

> **staging 레이아웃**: per-project 산출물은 **실제 git namespace/repo 를 따라 중첩**된 디렉토리에
> 들어간다 — `json/projects/<namespace>/<repo>/<per-root>/<per-root>.json`
> (+`.openapi.json`/`.impact.json`). nexcore 는 한 git repo(work-tree)에 bizunit 모듈이 모인
> 모노레포라 **`<namespace>/nexcore/<bizunit>`** (repo 슬롯=`nexcore`, per-root=bizunit). 집계본
> `_combined.json`/`_openapi.json`/`_manifest.json` 은 `json/` 루트에 남는다.
>
> `sync` 는 그 중첩 트리를 웹 data 디렉토리로 **같은 구조 그대로** 펼친다
> (`projects/<namespace>/nexcore/<bizunit>/<bizunit>.{json,openapi.json,impact.json}`) —
> `_combined`/`_openapi` 는 분석기 staging 전용이라 동기화하지 않는다(웹은 per-project 그래프를
> node id 로 자체 병합). 웹 `manifest.json` 은 **덮어쓰지 않고 nexcore 프로젝트만 병합**하므로(기존
> 동명 엔트리는 갱신, 나머지 분석기 엔트리는 보존) Spring·React 산출물과 같은 디렉토리에 공존한다.
> 단, 병합이 안전하려면 `sync` 는 다른 분석기의 sync 가 manifest 를 쓴 **뒤**에 돌아야 한다
> (통합 파이프라인은 sync 단계에서 그렇게 호출한다).

요구: JDK 17+, (impact 사용 시) `git` CLI. 최초 빌드 때 JavaParser/Jackson 의존성을 받는다.

## 무엇을 분석하나

NEXCORE 두 구조를 모두 인식한다.

- **온라인**: ProcessUnit(`P*`) → FunctionUnit(`F*`) → DataUnit(`D*`) + `xsql/*.xsql`
- **배치**: `MBBatchComponent` 상속 배치잡(`BS*`, `@BizBatch`) + `xsql/*.xsql`

외부 진입점 규칙: ProcessUnit 진입 메서드는 **외부 거래** `POST /<Tid>.jmd` 로 노출된다
(`th_tr_id == <Tid>`). 매핑은 **트랜잭션 `T<rest>` ↔ 클래스 `P<rest>` ↔ 메서드 `p<rest>`**
(예 `TACU6017` ↔ `PACU6017` ↔ `pACU6017`).

### 호출 16종(CallKind) → 엣지

각 엣지의 `relation` 에 CallKind wire 문자열을 보존한다(`local-call`, `local-new-tx`, `shared-call`,
`shared-new-tx`, `linked-tx-sync`/`-new`, `linked-tx-async-now`/`-after-commit`, `linked-tx-delay-async`,
`batch-now`/`-after-commit`, `fep-sync`, `edw-sync`, `fep-async-now`/`-after-commit`, `kafka-publish`).
DB 접근은 `db:io`. `kind`/`mode` 는 flowmap enum(`internal|external|s2s|batch|resource`, `sync|async`).

### NEXCORE → flowmap 노드 매핑

| NEXCORE | layer | 비고 |
|---|---|---|
| ProcessUnit 진입 | CONTROLLER | entryPoint=HTTP, `POST /<Tid>.jmd` |
| FunctionUnit | SERVICE | |
| DataUnit | REPOSITORY | |
| 배치잡 `execute()` | BATCH | entryPoint=BATCH |
| 연동거래(callService*) | EXTERNAL | `ext:LINKED#<txId>` |
| 배치호출(callBatchJob*) | BATCH | `ext:JOB#<jobId>` |
| FEP/EDW 아웃바운드 | EXTERNAL | `ext:FEP#<ifId>` / `ext:EDW#<seg>` |
| Kafka 발행 | RESOURCE | `kafka:<eventCode>` (kafka-topic) |
| DB(dbXxx→xsql) | RESOURCE | `db:table:<table>` |
| cross-project 공유호출(배치→온라인, AC↔BC) | (provider 노드) | refresh가 전역 인덱스로 **실제 노드 + `kind:s2s` 엣지** 직접 생성 → 웹이 node id 병합으로 연결 |
| 단일 project analyze에서 미해결 공유호출 | EXTERNAL | `ext:SHARED#<comp>.<Unit>.<method>` (전체 repo가 안 보일 때 폴백) |

## 명령

| 명령 | 설명 |
|---|---|
| `analyze --repo <r> [--project <p>] --out <f>` | 콜그래프 추출 (project=모듈 디렉토리) |
| `combine --dir <d> --out <f>` | 모듈 그래프 통합 + `ext:SHARED` → s2s 재연결 |
| `openapi --repo <r> [--project <p>] --title <t> --out <f>` | `.jmd` 트랜잭션 → OpenAPI 3.1 (operationId=node id) |
| `impact --git <r> --graph <g> [--max N] [--depth N] --out <f>` | git 커밋 → 변경 메서드 → 영향 엔드포인트 |
| `pulls --git <r> --graph <g> --out <f>` | 머지 PR 파일 diff 인덱스(+ 샤드) 생성 — 재분석 없이 PR 메타만 갱신 |
| `refresh [--repo <r>] [--out-dir <d>] [--no-impact]` | 위 전부 오케스트레이션 → `<out-dir>/projects/<namespace>/<repo>/<per-root>/` 트리 + 집계본 |
| `sync --out-dir <d> --sync-dir <web>` | staging 서비스 트리를 웹 data 로 flat 펼침 + manifest 병합 (재분석 없음) |

산출물 스키마/연동은 `flowmap-spring` 의 `MANUAL.md` 4장과 동일하다(형제 디렉터리 또는
[모노레포](https://github.com/geeshow/flowmap5) 참고).

## 빌드/테스트

```bash
./gradlew build        # 컴파일 + 단위/통합 테스트
./gradlew fatJar       # 단일 실행 jar (build/libs/*-all.jar)
```

## 한계

- 문자열 인자는 **리터럴**(+ 단순)만 해석한다. `final static` 상수 참조 등 심볼 해석이 필요한 경우는 미해석.
- impact 는 git 작업트리가 필요하다(`../nexcore` 에 git 미설정 시 refresh가 impact 단계를 건너뜀). 의미 있는
  영향도는 커밋 히스토리가 쌓인 뒤 나온다. 삭제 엔드포인트/breaking 탐지는 현재 미구현(빈 배열로 출력).
- OpenAPI 요청/응답 스키마는 `uio/*.uio` 명세가 있으면 필드까지, 없으면 `common_header`+`data` opaque 봉투로 생성.
