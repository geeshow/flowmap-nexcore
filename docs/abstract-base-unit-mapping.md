# 추상 base 상속 unit 인식 — endpoint external 오분석 수정

> PR [#6](https://github.com/geeshow/flowmap-nexcore/pull/6) · commit `3bb39da`
> 대상: `SourceScanner.java`, `CrossRun.java`, `AbstractBaseResolutionTest.java`

## 1. 증상

운영 PC의 더 큰 NEXCORE repo를 분석하면, 실제로는 cross-project 호출인 일부 endpoint가
정확히 매핑되지 않고 **external 노드(`ext:SHARED#...`)** 로 분석됐다. 웹 전체보기(`?view=overview`)에서
서비스 간 `s2s` 연결로 보여야 할 호출이 외부 호출처럼 끊겨 표시된다.

로컬 샘플 repo(`../nexcore`)는 모든 unit이 base를 **직접** 상속해서 이 버그가 재현되지 않았고,
운영 repo에서만 발생했다.

## 2. 분석 파이프라인 맥락

`refresh` 명령(`Cli.refresh`)의 흐름:

```
SourceScanner.scan(repo, null)         # repo 전체 1회 스캔 → 모든 unit 수집
  → UnitIndex global                   # 전 프로젝트 unit 인덱스
  → for each project:
        GraphBuilder.buildProject(global, project)
```

`buildProject`는 한 프로젝트의 unit만 source로 순회하되, **호출 대상은 `global` 인덱스로 resolve**한다.
그래서 cross-project shared-call(`callSharedMethod*`)은:

| globalIndex resolve | 결과 |
|---|---|
| 성공 | 실제 provider 노드로 잇는 **`s2s` 엣지** (combine 없이 웹에서 바로 렌더) |
| 실패 | **`ext:SHARED#<comp>.<Unit>.<method>` placeholder** (external) |

→ `GraphBuilder.handleFramework()`의 `CROSS` 분기 참조.

즉 external 오분석의 직접 원인은 **대상 unit이 `global` 인덱스에 등록되지 않은 것**이다.

## 3. 근본 원인

`SourceScanner.classify()`가 클래스의 **직속(direct) superclass 이름이 정확히**
`ProcessUnit` / `FunctionUnit` / `DataUnit` 일 때만 unit으로 등록했다.

운영 repo에는 프레임워크 추상 레이어를 한 단계 거쳐 상속하는 unit이 있다:

```java
class FCA0049 extends AbstractFunctionUnit { ... }   // AbstractFunctionUnit extends FunctionUnit
```

직속 super가 `AbstractFunctionUnit`이라 매칭에 실패 → unit 미등록 → `global`에 없음 →
이 unit을 향한 shared-call은 전부 `ext:SHARED#`(external)로 분석됐다.

추가로, per-project 스캔(`scan(repo, project)`)은 프레임워크 base 모듈이 스캔 범위 밖이라
super 체인을 끝까지 따라갈 수도 없다.

## 4. 수정

### 4.1 `SourceScanner` — 2-pass 분류 + 전이적 base 해석

**Pass 1 (수집):** 모든 `.java`를 파싱하며
- `simpleName → 직속 super simpleName` 인덱스(`superIndex`)를 만든다(모든 클래스, 추상 base 포함 — 체인 추적용).
- 추상 클래스는 프레임워크 base로 보고 **unit 노드 후보에서 제외**(인덱스에는 남겨 체인 링크로만 사용).

**Pass 2 (분류):** `resolveBase()`로 unit base를 전이적으로 해석한다. 두 단서를 함께 사용:

1. **이름 suffix 휴리스틱** — `matchBaseName()`:
   `*ProcessUnit` → `ProcessUnit`, `*FunctionUnit` → `FunctionUnit`, `*DataUnit` → `DataUnit`,
   `*BatchComponent`/`MBBatchComponent` → batch.
   → `AbstractFunctionUnit` 같은 중간 base가 **스캔 범위 밖(per-project)** 이어도 이름만으로 분류 가능.
   → 공통 조상 `AbstractBizUnit`은 매칭되지 않아 base 3종을 잘못 흡수하지 않음.
2. **super 체인 추적** — 이름으로 못 잡으면 `superIndex`를 따라 base까지 올라간다
   (중간 base 이름에 종류가 안 드러나는 `extends FooBase` 형태 대응).

진단을 stderr로 출력한다 — unit base가 skip 목록에 높은 빈도로 뜨면 새 프레임워크 레이어 신호:

```
[scan] <project>: units=N, skipped supers=[RuntimeException×2, ...]
```

### 4.2 `CrossRun.findReal` — combine 재연결 보강

`ext:SHARED#` placeholder가 combine 단계까지 남는 경우(standalone `combine` 명령 등)를 위해
재연결 매칭을 강화했다. `ext:SHARED#<comp>.<Unit>.<method>` 에서:

- **comp 힌트**가 후보 id에 매칭되면(`.<comp>.` 포함) 즉시 채택.
- comp로 구분 안 되고 `.<Unit>#<method>` suffix 매치가 **여럿이면 보수적으로 external 유지**.
  (기존엔 무조건 first-match라 다른 프로젝트의 동명 unit으로 **거짓 `s2s`** 가 생길 수 있었다.)

## 5. 검증

- 신규 `AbstractBaseResolutionTest`:
  - 추상 base(`AbstractFunctionUnit`) 뒤의 `FCA0049`가 인덱싱되고, 추상 base 자체는 unit 노드가 아님.
  - cross-project shared-call이 `ext:SHARED#`이 아니라 **`s2s` 엣지**로 resolve.
  - per-project 스캔(base가 범위 밖)에서도 이름 suffix로 인식.
- 기존 테스트 전부 통과.
- 로컬 repo `refresh` 회귀: unit 37개, skip된 super는 `RuntimeException`뿐(unit base 누락 0), 노드/엣지 수 동일.

## 6. 영향 범위 / 남은 것

- 이 수정은 **shared-call(`ext:SHARED#`)** 의 unit 매핑만 다룬다.
- 별도 매핑 영역(가이드 밖): `ext:LINKED#`(연동거래 service-code), `ext:JOB#`(배치), `ext:FEP`/`ext:EDW`(아웃바운드).
  특히 `callService("TXN_LIMIT_CHECK")` 류는 service-code → target unit 매핑 레지스트리가
  소스에 없어(현재 README에만 문서화) 분석기가 자동 resolve할 수 없으므로 여기서 다루지 않았다.
