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

# staging 전용 학습 데이터 S3 (KAN-201, 2026-09-08 결정). 내부 테스터의 음성 WAV와 AI 원점수를 모델 재학습용으로
# 보존한다 - FR-DP-01(원본 음성 미보존)의 staging 예외다. prod는 false로 버킷도 권한도 파라미터도 없다.
training_bucket_enabled = true

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
