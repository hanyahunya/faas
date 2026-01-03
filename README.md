# ⚡️ High-Performance Custom FaaS Platform

> **ポート枯渇のない無限のスケーラビリティと、競争的消費 (Competitive Consumption) モデルによるコールドスタートの極小化**

本プロジェクトは、AWS Lambdaのような **FaaS (Function as a Service)** プラットフォームを独自に実装したものです。初期アーキテクチャ(ADR-001)をベースに、**Java Virtual Threads、Go言語 (Golang)、Unix Domain Socket、cgroupの直接制御**などの技術を導入し、極限のパフォーマンスと安定性を確保しました。

* **開発期間**: 2025.12.17 ~ 2025.12.27

## 📚 Table of Contents
- [🚀 主要パフォーマンス (Key Performance)](#-key-performance-k6-load-test)
- [🏗 システムアーキテクチャ (System Architecture)](#-system-architecture)
  - [コアコンポーネント (Core Components)](#1-core-components)
  - [コアロジック: 競争的消費 (Core Logic: Competitive Consumption)](#2-コアロジック-コールドスタートの最小化-competitive-consumption)
  - [リクエスト処理フロー (Request Lifecycle)](#3-リクエスト処理フロー-request-lifecycle)
- [🛠 技術スタック (Tech Stack)](#-tech-stack)
- [🔧 トラブルシューティングと最適化 (Troubleshooting & Optimization)](#-troubleshooting--optimization-dev-log)
- [🔮 今後の展望 (Future Works)](#-future-works)

---

## 🚀 Key Performance (k6 Load Test)
<img width="2250" height="864" alt="Load Test Result" src="https://github.com/user-attachments/assets/ee0d0b2c-6aa4-47a6-9a6d-e29154e48918" />

* **Max Throughput**: 19,000+ RPS
* **Scenario**: 0 to 2,000+ VUs Ramp-up Test
* **Latency (P95)**: 31.16ms
* **Success Rate**: 99.99% (236万リクエスト中、失敗はわずか169件)
* **特徴**: コールドスタート状況下でも「コンテナ再利用の競争」メカニズムにより、待機時間を劇的に短縮。

---

## 🏗 System Architecture

本システムは **ヘキサゴナルアーキテクチャ (Hexagonal Architecture)** に基づいて設計されており、ドメインロジックの保護と外部依存（Docker, Redis, S3など）の管理を容易にしています。

### 1. Core Components
* **Gateway (Spring WebFlux)**:
    * すべてのリクエストのエントリーポイント。非同期/ノンブロッキング処理に特化したWebFluxを採用。
    * JWT検証・解析後に `X-Header` を注入し、Eurekaを通じた動的ルーティングを実行。
* **Registry (Hexagonal)**:
    * 関数の登録およびセキュリティ検証を担当。言語ごとに抽象化された検証ロジックを適用。
    * **MySQL採用**: ユーザー情報や関数メタデータ間の関係性(Relation)を明確にし、厳格なデータ整合性(ACID)を保証するためRDBMSを使用。
    * 悪意のあるコードを検査後、S3へアップロードし、実行メタデータ(AccessKey)を暗号化して保存。
    * 関数更新時はRedis Pub/Subを利用してInvokerのキャッシュを無効化。
* **Invoker (Java Virtual Threads)**:
    * **中核となるスケジューラ**。関数実行リクエストを受け、利用可能なコンテナを確保して実行を命令。
    * gRPCによる高速通信（HTTPハンドシェイクのオーバーヘッドを排除）。
    * Redis List (`LPOP`, `BLPOP`) を活用した **競争的消費モデル** の実装。
* **Provisioner (Java Virtual Threads)**:
    * **コンテナライフサイクル管理**。Redisパーティショニングのためのリーダー選出アルゴリズムを内蔵。
    * コールドスタート要求時、Dockerコンテナ生成(Network None)、ボリュームマウント、UDS接続を行い、Pause状態で待機。
    * 仮想スレッドを活用した非同期タスク処理と動的スケーリングに対応。
* **Agent (Go / Sidecar)**:
    * **Go言語 (Golang) 実装**: UDS通信と高トラフィック処理に有利なGoの並行処理モデル(Goroutines)とシステムプログラミング性能を活用。
    * 実際に関数が実行されるホスト上に配置され、**cgroupの直接制御**による超高速 Unpause/Pause (Docker Daemonのオーバーヘッド排除) を遂行。
    * UDS (Unix Domain Socket) を通じてコンテナ内部と通信し、実行ログはS3へ直接オフロード。

### 2. コアロジック: コールドスタートの最小化 ("Competitive Consumption")
本プロジェクトの最大の特徴は、「Provisionerがコールドスタートのために新しいコンテナを作成している間でも、他のリクエストが使い終わって返却されたコンテナがあれば、即座にそれを奪い取って(Stealing)実行する」という点です。

### 3. リクエスト処理フロー (Request Lifecycle)

以下の3つのシナリオにおける、アーキテクチャ図 `(1)` ~ `(11)` の詳細フローです。

<img width="1161" height="887" alt="arch" src="https://github.com/user-attachments/assets/7a9b4b01-8374-4ca4-9232-338d159ddac4" />

## 🟦 シナリオ A: Cold Start (Standard Flow)
新しいコンテナを生成して実行する、最も基本的なフローです。

* **(1) 関数実行のためのアクセスキー検証 (Invoker -> Registry)**
    * **キャッシング戦略**: 検証結果はキャッシュされ、原本(Registry)に変更が生じた際は即座にキャッシュを削除するロジックを実装。
    * **gRPC通信の採用**: 大量のリクエストが発生する際、HTTP/1.0ではリクエスト毎に3-way handshakeが発生しリソースの浪費となるため、これを防ぐ目的で採用。
* **(2) 実行可能なリソースの有無を確認 (Invoker)**
    * **LPOPの採用**: `EXISTS` での確認後に `POP` を行うと、その隙に他のリクエストにリソースを横取りされる(Race Condition)可能性があるため、`LPOP` を使用して即座に取得する方式を採用。
    * **結果**: リソースなし(Miss) → Cold Startプロセスへ移行。
* **(3) Provisionerサーバーへの実行環境生成リクエスト (Invoker -> Provisioner)**
    * **ハッシュベースのパーティショニング**: 「関数ID」と「関数ID:secondary」をハッシュ化して16384のパーティションに振り分け、常に同一のProvisionerサーバー群がリクエストを担当するように設計。
    * **負荷分散 (Power of Two Choices)**: 特定のサーバーへのトラフィック集中を防ぐため、2つのハッシュ候補のうち `LLEN` でキューの長さを比較し、より余裕のある(空いている)パーティションへ処理をリクエスト。
* **(3-1) 実行環境生成の待機 (Blocking Wait)**
    * **Java Virtual Threads**: 物理スレッドを占有しないJava Virtual Threadsを採用し、数千の同時待機(`BLPOP`)を低負荷で実現。
* **(4) ソースコードの取得と展開 (Provisioner)**
    * **S3ダウンロード遅延の最小化**: 同一関数を同じサーバーで処理させることでキャッシュヒット率を高め、S3からのコード取得時間を短縮。すでにローカルに解凍済みのファイルがある場合は、ダウンロードをスキップして再利用。
    * **同時性管理 (ConcurrentHashMap)**: キャッシュがない状態で同一関数への同時リクエストが集中した場合、`ConcurrentHashMap` を活用して重複ダウンロードを防ぎ、スレッドセーフに処理。
    * **キャッシュGC**: 最終使用時点から一定時間が経過したキャッシュファイルは、独自のガベージコレクタにより自動的に削除されディスク領域を管理。
* **(5) 実行環境(コンテナ)の生成 (Provisioner)**
* **(6) 実行環境情報の登録 (Provisioner -> Invoker)**
    * **接続情報の通知**: 生成完了後、Invokerが `BLPOP` で待機しているキュー(`func:idle:{func_id}`)に対し、実行環境への接続情報（Agent IP, UDSパス）を `RPUSH`。
    * **同一ホスト配置戦略**: Provisioner, Agent, コンテナは必ず同一ホスト上に配置されるアーキテクチャであるため、Provisionerは自身のIP（Host Network）と、コンテナ制御用の `function.sock` パスを含めて通知。
* **(7) 実行環境情報の獲得 (Invoker)**
* **(8) 実行命令 (Invoker -> Agent)**
* **(9) 関数実行 (Agent -> 関数実行環境)**
* **(10) 実行結果の応答 (Agent -> Invoker)**
    * **メタデータ返却**: 関数の実行結果（戻り値）、実際の実行時間、メモリ使用量、そしてログが保存されるS3のオブジェクトキー情報をgRPCで即座に応答。
* **(10-Async) ログの非同期保存 (Agent -> S3)**
    * **I/O分離**: ユーザーへの応答(10)とは独立した非同期プロセス(Goroutines)でログをS3へアップロードし、ログ保存のレイテンシがレスポンスタイムに影響しないよう最適化。
* **(11) 実行環境の返却 (Invoker -> Redis)**
    * **キューへの返還**: 実行が完了したコンテナの接続情報を、即座にキュー(`func:idle:{func_id}`)へ `RPUSH` し、リソースを再利用可能な状態に戻す。
* **(11-Async) 実行ログの保存 (Invoker -> MongoDB)**
    * **詳細メトリクスの記録**: ユーザー応答後、バックグラウンドで実行タイプ(`COLD`/`WARM`)、メモリ使用量、成功可否などの詳細データをMongoDBへ保存。
    * **パフォーマンス分析**: Agentが測定した純粋な実行時間(`durationMs`)と、Invokerが計測した全体処理時間(`totalProcessingTimeMs`)、およびコールドスタート遅延(`coldStartDurationMs`)を区別して記録し、各フェーズのオーバーヘッドを可視化。
    * **ログ連携**: S3に保存されたログファイルのキー(`logS3Key`)も保存し、メタデータと実ログの紐付けを行う。
 
> **Note**: 手順 `(2)LPOP`, `(3-1)BLPOP`, `(6)RPUSH`, `(7)BLPOP`, `(11)RPUSH` は、すべて同一のRedisキュー (`func:idle:{func_id}`) を対象に操作を行っています。

## 🟩 シナリオ B: Warm Start (Fast Track)
すでに待機中のコンテナが存在する場合のフローです。

* **(1) Cold Startと同じ**: アクセスキー検証。
* **(2) 実行可能なリソースの有無を確認 (Hit)**: `LPOP` が成功し、待機中のコンテナ(`IP:Port`)を即座に取得。
* **(3) ~ (7) Skip**: 新規生成プロセスを省略。
* **(8) ~ (11) Cold Startと同じ**: 取得したコンテナに対して即座に実行命令(`gRPC`)を送り、以後は通常通りのフロー(実行、ログ保存、返却)で処理。

## 🟧 シナリオ C: Stealing (状況によりWarm Start級の速度が出るCold Start)
Cold Startとして待機中(`3-1`)に、他のリクエストがコンテナを返却(`11`)した場合のフローです。 ( 形式上はCold Startですが、Warm Startと同等の速度を実現できるフローです。)

* **(1) ~ (3) Cold Startと同じ**: リソースがなく、生成リクエストを送る。
* **(3-1) 待機 (Blocking Wait)**: `BLPOP` でProvisionerの完了を待っている状態。
* **(11) コンテナ返却 (別リクエスト)**: **(ここで割り込み発生)** 別のリクエストが処理を終え、Redisにコンテナを返却(`RPUSH`)。
* **(7) 競争的獲得 (Stealing)**: Provisionerが新しいコンテナを作るよりも早く、(11)で戻ってきたコンテナを `BLPOP` が検知して獲得。
* **(8) ~ (11) 実行へ**: 新規作成を待たずに即時実行。
    * 👉 **効果**: ユーザーにとってはWarm Startと同等のレスポンス速度を実現。

> **Note (自然なスケールアウト)**: このプロセスが完了すると、最終的にキュー (`func:idle:{func_id}`) には **2つのコンテナ（リクエストが使用して返却した分 + 遅れてProvisionerが新規作成した分）** が残ります。これにより、システムはトラフィックの需要に合わせて自動的かつ自然にスケールアウト（予備リソースの確保）します。
---

## 🛠 Tech Stack

| Category | Technology | Reason for Selection |
| :--- | :--- | :--- |
| **Language** | Java 21+, **Go** | Java(VT)はI/Oブロッキング最小化、**Go(Agent)**は高トラフィック処理およびシステム制御に最適化 |
| **Framework** | Spring Boot, WebFlux | Gatewayの高い同時実行処理能力と安定したエコシステム |
| **Architecture** | Hexagonal | ビジネスロジックとインフラ技術(Docker, S3)の徹底的な分離 |
| **Messaging** | Redis (List, Pub/Sub) | 超高速キュー管理および原子的操作(`LPOP`, `RPUSH`)の保証 |
| **Communication** | gRPC, UDS | HTTPハンドシェイクコストの排除とTCPポート枯渇問題の解決(UDS) |
| **DB (Log/Meta)** | **MongoDB**, **MySQL** | **MySQL**: データ整合性の保証。 **MongoDB**: 大量の実行ログ書き込み(Write)性能の確保。 |
| **Infra** | Docker, AWS S3 | 隔離された実行環境の提供およびコード・ログの低コスト保存 |

---

## 🔧 Troubleshooting & Optimization (Dev Log)

負荷テスト(k6)の過程で発生したボトルネックを解決した記録です。

### 1. Docker Daemon CPU使用率の爆発 (Agent)
* **問題**: 関数実行前後に `docker unpause/pause` コマンドをデーモンAPI経由で実行したところ、トラフィック集中時にデーモンのCPU使用率が80%を超過。
* **解決**: Docker Daemonを経由せず、Linuxの **cgroupファイルシステム(`fs`)に直接アクセス**して状態を制御。
* **結果**: CPU使用率を平均30%未満に低減。

### 2. Too Many Open Files エラー (Gateway)
* **問題**: 負荷テスト時に仮想ユーザー(VUs)が増加すると、Linuxのデフォルトファイルディスクリプタ制限(1024)に達し、接続拒否が発生。
* **解決**: OSの `ulimit -n` 設定を通じてファイルオープン制限(File Descriptor Limit)を大幅に引き上げることで解決。

### 3. ソケットファイル生成のレースコンディション (Provisioner)
* **問題**: Provisionerがコンテナを生成しUDSファイルをマウントする前にInvokerへ「準備完了」応答を送ってしまい、Agentがファイルを見つけられないエラーが発生。
* **解決**: 単純な待機ではなく、OSレベルで該当 `.sock` **ファイルの生成イベントを検知(Listen)**するロジックを仮想スレッドで実装して解決。

### 4. Docker Connection Pool 枯渇と速度低下
* **問題**: コールドスタートが集中した際、Docker Connection Poolが枯渇するか、逆に増やしすぎるとデーモンの反応速度が低下。
* **解決**: 適切なConnection Poolサイズをベンチマークして設定し、不要なI/Oを非同期へ転換。

---

## 🔮 Future Works
* **ユーザー定義ライブラリのサポート (Layer概念の導入)**
* **ランタイムベースのコンテナ統合 (Per-Runtime Isolation)**
    * **構想**: 現在の「特定の関数専用コンテナ」モデルから、「特定の言語・バージョン（例: `python:3.10`）専用コンテナ」にて、異なる関数（AとB）が実行環境を共有するモデルへの進化。
    * **目的**: 本プロジェクトの核である「競争的消費 (Competitive Consumption)」の適用範囲を関数単位からランタイム単位へ拡張し、リソース効率とスループットを最大化すること。
    * **課題と展望**: 開発段階でも検討しましたが、セキュリティ（Noisy Neighbor問題、メモリ分離）や実行後の環境初期化（Clean-up）の複雑さを考慮し、今回は安定性を優先しました。今後はキュー構造を `func:idle:{func_id}` から `func:idle:{runtime_id}` へ移行し、これらの技術的課題を解決していく予定です。
