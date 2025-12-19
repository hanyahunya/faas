# 개발 모드 스위치 변수
variable "is_dev_mode" {
  description = "개발 모드 여부 (true: 서버를 Public에 배치, false: Private에 배치)"
  type        = bool
}

variable "key_name" {
  description = "AWS 키페어 이름"
  type        = string
}

variable "db_password" {
  description = "RDS 데이터베이스 비밀번호"
  type        = string
  sensitive   = true
}

variable "ssh_ip" {
  description = "SSH 접속을 허용할 관리자 IP (예: 1.2.3.4/32)"
  type        = string
}

variable "s3_function_bucket_name" {
  description = "함수 코드 저장용 버킷 이름"
  type        = string
}

variable "s3_log_bucket_name" {
  description = "로그 저장용 버킷 이름"
  type        = string
}

variable "allowed_external_cidrs" {
  description = "서비스 접속을 허용할 외부 IP 대역 리스트"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "server_configs" {
  description = "서버별 이름, 사양, 역할 정의"
  type = map(object({
    instance_type = string
    roles         = list(string)
  }))
}