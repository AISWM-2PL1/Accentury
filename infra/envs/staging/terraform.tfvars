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

# backend는 EC2가 아니라 Fargate 서비스다 (KAN-165). 태스크 크기(0.5 vCPU / 2 GB)는 두 환경이 같아 모듈 기본값이다.
db_instance_class = "db.t4g.micro"

# AI 추론 호스트 (KAN-36). A단계 스텁 모드부터 c7i.xlarge (2026-09-01 결정). 루트 볼륨은 실모델
# 전환(B단계)에서 40으로 올린다.
ai_instance_type    = "c7i.xlarge"
ai_root_volume_size = 20

ssm_prefix = "/accentury/staging"

# 이미지는 staging 파이프라인만 만든다 (KAN-128 승격 모델). prod는 그 SHA를 고르기만 한다.
ci_image_push = true

# staging은 부수고 다시 짓는 환경이다. 삭제 보호 없이, 최종 스냅샷도 남기지 않는다.
db_deletion_protection = false
db_skip_final_snapshot = true

# WAF (KAN-149). Count 관찰(2026-08-28부터 staging 사이클 6회, 정상 트래픽 오탐 0건)을 끝내고
# 2026-09-03 KAN-169에서 Block으로 전환해 실증했다. 두 환경 같은 값이다 - staging에서 본 결과가
# prod에 그대로 적용되어야 관찰의 의미가 있다 (2026-08-28 확정).
waf_enforce    = true
waf_rate_limit = 300
