terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "ap-northeast-1"
}

# ---------------------------------------------------------
# 1. 네트워크 (Public: ALB/NAT, Private: EC2/RDS)
# ---------------------------------------------------------

resource "aws_vpc" "main_vpc" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags = { Name = "Main-VPC" }
}

resource "aws_internet_gateway" "igw" {
  vpc_id = aws_vpc.main_vpc.id
  tags   = { Name = "Main-IGW" }
}

# [Public Subnet] ALB & NAT용 (1a, 1c) - ALB는 2개 AZ 필수
resource "aws_subnet" "public_subnet_a" {
  vpc_id                  = aws_vpc.main_vpc.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = "ap-northeast-1a"
  map_public_ip_on_launch = true
  tags = { Name = "Public-Subnet-1a" }
}

resource "aws_subnet" "public_subnet_c" {
  vpc_id                  = aws_vpc.main_vpc.id
  cidr_block              = "10.0.2.0/24"
  availability_zone       = "ap-northeast-1c"
  map_public_ip_on_launch = true
  tags = { Name = "Public-Subnet-1c" }
}

# [Private Subnet] 애플리케이션 서버용 (1a) - 모든 EC2 배치
resource "aws_subnet" "private_subnet_app" {
  vpc_id            = aws_vpc.main_vpc.id
  cidr_block        = "10.0.10.0/24"
  availability_zone = "ap-northeast-1a"
  tags = { Name = "Private-Subnet-App-1a" }
}

# [Private Subnet] RDS 보조용 (1c)
resource "aws_subnet" "private_subnet_db" {
  vpc_id            = aws_vpc.main_vpc.id
  cidr_block        = "10.0.20.0/24"
  availability_zone = "ap-northeast-1c"
  tags = { Name = "Private-Subnet-DB-1c" }
}

# --- NAT Gateway (Private 서버들이 인터넷을 쓰기 위해 필수) ---
resource "aws_eip" "nat_eip" {
  count  = var.is_dev_mode ? 0 : 1
  domain = "vpc"
  tags   = { Name = "NAT-GW-EIP" }
}

resource "aws_nat_gateway" "nat_gw" {
  count         = var.is_dev_mode ? 0 : 1
  allocation_id = aws_eip.nat_eip[0].id
  subnet_id     = aws_subnet.public_subnet_a.id
  tags          = { Name = "Main-NAT-GW" }
  depends_on    = [aws_internet_gateway.igw]
}

# --- 라우팅 테이블 ---

# Public RT (IGW 연결) - Public Subnet용
resource "aws_route_table" "public_rt" {
  vpc_id = aws_vpc.main_vpc.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.igw.id
  }
  tags = { Name = "Public-RT" }
}

resource "aws_route_table_association" "pub_a" {
  subnet_id      = aws_subnet.public_subnet_a.id
  route_table_id = aws_route_table.public_rt.id
}
resource "aws_route_table_association" "pub_c" {
  subnet_id      = aws_subnet.public_subnet_c.id
  route_table_id = aws_route_table.public_rt.id
}

# Private RT (NAT GW 연결) - Private Subnet용
# 개발 모드일 때는 Private Subnet도 IGW를 타게 해서 인터넷 되게 함 (임시 Public 변환 효과)
resource "aws_route_table" "private_rt" {
  vpc_id = aws_vpc.main_vpc.id
  route {
    cidr_block     = "0.0.0.0/0"
    # 개발모드면 IGW, 배포모드면 NAT 사용
    gateway_id     = var.is_dev_mode ? aws_internet_gateway.igw.id : null
    nat_gateway_id = var.is_dev_mode ? null : aws_nat_gateway.nat_gw[0].id
  }
  tags = { Name = "Private-RT" }
}

resource "aws_route_table_association" "pri_app" {
  subnet_id      = aws_subnet.private_subnet_app.id
  route_table_id = aws_route_table.private_rt.id
}
resource "aws_route_table_association" "pri_db" {
  subnet_id      = aws_subnet.private_subnet_db.id
  route_table_id = aws_route_table.private_rt.id
}

# ---------------------------------------------------------
# 2. 로드 밸런서 (ALB) - 외부 진입점
# ---------------------------------------------------------

# ALB 전용 보안 그룹 (외부 80 포트 허용)
resource "aws_security_group" "sg_alb" {
  name        = "sg_alb"
  description = "ALB Public Access"
  vpc_id      = aws_vpc.main_vpc.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "SG-ALB" }
}

resource "aws_lb" "main_alb" {
  name               = "faas-main-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.sg_alb.id]
  subnets            = [aws_subnet.public_subnet_a.id, aws_subnet.public_subnet_c.id]
  tags               = { Name = "Faas-ALB" }
}

resource "aws_lb_target_group" "tg_gateway" {
  name        = "tg-gateway"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main_vpc.id
  target_type = "instance"

  health_check {
    path                = "/actuator/health"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 2
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main_alb.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.tg_gateway.arn
  }
}

# ---------------------------------------------------------
# 3. IAM & S3 & RDS
# ---------------------------------------------------------

resource "aws_iam_role" "ec2_s3_access_role" {
  name = "faas_ec2_role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

# [수정] 정책 리소스에 두 버킷의 ARN을 모두 추가하여 EC2가 둘 다 접근 가능하게 함
resource "aws_iam_policy" "s3_access_policy" {
  name = "faas_s3_policy"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "s3:PutObject",
        "s3:GetObject",
        "s3:ListBucket",
        "s3:DeleteObject"
      ]
      Resource = [
        # 함수 코드 버킷
        "arn:aws:s3:::${var.s3_function_bucket_name}",
        "arn:aws:s3:::${var.s3_function_bucket_name}/*",
        # 로그 버킷
        "arn:aws:s3:::${var.s3_log_bucket_name}",
        "arn:aws:s3:::${var.s3_log_bucket_name}/*"
      ]
    }]
  })
}

resource "aws_iam_role_policy_attachment" "attach_s3" {
  role       = aws_iam_role.ec2_s3_access_role.name
  policy_arn = aws_iam_policy.s3_access_policy.arn
}

resource "aws_iam_instance_profile" "ec2_profile" {
  name = "faas_ec2_profile"
  role = aws_iam_role.ec2_s3_access_role.name
}

# 함수 코드 저장용 버킷
resource "aws_s3_bucket" "function_bucket" {
  bucket        = var.s3_function_bucket_name
  force_destroy = true
  tags          = { Name = "Faas-Function-Code-S3" }
}

#  로그 저장용 버킷
resource "aws_s3_bucket" "log_bucket" {
  bucket        = var.s3_log_bucket_name
  force_destroy = true
  tags          = { Name = "Faas-Logs-S3" }
}

resource "aws_db_subnet_group" "faas_db_subnet_group" {
  name       = "faas-db-subnet-group"
  subnet_ids = [aws_subnet.private_subnet_app.id, aws_subnet.private_subnet_db.id]
  tags       = { Name = "Faas DB Subnet Group" }
}

# RDS용 보안 그룹
resource "aws_security_group" "sg_internal_db" {
  name        = "sg_internal_db"
  description = "Internal Traffic for RDS"
  vpc_id      = aws_vpc.main_vpc.id

  ingress {
    from_port   = 3306
    to_port     = 3306
    protocol    = "tcp"
    cidr_blocks = [aws_vpc.main_vpc.cidr_block]
  }
  tags = { Name = "SG-Internal-DB" }
}

resource "aws_db_instance" "faas_db" {
  allocated_storage      = 20
  db_name                = "faas"
  engine                 = "mysql"
  engine_version         = "8.0"
  instance_class         = "db.t4g.micro"
  username               = "admin"
  password               = var.db_password
  parameter_group_name   = "default.mysql8.0"
  skip_final_snapshot    = true
  publicly_accessible    = false
  vpc_security_group_ids = [aws_security_group.sg_internal_db.id]
  db_subnet_group_name   = aws_db_subnet_group.faas_db_subnet_group.name
  tags                   = { Name = "Faas-RDS" }
}

# ---------------------------------------------------------
# [핵심] 4. 동적 보안 그룹 & 규칙 (Dynamic Security Groups)
# ---------------------------------------------------------

# SSH용 공통 보안 그룹 (내부 접속 허용)
resource "aws_security_group" "sg_ssh" {
  name        = "sg_ssh"
  description = "SSH Access"
  vpc_id      = aws_vpc.main_vpc.id

  # 개발 모드일 때는 내 IP에서 직접 접속 허용
  # 배포 모드일 때는 내부 VPC(Load Tester 등)에서만 접속 허용
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = var.is_dev_mode ? [var.ssh_ip] : [aws_vpc.main_vpc.cidr_block]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = { Name = "SG-SSH" }
}

# 서버별 전용 보안 그룹 생성
resource "aws_security_group" "per_server_sg" {
  for_each = var.server_configs

  name        = "sg_${each.key}"
  description = "Dynamic Security Group for ${each.key}"
  vpc_id      = aws_vpc.main_vpc.id
  tags        = { Name = "SG-${each.key}" }
}

locals {
  # 역할별 필요한 포트 규칙 (내부 통신용)
  # 외부 접근(8080)은 아래 alb_to_gateway 리소스에서 별도로 처리함
  role_definitions = {
    "gateway" = [
      { port = 8080, cidr = [aws_vpc.main_vpc.cidr_block], desc = "Internal Access" }
    ]
    # [수정] gRPC 포트 추가 (9091)
    "register" = [
      { port = 8081, cidr = [aws_vpc.main_vpc.cidr_block], desc = "Internal Access" },
      { port = 9091, cidr = [aws_vpc.main_vpc.cidr_block], desc = "gRPC Internal Access" }
    ]
    # [수정] gRPC 포트 추가 (9092)
    "invoker" = [
      { port = 8082, cidr = [aws_vpc.main_vpc.cidr_block], desc = "Internal Access" },
      { port = 9092, cidr = [aws_vpc.main_vpc.cidr_block], desc = "gRPC Internal Access" }
    ]
    # [수정] gRPC 포트 추가 (9094)
    "provisioner_agent" = [
      { port = 8083, cidr = [aws_vpc.main_vpc.cidr_block], desc = "Internal Access" },
      { port = 8084, cidr = [aws_vpc.main_vpc.cidr_block], desc = "Internal Access" },
      { port = 9094, cidr = [aws_vpc.main_vpc.cidr_block], desc = "gRPC Internal Access" }
    ]
    "discovery" = [
      { port = 8761, cidr = [aws_vpc.main_vpc.cidr_block], desc = "Internal Access" }
    ]
    "nosql" = [
      { port = 27017, cidr = [aws_vpc.main_vpc.cidr_block], desc = "Internal Access" }
    ]
    "redis" = [
      { port = 6379, cidr = [aws_vpc.main_vpc.cidr_block], desc = "Internal Access" }
    ]
  }

  server_rules_flat = flatten([
    for server_name, config in var.server_configs : [
      for role in config.roles : [
        for rule in try(local.role_definitions[role], []) : {
          key         = "${server_name}-${role}-${rule.port}"
          server_name = server_name
          port        = rule.port
          cidrs       = rule.cidr
          desc        = rule.desc
        }
      ]
    ]
  ])
}

# 내부 통신용 Ingress 규칙 주입
resource "aws_security_group_rule" "dynamic_ingress_rules" {
  for_each = { for rule in local.server_rules_flat : rule.key => rule }

  security_group_id = aws_security_group.per_server_sg[each.value.server_name].id
  type              = "ingress"
  from_port         = each.value.port
  to_port           = each.value.port
  protocol          = "tcp"
  cidr_blocks       = each.value.cidrs
  description       = each.value.desc
}

# ALB -> Gateway 서버(8080) 접속 허용 규칙
# 역할에 'gateway'가 있는 서버만 ALB SG에서의 접근을 허용
resource "aws_security_group_rule" "alb_to_gateway" {
  for_each = { for k, v in var.server_configs : k => v if contains(v.roles, "gateway") }

  security_group_id        = aws_security_group.per_server_sg[each.key].id
  type                     = "ingress"
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.sg_alb.id
  description              = "Allow traffic from ALB"
}

# Outbound 전체 허용
resource "aws_security_group_rule" "dynamic_egress_rules" {
  for_each = var.server_configs

  security_group_id = aws_security_group.per_server_sg[each.key].id
  type              = "egress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
}

# ---------------------------------------------------------
# 5. EC2 인스턴스 생성
# ---------------------------------------------------------

locals {
  user_data_script = <<-EOF
    #!/bin/bash
    apt-get update
    
    apt-get install -y ca-certificates curl gnupg git mysql-client

    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
    echo "deb [arch="$(dpkg --print-architecture)" signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu "$(. /etc/os-release && echo "$VERSION_CODENAME")" stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
    apt-get update
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    usermod -aG docker ubuntu
    echo 'alias docker-compose="docker compose"' >> /home/ubuntu/.bashrc
  EOF
}

data "aws_ami" "ubuntu_24_04" {
  most_recent = true
  owners      = ["099720109477"]
  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }
}

resource "aws_instance" "app_servers" {
  for_each = var.server_configs

  ami                  = data.aws_ami.ubuntu_24_04.id
  instance_type        = each.value.instance_type
  key_name             = var.key_name
  iam_instance_profile = aws_iam_instance_profile.ec2_profile.name
  user_data            = local.user_data_script
  
  # 개발 모드면 Public Subnet, 배포 모드면 Private Subnet 배치
  subnet_id            = var.is_dev_mode ? aws_subnet.public_subnet_a.id : aws_subnet.private_subnet_app.id
  
  # 개발 모드면 공인 IP 자동 할당 (직접 SSH 접속 가능하게)
  associate_public_ip_address = var.is_dev_mode

  root_block_device {
    volume_type = "gp3"
    volume_size = contains(each.value.roles, "gateway") ? 30 : 20
  }

  vpc_security_group_ids = [
    aws_security_group.sg_ssh.id,
    aws_security_group.per_server_sg[each.key].id
  ]

  tags = { Name = each.key }
}

# Gateway 역할이 있는 서버만 자동으로 ALB에 연결
resource "aws_lb_target_group_attachment" "gateway_attach" {
  for_each = { for k, v in var.server_configs : k => v if contains(v.roles, "gateway") }

  target_group_arn = aws_lb_target_group.tg_gateway.arn
  target_id        = aws_instance.app_servers[each.key].id
  port             = 8080
}