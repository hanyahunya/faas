# ADR 001: Agent 레이어 도입 및 비동기 스케일링 전략

* **Status**: Accepted
* **Date**: 2025-12-14
* **Technical Story**: 포트 고갈 문제 해결, 대용량 트래픽 제어, 그리고 실시간성을 보장하는 고도화된 아키텍처로의 전환

## 배경 및 문제점 (Context and Problem Statement)

[ADR-000](./000-base-architecture.md)에서 정의한 초기 아키텍처는 구현이 직관적이었으나, 트래픽이 증가함에 따라 다음과 같은 구조적 한계에 봉착했습니다.

1.  **포트 고갈(Port Exhaustion)**: 컨테이너와 TCP/IP 통신을 하므로 OS의 Ephemeral Port 한계에 의해 동시에 실행 가능한 컨테이너 수가 물리적으로 제한됩니다.
2.  **Manager 병목 현상**: Cold Start 요청 시 `Main`이 `Manager`와 동기(gRPC)로 통신하므로, 요청 폭주 시 가장 무거운 작업을 수행하는 `Manager` 서버가 부하를 견디지 못하고 장애가 발생할 위험이 큽니다.
3.  **메인 서버 부하 가중**: 대용량 로그 데이터가 `Main` 서버를 거쳐서 저장되므로, 정작 중요한 '함수 실행 및 응답' 처리에 쓰여야 할 네트워크 대역폭과 CPU가 낭비됩니다.

## 의사결정 요소 (Decision Drivers)

* **무제한 확장성**: OS 네트워크 스택(Port)의 제약을 받지 않고, 하드웨어 리소스가 허용하는 한 최대한 많은 함수를 동시에 실행할 수 있어야 함.
* **시스템 안정성(Backpressure)**: 트래픽 폭주 시에도 `Manager`가 죽지 않고 자신이 처리할 수 있는 속도로 작업을 수행해야 함.
* **응답 속도 최적화**: Cold Start 대기 중이라도, 다른 곳에서 반납된 컨테이너가 있다면 즉시 이를 낚아채서 실행할 수 있어야 함.
* **관측 가능성(Observability)**: 실시간 로그 스트리밍(SSE)을 지원하면서도 `Main` 서버의 부담을 최소화해야 함.

## 결정 사항 (Decisions)

### 1. Main - Manager 간 비동기 큐 도입 및 경쟁적 소비 (Competitive Consumption)
* **변경**: `Main` -> `Manager` 간의 동기 gRPC 호출을 제거하고, Redis List 기반의 **Producer-Consumer 패턴**을 도입합니다.
* **동작 원리**:
    1.  `Main`은 함수 실행 공간 생성 요청을 `Request Queue`에 `RPUSH` 합니다.
    2.  `Main`은 즉시 응답을 기다리는 것이 아니라, 해당 함수의 `Warm Pool`에 대해 **`BLPOP` (Blocking Wait)** 상태로 대기합니다.
    3.  `Manager`는 큐에서 요청을 하나씩 꺼내 처리한 뒤, 생성된 컨테이너 정보를 `Warm Pool`에 `RPUSH` 합니다.
* **이유 (Key Benefit)**: 이 구조는 **"경쟁적 소비"**를 가능하게 합니다.
    * 함수 A에 대한 요청1(Cold Start 대기 중)과 요청2(실행 중)가 있다고 가정합니다.
    * 요청2가 먼저 끝나서 컨테이너를 `Warm Pool`에 반납(`RPUSH`)하면, **요청1은 Manager가 새 컨테이너를 다 만들 때까지 기다릴 필요 없이, 방금 반납된 컨테이너를 `BLPOP`으로 즉시 획득하여 실행합니다.**
    * 이는 대기 시간을 획기적으로 단축시키며, 불필요한 리소스 대기를 제거하는 가장 강력한 이점입니다.

### 2. 중간 계층 'Agent' 도입 및 UDS(Unix Domain Socket) 통신 전환
* **변경**: `Main`이 컨테이너의 IP:Port로 직접 접근하는 방식을 폐기하고, 중간에 `Agent`를 두어 통신합니다.
    * `Main` <-> `Agent`: **gRPC** (HTTP/2 Multiplexing)
    * `Agent` <-> `Container`: **UDS** (Unix Domain Socket 파일)
* **이유**:
    * **포트 고갈 해결**: 소켓 파일을 식별자로 사용하므로 TCP 포트를 소비하지 않아, 하드웨어 성능이 허용하는 한 무제한에 가까운 동시 실행이 가능합니다.
    * **확장성**: `Main`과 `Agent` 사이는 gRPC를 사용하여, 향후 실시간 로그 스트리밍(Server-Sent Events) 등의 기능을 효율적으로 구현할 수 있습니다.

### 3. Manager 보호를 위한 부하 완충(Backpressure) 적용
* **변경**: `Manager`는 들어오는 요청을 무조건 받는 것이 아니라, Redis Queue(`Request Queue`)를 통해 **자신이 처리 가능한 속도로만(`LPOP`) 작업을 가져갑니다.**
* **이유**: 갑작스러운 트래픽 스파이크(Spike)가 발생해도 요청은 Redis에 안전하게 쌓이며, `Manager` 서버의 과부하 및 다운을 원천적으로 방지합니다.

### 4. 로그 데이터 흐름의 분리 (Log Offloading)
* **변경**: 실행 로그는 `Agent`가 직접 S3에 업로드하고, `Main`에게는 S3 객체 키(Key)와 메타데이터만 반환합니다.
* **이유**: 대용량의 텍스트 데이터(로그)가 `Main` 서버를 통과하지 않도록 하여 네트워크 병목을 제거하고, `Main`은 오직 요청 처리와 스케줄링에만 집중하도록 합니다.

## 결과 및 장단점 (Consequences)

### Positive (장점)
* **Latency 획기적 개선**: `BLPOP`을 활용한 풀 점유 경쟁(Race Condition)을 긍정적으로 활용하여, Cold Start 상황에서도 가장 빨리 가용한 리소스를 즉시 획득함.
* **무한한 동시성**: 포트 제약이 사라져 고사양 서버의 자원을 100% 활용 가능.
* **장애 격리**: `Manager`가 느려지거나 멈춰도, `Main`과 기존 `Warm Container`의 실행에는 영향을 주지 않음.
* **네트워크 효율성**: 로그 데이터 오프로딩으로 `Main` 서버의 트래픽 비용 절감.

### Negative (단점)
* **시스템 복잡도 증가**: `Agent` 컴포넌트가 추가되고, 통신 방식이 다양해짐(Redis Queue, gRPC, UDS).
* **Redis 의존성 심화 및 SPOF 위험**: 큐(Queue)와 워커 풀(Worker Pool) 등 시스템의 핵심 로직이 Redis에 집중되어 있어, Redis 장애 시 전체 서비스가 중단되는 단일 실패 지점(SPOF)이 될 수 있음.
    * **대응 계획**: 이를 방지하기 위해 **Master-Replica 복제 전략**을 도입하여 데이터 유실을 막고 고가용성(High Availability)을 확보할 계획임.

## Architecture Flow Description
**Cold Start**
<img width="1161" height="951" alt="cold_start_001" src="https://github.com/user-attachments/assets/4abc38ee-b3d1-47fb-9093-d8bc5439e510" />

**상세 흐름**

1.  **요청 수신**: `Main` 서버가 Gateway로부터 `func-a` 실행 요청을 수신합니다.
2.  **리소스 확인**: Redis의 `Warm Pool`을 조회합니다. (Miss - 없음)
3.  **비동기 생성 요청 & 대기 (핵심)**:
    * `Main`은 `Request Queue`에 생성 요청을 **`RPUSH`** 합니다.
    * 동시에 `Main`은 `Warm Pool`에 대해 **`BLPOP`** 명령어로 리소스가 들어올 때까지 대기합니다. (이때, 다른 요청이 쓰고 반납한 컨테이너가 들어와도 즉시 획득합니다.)
4.  **작업 수신**: `Manager`는 큐를 구독하고 있다가 요청을 확인(`LPOP`)합니다.
5.  **환경 구성**: `Manager`는 S3에서 코드를 가져와 컨테이너를 생성하고, `Agent`와 통신할 UDS 소켓을 연결합니다.
6.  **리소스 공급**: `Manager`는 생성된 컨테이너 정보(IP + Sock 경로)를 `Warm Pool`에 **`RPUSH`** 합니다.
7.  **리소스 획득**: `Warm Pool`에 데이터가 들어오자마자, 대기 중이던 `Main`의 `BLPOP`이 풀리며 컨테이너 정보를 획득합니다.
8.  **실행 요청**: `Main`은 획득한 정보를 바탕으로 `Agent`에게 gRPC로 함수 실행을 요청합니다.
9.  **함수 실행**: `Agent`는 UDS를 통해 컨테이너 내부의 함수를 실행합니다.
10. **결과 반환**: `Agent`는 실행 결과와 로그키(S3 Key)를 `Main`에게 반환합니다.
    * **(10-A)** `Agent`는 발생한 로그를 S3에 직접 업로드합니다.
11. **리소스 반환**: `Main`은 사용이 끝난 컨테이너 정보를 다시 `Warm Pool`에 **`RPUSH`** 하여 다음 요청이 즉시 사용할 수 있도록 합니다.
    * **(11-A)** `Main`은 실행 메타데이터(시간, 메모리 등)를 DB에 저장합니다.  
  

**Warm Start**
<img width="1161" height="951" alt="warm_start_001" src="https://github.com/user-attachments/assets/6aba0366-a724-4a6c-9e8e-f363f3cbdb6e" />

**상세 흐름**

1.  **요청 수신**: `Main` 서버가 Gateway로부터 `func-a` 실행 요청을 수신합니다.
2.  **리소스 확인**: Redis의 `Warm Pool`을 조회합니다. (`"func-a": ["10.0.1.7|sock_addr"]`)
3.  **실행 요청**: `Main`은 획득한 정보를 바탕으로 `Agent`에게 gRPC로 함수 실행을 요청합니다.
4.  **함수 실행**: `Agent`는 UDS를 통해 컨테이너 내부의 함수를 실행합니다.
5. **결과 반환**: `Agent`는 실행 결과와 로그키(S3 Key)를 `Main`에게 반환합니다.
    * **(5-A)** `Agent`는 발생한 로그를 S3에 직접 업로드합니다.
6. **리소스 반환**: `Main`은 사용이 끝난 컨테이너 정보를 다시 `Warm Pool`에 **`RPUSH`** 하여 다음 요청이 즉시 사용할 수 있도록 합니다.
    * **(6-A)** `Main`은 실행 메타데이터(시간, 메모리 등)를 DB에 저장합니다.
