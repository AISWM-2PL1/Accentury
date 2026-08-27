# prod 환경 값. 환경 간 차이는 이 파일의 diff로 전부 열거된다 (KAN-140 AC).
env    = "prod"
region = "ap-northeast-2"

domain                 = "accentury.app"
hosted_zone_name       = "accentury.app"
acm_certificate_domain = "accentury.app"

vpc_cidr             = "10.0.0.0/16"
azs                  = ["ap-northeast-2a", "ap-northeast-2c"]
public_subnet_cidrs  = ["10.0.0.0/24", "10.0.1.0/24"]
private_subnet_cidrs = ["10.0.10.0/24", "10.0.11.0/24"]

instance_type     = "t3.small"
db_instance_class = "db.t4g.micro"

ssm_prefix = "/accentury/prod"

# prod 역할은 ECR push 불가 (KAN-128 승격 모델). staging이 검증한 SHA만 반영한다.
ci_image_push = false

# prod는 실수로 지워지면 안 된다. destroy하려면 먼저 이 값을 false로 apply한다.
db_deletion_protection = true
db_skip_final_snapshot = false
