# ⚡️ High-Performance Custom FaaS Platform

> **포트 고갈 없는 무한한 확장성과, 경쟁적 소비(Competitive Consumption) 모델을 통한 콜드 스타트의 극소화**

본 프로젝트는 AWS Lambda와 같은 **FaaS (Function as a Service)** 플랫폼을 독자적으로 구현한 것입니다. 초기 아키텍처(ADR-001)를 기반으로 **Java Virtual Threads, Go 언어(Golang), Unix Domain Socket, cgroup 직접 제어** 등의 기술을 도입하여, 극한의 성능과 안정성을 확보했습니다.

* **개발 기간**: 2025.12.17 ~ 2025.12.27

## 📚 목차 (Table of Contents)
- [🚀 주요 성능 (Key Performance)](#-key-performance-k6-load-test)
- [🏗 시스템 아키텍처 (System Architecture)](#-system-architecture)
  - [핵심 컴포넌트 (Core Components)](#1-core-components)
  - [핵심 로직: 경쟁적 소비 (Core Logic: Competitive Consumption)](#2-コアロジック-コールドスタートの最小化-competitive-consumption)
  - [요청 처리 흐름 (Request Lifecycle)](#3-リクエスト処理フロー-request-lifecycle)
- [🛠 기술 스택 (Tech Stack)](#-tech-stack)
- [🔧 트러블슈팅 및 최적화 (Troubleshooting & Optimization)](#-troubleshooting--optimization-dev-log)
- [🔮 향후 전망 (Future Works)](#-future-works)

---

## 🚀 Key Performance (k6 Load Test)
<img width="2250" height="864" alt="Load Test Result" src="https://github.com/user-attachments/assets/ee0d0b2c-6aa4-47a6-9a6d-e29154e48918" />

* **Max Throughput**: 19,000+ RPS
* **Scenario**: 0 to 2,000+ VUs Ramp-up Test
* **Latency (P95)**: 31.16ms
* **Success Rate**: 99.99% (236만 리퀘스트 중, 실패는 단 169건)
* **특징**: 콜드 스타트 상황에서도 '컨테이너 재사용 경쟁' 메커니즘을 통해 대기 시간을 획기적으로 단축.

---

## 🏗 System Architecture

본 시스템은 **헥사고날 아키텍처(Hexagonal Architecture)**에 기반하여 설계되었으며, 도메인 로직의 보호와 외부 의존성(Docker, Redis, S3 등) 관리를 용이하게 했습니다.

### 1. Core Components
* **Gateway (Spring WebFlux)**:
    * 모든 요청의 진입점. 비동기/논블로킹 처리에 특화된 WebFlux 채택.
    * JWT 검증·해석 후 `X-Header`를 주입하고, Eureka를 통한 동적 라우팅 실행.
* **Registry (Hexagonal)**:
    * 함수 등록 및 보안 검증 담당. 언어별로 추상화된 검증 로직 적용.
    * **MySQL 채택**: 사용자 정보나 함수 메타데이터 간의 관계성(Relation)을 명확히 하고, 엄격한 데이터 정합성(ACID)을 보장하기 위해 RDBMS 사용.
    * 악의적인 코드를 검사 후 S3 업로드, 실행 메타데이터(AccessKey)를 암호화하여 저장.
    * 함수 업데이트 시 Redis Pub/Sub를 이용하여 Invoker의 캐시 무효화.
* **Invoker (Java Virtual Threads)**:
    * **핵심 스케줄러**. 함수 실행 요청을 받아 가용한 컨테이너를 확보하고 실행을 명령.
    * gRPC를 통한 고속 통신 (HTTP 핸드쉐이크 오버헤드 제거).
    * Redis List (`LPOP`, `BLPOP`)를 활용한 **경쟁적 소비 모델** 구현.
* **Provisioner (Java Virtual Threads)**:
    * **컨테이너 수명 주기 관리**. Redis 파티셔닝을 위한 리더 선출 알고리즘 내장.
    * 콜드 스타트 요청 시, Docker 컨테이너 생성(Network None), 볼륨 마운트, UDS 연결을 수행하고 Pause 상태로 대기.
    * 가상 스레드를 활용한 비동기 작업 처리와 동적 스케일링 대응.
* **Agent (Go / Sidecar)**:
    * **Go 언어(Golang) 구현**: UDS 통신과 고트래픽 처리에 유리한 Go의 병행 처리 모델(Goroutines)과 시스템 프로그래밍 성능 활용.
    * 실제 함수가 실행되는 호스트 상에 배치되어, **cgroup 직접 제어**를 통한 초고속 Unpause/Pause (Docker Daemon 오버헤드 제거) 수행.
    * UDS (Unix Domain Socket)를 통해 컨테이너 내부와 통신하며, 실행 로그는 S3로 직접 오프로드.

### 2. 핵심 로직: 콜드 스타트 최소화 ("Competitive Consumption")
본 프로젝트의 가장 큰 특징은, "Provisioner가 콜드 스타트를 위해 새로운 컨테이너를 생성하는 동안에도, 다른 요청이 사용을 마치고 반납한 컨테이너가 있다면 즉시 이를 가로채서(Stealing) 실행한다"는 점입니다.

### 3. 요청 처리 흐름 (Request Lifecycle)

다음 3가지 시나리오에서의 아키텍처 다이어그램 `(1)` ~ `(11)` 상세 흐름입니다.

<img width="1161" height="887" alt="final_arch" src="https://github.com/user-attachments/assets/68aa1d31-5f07-43ec-bb9b-a1dd3f87efa8" />

## 🟦 시나리오 A: Cold Start (Standard Flow)
새로운 컨테이너를 생성하여 실행하는 가장 기본적인 흐름입니다.

* **(1) 함수 실행을 위한 액세스 키 검증 (Invoker -> Registry)**
    * **캐싱 전략**: 검증 결과는 캐시되며, 원본(Registry)에 변경이 생길 시 즉시 캐시를 삭제하는 로직 구현.
    * **gRPC 통신 채택**: 대량의 요청 발생 시, HTTP/1.0에서는 요청마다 3-way handshake가 발생하여 리소스 낭비가 되므로, 이를 방지할 목적으로 채택.
* **(2) 실행 가능한 리소스 유무 확인 (Invoker)**
    * **LPOP 채택**: `EXISTS`로 확인 후 `POP`을 수행하면, 그 사이 다른 요청이 리소스를 가로챌(Race Condition) 가능성이 있으므로, `LPOP`을 사용하여 즉시 획득하는 방식 채택.
    * **결과**: 리소스 없음(Miss) → Cold Start 프로세스로 이동.
* **(3) Provisioner 서버로 실행 환경 생성 요청 (Invoker -> Provisioner)**
    * **해시 기반 파티셔닝**: '함수ID'와 '함수ID:secondary'를 해시화하여 16384개의 파티션에 분배, 항상 동일한 Provisioner 서버군이 요청을 담당하도록 설계.
    * **부하 분산 (Power of Two Choices)**: 특정 서버로 트래픽이 집중되는 것을 막기 위해, 2개의 해시 후보 중 `LLEN`으로 큐 길이를 비교하여 더 여유 있는(비어 있는) 파티션에 처리 요청.
* **(3-1) 실행 환경 생성 대기 (Blocking Wait)**
    * **Java Virtual Threads**: 물리 스레드를 점유하지 않는 Java Virtual Threads를 채택하여, 수천 개의 동시 대기(`BLPOP`)를 저부하로 실현.
* **(4) 소스 코드 취득 및 전개 (Provisioner)**
    * **S3 다운로드 지연 최소화**: 동일 함수를 같은 서버에서 처리하게 함으로써 캐시 히트율을 높이고, S3로부터의 코드 취득 시간을 단축. 이미 로컬에 압축 해제된 파일이 있다면 다운로드를 건너뛰고 재사용.
    * **동시성 관리 (ConcurrentHashMap)**: 캐시가 없는 상태에서 동일 함수에 대한 동시 요청이 집중될 경우, `ConcurrentHashMap`을 활용하여 중복 다운로드를 방지하고 스레드 세이프하게 처리.
    * **캐시 GC**: 최종 사용 시점으로부터 일정 시간이 경과한 캐시 파일은, 자체 가비지 컬렉터에 의해 자동으로 삭제되어 디스크 공간 관리.
* **(5) 실행 환경(컨테이너) 생성 (Provisioner)**
* **(6) 실행 환경 정보 등록 (Provisioner -> Invoker)**
    * **접속 정보 통지**: 생성 완료 후, Invoker가 `BLPOP`으로 대기 중인 큐(`func:idle:{func_id}`)에 실행 환경 접속 정보(Agent IP, UDS 경로)를 `RPUSH`.
    * **동일 호스트 배치 전략**: Provisioner, Agent, 컨테이너는 반드시 동일 호스트 상에 배치되는 아키텍처이므로, Provisioner는 자신의 IP(Host Network)와 컨테이너 제어용 `function.sock` 경로를 포함하여 통지.
* **(7) 실행 환경 정보 획득 (Invoker)**
* **(8) 실행 명령 (Invoker -> Agent)**
* **(9) 함수 실행 (Agent -> 함수 실행 환경)**
* **(10) 실행 결과 응답 (Agent -> Invoker)**
    * **메타데이터 반환**: 함수의 실행 결과(반환값), 실제 실행 시간, 메모리 사용량, 그리고 로그가 저장되는 S3의 객체 키 정보를 gRPC로 즉시 응답.
* **(10-Async) 로그 비동기 저장 (Agent -> S3)**
    * **I/O 분리**: 사용자 응답(10)과는 독립된 비동기 프로세스(Goroutines)로 로그를 S3에 업로드하여, 로그 저장 레이턴시가 응답 시간에 영향을 주지 않도록 최적화.
* **(11) 실행 환경 반납 (Invoker -> Redis)**
    * **큐로 반환**: 실행이 완료된 컨테이너의 접속 정보를 즉시 큐(`func:idle:{func_id}`)에 `RPUSH`하여 리소스를 재사용 가능한 상태로 되돌림.
* **(11-Async) 실행 로그 저장 (Invoker -> MongoDB)**
    * **상세 메트릭 기록**: 사용자 응답 후, 백그라운드에서 실행 타입(`COLD`/`WARM`), 메모리 사용량, 성공 여부 등의 상세 데이터를 MongoDB에 저장.
    * **성능 분석**: Agent가 측정한 순수 실행 시간(`durationMs`)과 Invoker가 계측한 전체 처리 시간(`totalProcessingTimeMs`), 그리고 콜드 스타트 지연(`coldStartDurationMs`)을 구분하여 기록하고 각 단계의 오버헤드를 시각화.
    * **로그 연동**: S3에 저장된 로그 파일의 키(`logS3Key`)도 저장하여 메타데이터와 실제 로그의 연결 수행.
 
> **Note**: 절차 `(2)LPOP`, `(3-1)BLPOP`, `(6)RPUSH`, `(7)BLPOP`, `(11)RPUSH`는 모두 동일한 Redis 큐 (`func:idle:{func_id}`)를 대상으로 작업을 수행합니다.

## 🟩 시나리오 B: Warm Start (Fast Track)
이미 대기 중인 컨테이너가 존재하는 경우의 흐름입니다.

* **(1) Cold Start와 동일**: 액세스 키 검증.
* **(2) 실행 가능한 리소스 유무 확인 (Hit)**: `LPOP`이 성공하여 대기 중인 컨테이너(`IP:Port`)를 즉시 획득.
* **(3) ~ (7) Skip**: 신규 생성 프로세스 생략.
* **(8) ~ (11) Cold Start와 동일**: 획득한 컨테이너에 즉시 실행 명령(`gRPC`)을 보내고, 이후는 통상적인 흐름(실행, 로그 저장, 반납)대로 처리.

## 🟧 시나리오 C: Stealing (상황에 따라 Warm Start급 속도가 나오는 Cold Start)
Cold Start로서 대기 중(`3-1`)일 때, 다른 요청이 컨테이너를 반납(`11`)한 경우의 흐름입니다. (형식상으론 Cold Start이지만, Warm Start와 동등한 속도를 실현할 수 있는 흐름입니다.)

* **(1) ~ (3) Cold Start와 동일**: 리소스가 없어 생성 요청을 보냄.
* **(3-1) 대기 (Blocking Wait)**: `BLPOP`으로 Provisioner의 완료를 기다리고 있는 상태.
* **(11) 컨테이너 반납 (별도 리퀘스트)**: **(여기서 인터럽트 발생)** 다른 요청이 처리를 마치고 Redis에 컨테이너를 반납(`RPUSH`).
* **(7) 경쟁적 획득 (Stealing)**: Provisioner가 새 컨테이너를 만드는 것보다 빨리, (11)에서 돌아온 컨테이너를 `BLPOP`이 감지하여 획득.
* **(8) ~ (11) 실행으로**: 신규 작성을 기다리지 않고 즉시 실행.
    * 👉 **효과**: 사용자 입장에서는 Warm Start와 동등한 응답 속도 실현.

> **Note (자연스러운 스케일 아웃)**: 이 프로세스가 완료되면, 최종적으로 큐(`func:idle:{func_id}`)에는 **2개의 컨테이너 (리퀘스트가 사용하고 반납한 것 + 늦게 Provisioner가 신규 작성한 것)**가 남게 됩니다. 이를 통해 시스템은 트래픽 수요에 맞춰 자동적이고 자연스럽게 스케일 아웃(예비 리소스 확보)합니다.
---

## 🛠 Tech Stack

| Category | Technology | Reason for Selection |
| :--- | :--- | :--- |
| **Language** | Java 21+, **Go** | Java(VT)는 I/O 블로킹 최소화, **Go(Agent)**는 고트래픽 처리 및 시스템 제어 최적화 |
| **Framework** | Spring Boot, WebFlux | Gateway의 높은 동시 실행 처리 능력과 안정된 생태계 |
| **Architecture** | Hexagonal | 비즈니스 로직과 인프라 기술(Docker, S3)의 철저한 분리 |
| **Messaging** | Redis (List, Pub/Sub) | 초고속 큐 관리 및 원자적 조작(`LPOP`, `RPUSH`) 보장 |
| **Communication** | gRPC, UDS | HTTP 핸드쉐이크 비용 제거 및 TCP 포트 고갈 문제 해결(UDS) |
| **DB (Log/Meta)** | **MongoDB**, **MySQL** | **MySQL**: 데이터 정합성 보장. **MongoDB**: 대량의 실행 로그 쓰기(Write) 성능 확보. |
| **Infra** | Docker, AWS S3 | 격리된 실행 환경 제공 및 코드/로그의 저비용 저장 |

---

## 🔧 Troubleshooting & Optimization (Dev Log)

부하 테스트(k6) 과정에서 발생한 병목 현상을 해결한 기록입니다.

### 1. Docker Daemon CPU 사용률 폭발 (Agent)
* **문제**: 함수 실행 전후에 `docker unpause/pause` 명령을 데몬 API 경유로 실행했더니, 트래픽 집중 시 데몬의 CPU 사용률이 80%를 초과.
* **해결**: Docker Daemon을 경유하지 않고, 리눅스의 **cgroup 파일 시스템(`fs`)에 직접 접근**하여 상태를 제어.
* **결과**: CPU 사용률을 평균 30% 미만으로 절감.

### 2. Too Many Open Files 에러 (Gateway)
* **문제**: 부하 테스트 시 가상 유저(VUs)가 증가하면, 리눅스의 기본 파일 디스크립터 제한(1024)에 도달하여 연결 거부 발생.
* **해결**: OS의 `ulimit -n` 설정을 통해 파일 오픈 제한(File Descriptor Limit)을 대폭 상향 조정하여 해결.

### 3. 소켓 파일 생성의 레이스 컨디션 (Provisioner)
* **문제**: Provisioner가 컨테이너를 생성하고 UDS 파일을 마운트하기 전에 Invoker에게 '준비 완료' 응답을 보내버려, Agent가 파일을 찾지 못하는 에러 발생.
* **해결**: 단순 대기가 아니라, OS 레벨에서 해당 `.sock` **파일의 생성 이벤트를 감지(Listen)**하는 로직을 가상 스레드로 구현하여 해결.

### 4. Docker Connection Pool 고갈과 속도 저하
* **문제**: 콜드 스타트가 집중될 때, Docker Connection Pool이 고갈되거나, 반대로 너무 많이 늘리면 데몬의 반응 속도가 저하됨.
* **해결**: 적절한 Connection Pool 사이즈를 벤치마킹하여 설정하고, 불필요한 I/O를 비동기로 전환.

---

## 🔮 Future Works
* **사용자 정의 라이브러리 지원 (Layer 개념 도입)**
* **런타임 기반 컨테이너 통합 (Per-Runtime Isolation)**
    * **구상**: 현재의 '특정 함수 전용 컨테이너' 모델에서, '특정 언어·버전(예: `python:3.10`) 전용 컨테이너'로, 서로 다른 함수(A와 B)가 실행 환경을 공유하는 모델로의 진화.
    * **목적**: 본 프로젝트의 핵심인 '경쟁적 소비(Competitive Consumption)'의 적용 범위를 함수 단위에서 런타임 단위로 확장하여, 리소스 효율과 스루풋을 극대화하는 것.
    * **과제와 전망**: 개발 단계에서도 검토했으나, 보안(Noisy Neighbor 문제, 메모리 격리)이나 실행 후 환경 초기화(Clean-up)의 복잡성을 고려하여 이번에는 안정성을 우선했습니다. 향후에는 큐 구조를 `func:idle:{func_id}`에서 `func:idle:{runtime_id}`로 이행하고, 이러한 기술적 과제들을 해결해 나갈 예정입니다.
