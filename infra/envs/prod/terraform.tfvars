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

# AI 추론 호스트 (KAN-36). A단계 스텁 모드부터 c7i.xlarge (2026-09-01 결정). 루트 볼륨은 실모델
# 전환(B단계)에서 40으로 올린다.
ai_instance_type    = "c7i.xlarge"
ai_root_volume_size = 20

ssm_prefix = "/accentury/prod"

# prod 역할은 ECR push 불가 (KAN-128 승격 모델). staging이 검증한 SHA만 반영한다.
ci_image_push = false

# prod는 실수로 지워지면 안 된다. destroy하려면 먼저 이 값을 false로 apply한다.
db_deletion_protection = true
db_skip_final_snapshot = false

# WAF (KAN-149). staging의 Count 관찰 결과가 티켓에 기록된 뒤에만 true로 바꾼다. 두 환경 같은
# 값이다 - staging에서 본 결과가 prod에 그대로 적용되어야 관찰의 의미가 있다 (2026-08-28 확정).
waf_enforce    = false
waf_rate_limit = 300
