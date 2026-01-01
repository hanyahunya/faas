# ⚡️ High-Performance Custom FaaS Platform

> **ポート枯渇のない無限のスケーラビリティと、競争的消費 (Competitive Consumption) モデルによるコールドスタートの極小化**

本プロジェクトは、AWS Lambdaのような **FaaS (Function as a Service)** プラットフォームを独自に実装したものです。初期アーキテクチャ(ADR-001)をベースに、**Java Virtual Threads、Go言語 (Golang)、Unix Domain Socket、cgroupの直接制御**などの技術を導入し、極限のパフォーマンスと安定性を確保しました。

* **開発期間**: 2025.12.17 ~ 2025.12.27

## 🚀 Key Performance (k6 Load Test)
<img width="2250" height="864" alt="화면 캡처 2025-12-28 175058" src="https://github.com/user-attachments/assets/ee0d0b2c-6aa4-47a6-9a6d-e29154e48918" />


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
本プロジェクトの最大の特徴は、**「Provisionerがコールドスタートのために新しいコンテナを作成している間でも、他のリクエストが使い終わって返却されたコンテナがあれば、即座にそれを奪い取って(Stealing)実行する」**という点です。

1.  **Warm Start 試行**: `LPOP` でアイドル状態のコンテナを確認（成功すれば即時実行）。
2.  **Cold Start 要求**: 失敗時、Provisionerに生成リクエスト(`RPUSH`)を送り、同時に `BLPOP` で待機。
3.  **競争的獲得**: Provisionerが新コンテナを作る前に、別リクエストが完了してコンテナが返却されれば、`BLPOP` が即座に反応してそのコンテナを獲得。
    * 👉 **結果**: コールドスタートリクエストであっても、Warm Start並みの速度で処理が可能。

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
* ユーザー定義ライブラリのサポート (Layer概念の導入)
* モニタリングダッシュボードの高度化 (Prometheus/Grafana連携)
  
