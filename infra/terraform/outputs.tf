# ALB 주소 (항상 유효)
output "alb_dns_name" {
  value       = aws_lb.main_alb.dns_name
  description = "서비스 접속 주소 (ALB)"
}

# 서버 공인 IP 목록 (개발 모드일 때만 유효)
output "server_public_ips" {
  value = {
    for k, v in aws_instance.app_servers : k => v.public_ip
  }
  description = "개발 모드일 때 접속 가능한 공인 IP 목록 (배포 모드면 빈 값)"
}

# 서버 사설 IP 목록 (항상 유효)
output "server_private_ips" {
  value = {
    for k, v in aws_instance.app_servers : k => v.private_ip
  }
  description = "내부 통신용 서버 사설 IP 목록"
}

# RDS 및 S3 출력
output "rds_endpoint" {
    value = aws_db_instance.faas_db.endpoint
}