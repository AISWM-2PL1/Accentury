# staging 환경 값. 환경 간 차이는 이 파일의 diff로 전부 열거된다 (KAN-140 AC).
env    = "staging"
region = "ap-northeast-2"

domain                 = "staging.accentury.app"
hosted_zone_name       = "accentury.app"
acm_certificate_domain = "accentury.app"

vpc_cidr             = "10.1.0.0/16"
azs                  = ["ap-northeast-2a", "ap-northeast-2c"]
public_subnet_cidrs  = ["10.1.0.0/24", "10.1.1.0/24"]
private_subnet_cidrs = ["10.1.10.0/24", "10.1.11.0/24"]

instance_type     = "t3.small"
db_instance_class = "db.t4g.micro"

ssm_prefix = "/accentury/staging"

# staging은 부수고 다시 짓는 환경이다. 삭제 보호 없이, 최종 스냅샷도 남기지 않는다.
db_deletion_protection = false
db_skip_final_snapshot = true
