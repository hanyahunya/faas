# ⚡️ High-Performance Custom FaaS Platform

> **OS 포트 고갈 없는 무제한 확장성과 경쟁적 소비(Competitive Consumption) 모델을 통한 Cold Start 제로화 도전**

이 프로젝트는 AWS Lambda와 유사한 **FaaS(Function as a Service)** 플랫폼을 직접 구현한 결과물입니다. 초기 아키텍처(ADR-001)를 기반으로 **Java Virtual Threads, Go(Golang), Unix Domain Socket, cgroup 직접 제어** 등의 기술을 도입하여 극한의 성능과 안정성을 확보했습니다.

## 🚀 Key Performance (k6 Load Test)
<img width="2250" height="864" alt="화면 캡처 2025-12-28 175058" src="https://github.com/user-attachments/assets/a5ebaeee-6732-4431-a97a-45b4a5fec507" />

* **Throughput**: 7,000+ RPS (Requests Per Second) 안정적 처리
* **Latency (P95)**: 31.16ms
* **Success Rate**: 99.99% (236만 요청 중 단 169건 실패)
* **특징**: Cold Start 상황에서도 '컨테이너 재사용 경쟁' 메커니즘을 통해 대기 시간을 획기적으로 단축.

---

## 🏗 System Architecture

이 시스템은 **Hexagonal Architecture**를 기반으로 설계되어 도메인 보호와 외부 의존성(Docker, Redis, S3 등) 관리가 용이합니다.

### 1. Core Components
* **Gateway (Spring WebFlux)**:
    * 모든 요청의 진입점. 비동기/논블로킹 처리에 특화된 WebFlux 사용.
    * JWT 검증 및 파싱 후 `X-Header` 주입, Eureka를 통한 동적 라우팅.
* **Registry (Hexagonal)**:
    * 함수 등록 및 보안 검증 담당. 언어별 추상화된 검증 로직 적용.
    * **MySQL 사용**: 사용자 정보와 함수 메타데이터 간의 관계(Relation)를 명확히 하고, 엄격한 데이터 정합성(ACID)을 보장하기 위해 RDBMS 채택.
    * 악성 코드 검사 후 S3에 업로드 및 실행 메타데이터(AccessKey) 암호화 저장.
    * 함수 업데이트 시 Redis Pub/Sub으로 Invoker 캐시 무효화.
* **Invoker (Java Virtual Threads)**:
    * **핵심 스케줄러**. 함수 실행 요청을 받아 가용한 컨테이너를 확보하고 실행을 명령.
    * gRPC를 통한 고속 통신 (HTTP 핸드쉐이크 오버헤드 제거).
    * Redis List(`LPOP`, `BLPOP`)를 활용한 **경쟁적 소비 모델** 구현.
* **Provisioner (Java Virtual Threads)**:
    * **컨테이너 라이프사이클 관리**. Redis 파티셔닝을 위한 리더 선출 알고리즘 내장.
    * Cold Start 요청 시 Docker 컨테이너 생성(Network None), 볼륨 마운트, UDS 연결 후 Pause 상태로 대기.
    * 가상 스레드를 활용한 비동기 작업 처리 및 동적 스케일링 지원.
* **Agent (Go / Sidecar)**:
    * **Go 언어(Golang) 구현**: UDS 통신과 높은 트래픽 처리에 유리한 Go의 동시성 모델(Goroutines)과 시스템 프로그래밍 성능을 활용.
    * 실제 함수가 실행되는 호스트에 위치하며, **cgroup 직접 제어**를 통한 초고속 Unpause/Pause (Docker Daemon 오버헤드 제거) 수행.
    * UDS(Unix Domain Socket)를 통해 컨테이너 내부와 통신하며, 실행 로그는 S3로 직접 오프로딩.

### 2. 핵심 로직: Cold Start 최소화 ("Competitive Consumption")
이 프로젝트의 가장 큰 특징은 **Provisioner가 Cold Start를 위해 새 컨테이너를 만드는 동안에도, 다른 요청이 쓰고 반납한 컨테이너가 생기면 즉시 가로채서(Stealing) 실행한다**는 점입니다.

1.  **Warm Start 시도**: `LPOP`으로 유휴 컨테이너 확인. (성공 시 즉시 실행)
2.  **Cold Start 요청**: 실패 시 Provisioner에 생성 요청(`RPUSH`)을 보내고, 동시에 `BLPOP`으로 대기.
3.  **경쟁적 획득**: Provisioner가 새 컨테이너를 만들기 전에, 다른 요청이 완료되어 컨테이너가 반납되면 `BLPOP`이 즉시 반응하여 해당 컨테이너를 획득.
    * 👉 **결과**: Cold Start 요청임에도 불구하고 Warm Start에 준하는 속도로 처리 가능.

---

## 🛠 Tech Stack

| Category | Technology | Reason for Selection |
| :--- | :--- | :--- |
| **Language** | Java 21+, **Go** | Java(Virtual Threads)는 I/O 블로킹 최소화, Go(Agent)는 고성능 트래픽 처리 및 시스템 제어에 최적화 |
| **Framework** | Spring Boot, WebFlux | Gateway의 높은 동시성 처리 및 안정적인 에코시스템 |
| **Architecture** | Hexagonal | 비즈니스 로직과 인프라 기술(Docker, S3)의 철저한 분리 |
| **Messaging** | Redis (List, Pub/Sub) | 초고속 큐 관리 및 원자적 연산(`LPOP`, `RPUSH`) 보장 |
| **Communication** | gRPC, UDS | HTTP 핸드쉐이크 비용 제거 및 TCP 포트 고갈 문제 해결(UDS) |
| **DB (Log/Meta)** | **MongoDB**, **MySQL** | **MySQL**: 유저/함수 정보의 정합성 보장. **MongoDB**: 대량의 실행 로그 쓰기(Write) 성능 확보. |
| **Infra** | Docker, AWS S3 | 격리된 실행 환경 제공 및 코드/로그의 저비용 저장 |

---

## 🔧 Troubleshooting & Optimization (Dev Log)

부하 테스트(k6) 과정에서 발생한 병목 현상을 해결한 기록입니다.

### 1. Docker Daemon CPU 점유율 폭발 (Agent)
* **문제**: 함수 실행 전후로 `docker unpause/pause` 명령을 데몬 API로 수행하니, 트래픽이 몰릴 때 데몬의 CPU 사용량이 80%를 상회함.
* **해결**: Docker Daemon을 거치지 않고, 리눅스 **cgroup 파일 시스템(`fs`)에 직접 접근**하여 상태를 제어.
* **결과**: CPU 사용량을 평균 30% 미만으로 감소시킴.

### 2. Too Many Open Files 오류 (Gateway)
* **문제**: 부하 테스트 시 가상 유저(VUs) 수가 증가하자 Linux의 기본 파일 디스크립터 제한(1024개)에 도달하여 연결 거부 발생.
* **해결**: OS의 `ulimit -n` 설정을 통해 파일 오픈 제한(File Descriptor Limit)을 대폭 상향 조정하여 해결.

### 3. Socket 파일 생성 레이스 컨디션 (Provisioner)
* **문제**: Provisioner가 컨테이너를 생성하고 UDS 파일을 마운트하기 전에 Invoker에게 "준비됨" 응답을 보내 Agent가 파일을 찾지 못하는 오류 발생.
* **해결**: 단순 대기가 아니라, OS 레벨에서 해당 `.sock` **파일의 생성 이벤트를 감지(Listen)**하는 로직을 가상 스레드로 구현하여 해결.

### 4. Docker Connection Pool 고갈 및 속도 저하
* **문제**: Cold Start가 몰릴 때 Docker Connection Pool이 고갈되거나, 반대로 너무 많이 늘리면 데몬 반응 속도가 느려짐.
* **해결**: 적정 수준의 Connection Pool 사이즈를 벤치마킹하여 설정하고, 불필요한 I/O를 비동기로 전환.

---

## 🔮 Future Works
* 사용자 정의 라이브러리 지원 (Layer 개념 도입)
* 모니터링 대시보드 고도화 (Prometheus/Grafana 연동)
