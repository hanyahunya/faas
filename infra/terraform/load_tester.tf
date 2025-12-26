# ---------------------------------------------------------
# [Load Tester] 부하 테스트용 인스턴스 (k6)
# ---------------------------------------------------------

variable "enable_load_test" {
  description = "부하 테스트기를 켤지 말지 결정 (true: 생성, false: 삭제)"
  type        = bool
}

# 2. 보안 그룹 (테스트기 전용)
resource "aws_security_group" "sg_load_tester" {
  name        = "sg_load_tester"
  description = "Security Group for k6 Load Tester"
  vpc_id      = aws_vpc.main_vpc.id

  # 내 PC에서 SSH 접속 허용
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.ssh_ip]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "SG-Load-Tester" }
}

# 3. k6 자동 설치 스크립트
locals {
  k6_user_data = <<-EOF
    #!/bin/bash
    apt-get update
    apt-get install -y ca-certificates curl gnupg git vim

    # k6 공식 레포지토리 등록 및 설치
    gpg -k
    gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
    echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | tee /etc/apt/sources.list.d/k6.list
    apt-get update
    apt-get install -y k6

    # (선택) Docker 설치
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    chmod a+r /etc/apt/keyrings/docker.gpg
    echo "deb [arch="$(dpkg --print-architecture)" signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu "$(. /etc/os-release && echo "$VERSION_CODENAME")" stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
    apt-get update
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    usermod -aG docker ubuntu
  EOF
}

# 4. 인스턴스 생성
resource "aws_instance" "load_tester" {
  # enable_load_test가 true일 때만 1개 생성, false면 0개(삭제)
  count = var.enable_load_test ? 1 : 0

  ami           = data.aws_ami.ubuntu_24_04.id 
  
  # 부하 테스트용 인스턴스
  instance_type = "c7i.xlarge" 
  
  key_name      = var.key_name
  
  # 접속을 위해 Public Subnet에 배치
  subnet_id     = aws_subnet.public_subnet_a.id 
  
  vpc_security_group_ids = [aws_security_group.sg_load_tester.id]
  user_data              = local.k6_user_data

  tags = { Name = "Load-Tester-k6" }
}

# 5. 테스트기 IP 주소
output "load_tester_ip" {
  value       = length(aws_instance.load_tester) > 0 ? aws_instance.load_tester[0].public_ip : "Load Tester is OFF (Set 'default = true' in load_tester.tf)"
  description = "부하 테스트기 접속 IP (SSH)"
}