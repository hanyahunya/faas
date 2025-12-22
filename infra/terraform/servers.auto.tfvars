# servers.auto.tfvars

server_configs = {
  # Server A: 풀스택 (Gateway, Apps, DBs)
  "Server-A" = {
    instance_type = "m7i-flex.large"
    roles         = ["gateway", "register", "invoker", "nosql", "redis"]
  }

  "Server-B" = {
    instance_type = "m7i-flex.large"
    roles         = ["provisioner_agent"]
  }

  # Server D: 디스커버리 전용
  "Server-D" = {
    instance_type = "t3.small"
    roles         = ["discovery"]
  }
}