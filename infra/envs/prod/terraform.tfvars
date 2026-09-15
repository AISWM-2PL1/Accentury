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

# backend는 EC2가 아니라 Fargate 서비스다 (KAN-165). 태스크 크기(0.5 vCPU / 2 GB)는 두 환경이 같아 모듈 기본값이다.
db_instance_class = "db.t4g.micro"

# AI 추론 호스트 (KAN-36). A단계 스텁 모드부터 c7i.xlarge (2026-09-01 결정). B단계(실모델, 2026-09-10)에서도
# 유지한다 - KAN-57 채택안(Whisper bf16 + MFA align_one)의 1건 P95가 11.1초로 판정 게이트의 6초를 넘지만,
# GPU(g4dn.xlarge)가 줄이는 것은 Whisper 6초뿐이고 MFA 3.9초는 CPU 작업이라 어느 인스턴스로도 3초(NFR-PF-01)에
# 못 닿는다. 상향(c7i.2xlarge 또는 GPU)은 NFR 완화 논의와 KAN-204 stageMs(Whisper 대 MFA 비율)를 본 뒤 정한다.
# RSS 최대 6.19GB는 8GB의 77%로 KAN-57 메모리 축(75%)을 살짝 넘지만 동시 처리가 1건 고정이라 그대로 둔다.
ai_instance_type = "c7i.xlarge"
# 루트 볼륨 40GiB (B단계). 실모델 ai 이미지는 7.02GB(2026-09-10 staging 실측, 모델 베이스 4.12GB 위)이고 reload 중
# 옛 SHA와 새 SHA가 공존하는 데다 pull이 압축 레이어를 임시로 한 벌 더 풀어 순간 최대치가 약 2.5(OS와 docker) +
# 7 x 2 + 4 = 21GB다. 20GB에서는 pull이 디스크 부족으로 실패할 수 있어(실측 여유 11.8GB) 두 배로 올린다.
ai_root_volume_size = 40
# AI 호스트 오토스케일링 상한 (KAN-201, 2026-09-11 결정). 평시 1대, 진행 중 분석 6건 이상 2분 연속이면 +1, 1건 이하
# 15분 연속이면 -1. 내부 ALB(least_outstanding_requests)가 backend 태스크 3개의 동시 호출을 빈 인스턴스로 나눈다.
ai_max_size = 3

# 학습 데이터 S3는 staging 전용이다 (KAN-201). prod는 FR-DP-01 그대로 - 원본 음성이 어디에도 남지 않는다.
training_bucket_enabled = false

ssm_prefix = "/accentury/prod"

# prod 역할은 ECR push 불가 (KAN-128 승격 모델). staging이 검증한 SHA만 반영한다.
ci_image_push = false

# prod는 실수로 지워지면 안 된다. destroy하려면 먼저 이 값을 false로 apply한다.
db_deletion_protection = true
db_skip_final_snapshot = false

# WAF (KAN-149). staging의 Count 관찰 결과(오탐 0건)와 Block 전환 실증이 KAN-169에 기록됐으므로
# prod도 처음부터 Block이다. 두 환경 같은 값이다 - staging에서 본 결과가 prod에 그대로 적용되어야
# 관찰의 의미가 있다 (2026-08-28 확정).
waf_enforce    = true
waf_rate_limit = 300
