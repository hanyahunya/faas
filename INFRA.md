## ☁️ Infrastructure & Deployment

本プロジェクトは **AWS** 環境で運用され、すべてのインフラは **Terraform** を使用してコード管理 (IaC) されています。
実際のサービスは現在 [https://hanyahunya.com](https://hanyahunya.com) ドメインにてテスト可能です。

> 🔗 **Infrastructure Source Code**: [infra/terraform/main.tf](https://github.com/hanyahunya/faas/blob/main/infra/terraform/main.tf)

> ⚠️ **Note**: 現在、セキュリティのため会員登録ロジックは**管理者承認制 (Admin Approval)** を採用しています。テスト用アカウントの発行をご希望の方は、お手数ですが [hanyahunya13@gmail.com](mailto:hanyahunya13@gmail.com) までお問い合わせください。

### 1. Network Topology & Security Strategy

セキュリティと拡張性のために、**VPC (Virtual Private Cloud)** を徹底的に分離して設計しました。

* **Public Subnet**: 外部トラフィックを受け取る **Application Load Balancer (ALB)** と、Privateサーバーのアウトバウンド通信のための **NAT Gateway** のみが配置されます。
* **Private Subnet**: 実際のアプリケーションが稼働するすべてのEC2インスタンスとRDSは、外部アクセスが遮断されたPrivate Subnetに配置し、セキュリティを強化しました。
* **Cloudflare Integration**: Terraformの `http` providerを使用して、Cloudflareの最新IP帯域をリアルタイムで取得します。ALBのセキュリティグループ (Security Group) は、**Cloudflareを経由したトラフィックのみを許可**するように動的に設定され、DDoS攻撃や不正なアクセスを根本から遮断します。

### 2. Configuration-Driven Infrastructure (Dynamic Provisioning)

サーバーの役割 (Role) とスペックを `server_configs` 変数一つで管理し、Terraformがこれを解析して必要なリソースを自動的に生成します。

```hcl
# Terraform Variable Example (Infrastructure as Code)
server_configs = {
  "gateway-01"   = { instance_type = "c7i.xlarge",  roles = ["gateway"] }

  "invoker-01"   = { instance_type = "c7i.xlarge",  roles = ["invoker"] }
  "invoker-02"   = { instance_type = "c7i.xlarge",  roles = ["invoker"] }

  "registry-01"  = { instance_type = "c7i.large",   roles = ["register"] }

  "data-01"      = { instance_type = "r8i.xlarge",  roles = ["nosql", "redis"] }

  "discovery-01" = { instance_type = "t3.small",    roles = ["discovery"] }

  "worker-01"    = { instance_type = "m8i.4xlarge", roles = ["provisioner_agent"] }
  "worker-02"    = { instance_type = "m8i.4xlarge", roles = ["provisioner_agent"] }
}
