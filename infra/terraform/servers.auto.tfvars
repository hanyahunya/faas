server_configs = {
  "core-01" = {
    instance_type = "c7i.xlarge"
    roles         = ["gateway", "invoker"]
  }
  "core-02" = {
    instance_type = "c7i.xlarge"
    roles         = ["gateway", "invoker"]
  }

  "data-01" = {
    instance_type = "m8i.xlarge"
    roles         = ["nosql", "redis"]
  }


  "discovery-01" = {
    instance_type = "t3.small"
    roles         = ["discovery"]
  }

  "worker-01" = {instance_type = "m8i.4xlarge", roles = ["provisioner_agent"]}
  "worker-02" = {instance_type = "m8i.4xlarge", roles = ["provisioner_agent"]}
  "worker-03" = {instance_type = "m8i.4xlarge", roles = ["provisioner_agent"]}
  "worker-04" = {instance_type = "m8i.4xlarge", roles = ["provisioner_agent"]}
}