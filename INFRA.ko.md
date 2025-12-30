## ☁️ Infrastructure & Deployment

이 프로젝트는 **AWS** 환경에서 운영되며, 모든 인프라는 **Terraform**을 사용하여 코드로 관리(IaC)됩니다.
실제 서비스는 현재 [https://hanyahunya.com](https://hanyahunya.com) 도메인에서 테스트 가능합니다.

> 🔗 **Infrastructure Source Code**: [infra/terraform/main.tf](https://github.com/hanyahunya/faas/blob/main/infra/terraform/main.tf)

> ⚠️ **Note**: 현재 보안을 위해 회원가입 로직은 **관리자 승인 방식(Admin Approval)**으로 운영되고 있습니다. 테스트 계정 발급을 원하시는 경우, [hanyahunya13@gmail.com](mailto:hanyahunya13@gmail.com)으로 문의해주시기 바랍니다.

### 1. Network Topology & Security Strategy

보안과 확장성을 위해 **VPC(Virtual Private Cloud)**를 철저히 분리하여 설계했습니다.

* **Public Subnet**: 외부 트래픽을 받는 **Application Load Balancer (ALB)**와 Private 서버의 아웃바운드 통신을 위한 **NAT Gateway**만 배치됩니다.
* **Private Subnet**: 실제 애플리케이션이 구동되는 모든 EC2 인스턴스와 RDS는 외부 접근이 차단된 Private Subnet에 배치되어 보안을 강화했습니다.
* **Cloudflare Integration**: Terraform의 `http` provider를 사용하여 Cloudflare의 최신 IP 대역을 실시간으로 가져옵니다. ALB의 보안 그룹(Security Group)은 오직 **Cloudflare를 경유한 트래픽만 허용**하도록 동적으로 설정되어, DDoS 공격 및 비정상적인 접근을 원천 차단합니다.

### 2. Configuration-Driven Infrastructure (Dynamic Provisioning)

서버의 역할(Role)과 스펙을 `server_configs` 변수 하나로 관리하며, Terraform이 이를 해석하여 필요한 리소스를 자동으로 생성합니다.

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
