# infra - Terraform으로 staging, prod 환경 구축 (KAN-140)

AWS 스택 2벌(staging, prod)을 같은 모듈에서 tfvars만 바꿔 짓는다.
콘솔로 먼저 짓고 나중에 코드를 맞추는 순서는 금지다 (KAN-140).

## 구조

```
infra/
  bootstrap/          state 백엔드(S3 버킷) 생성용. 여기만 로컬 state.
  modules/
    network/          VPC, 서브넷, 보안 그룹 3종 (KAN-121)
    data/             RDS PostgreSQL (KAN-122)
    compute/          EC2, docker compose 부트스트랩 (KAN-124)
    edge/             internal ALB, VPC 오리진, CloudFront, S3 (KAN-125, KAN-126)
  envs/
    staging/          main.tf + terraform.tfvars
    prod/             main.tf + terraform.tfvars
```

workspace가 아니라 디렉토리 분리를 쓴다. workspace는 현재 선택된 환경이 눈에
보이지 않아 prod에 실수로 apply할 위험이 있다. 디렉토리가 갈려 있으면 어느
환경을 만지는지 경로로 드러난다.

`envs/staging`과 `envs/prod`는 `terraform.tfvars`와 `backend.tf`(state key)만
다르고 나머지 파일은 동일해야 한다. 확인:

```
diff -r infra/envs/staging infra/envs/prod
```

## 사전 요건

- Terraform >= 1.10 (S3 네이티브 잠금 `use_lockfile` 필요. 2026-08-24 기준
  로컬 설치 1.15.8에서 확인했고, DynamoDB 잠금 테이블은 필요 없다.)
- AWS CLI 프로파일(accentury-cli, ap-northeast-2). CI OIDC 연동은 범위 밖이다
  (2026-08-20 결정, 추후 별도 티켓).
- 선행 완료 상태: Route 53 호스팅 영역과 us-east-1 인증서 (KAN-119). 둘은
  Terraform이 소유하지 않고 data 소스로 조회만 한다. destroy에도 지워지지 않는다.
- ECR 이미지 (KAN-120): EC2가 t3.small(x86_64)이므로 이미지는
  `PLATFORM=linux/amd64 scripts/push-images.sh`로 빌드해야 한다. 애플 실리콘
  기본값(arm64) 이미지는 이 인스턴스에서 뜨지 않는다.

## 실행 절차

### 0. bootstrap (계정에 한 번만)

state 버킷은 Terraform state를 담을 곳이라 자기 자신을 원격 state로 만들 수
없다 (닭과 달걀). 이 스택만 로컬 state로 실행한다.

```
cd infra/bootstrap
terraform init
terraform plan
terraform apply
```

생성물: `accentury-tfstate-<account_id>` 버킷 (버전 관리, 암호화, 퍼블릭 차단).
로컬에 남는 `terraform.tfstate`는 커밋하지 않는다 (.gitignore 처리 완료).
버킷이 이미 있는데 로컬 state를 잃었다면:

```
terraform import aws_s3_bucket.tfstate accentury-tfstate-<account_id>
```

### 1. staging 먼저

```
cd infra/envs/staging
terraform init
terraform plan
terraform apply
```

검증한 뒤에 같은 모듈로 prod를 짓는다. 순서 강제의 이유: staging에서 모듈
결함을 다 털어내야 prod apply가 한 번에 선다.

첫 apply 후 확인:

- `terraform plan` 재실행이 "No changes"인가 (KAN-140 AC).
- 잠금 동작: 터미널 2개에서 동시에 `terraform apply`를 걸면 뒤쪽이
  state lock 오류로 거부되는가 (KAN-140 AC).
- `aws elbv2 describe-target-health --target-group-arn <arn>`으로 대상 healthy
  여부 (컨테이너 기동 전이면 unhealthy가 정상이다. KAN-124, KAN-128, KAN-129
  이후 healthy).

### 2. prod

```
cd infra/envs/prod
terraform init
terraform plan   # staging과 diff가 tfvars 차이뿐인지 눈으로 확인
terraform apply
```

### 3. apply 이후 남는 수동 단계 (다른 티켓)

- SSM 파라미터 채우기: `/accentury/{env}/*` (KAN-129). RDS 마스터 자격 증명은
  output `rds_master_user_secret_arn`의 Secrets Manager 시크릿에서 읽는다.
- EC2에 운영 compose 파일 배치: `/opt/accentury/docker-compose.yml` (KAN-124).
  배치 후 재부팅하거나 `systemctl start accentury`.
- 웹 번들과 등급 이미지 S3 업로드 (KAN-127).

## teardown 절차

환경 스택은 `terraform destroy`로 통째로 제거한다. 호스팅 영역과 인증서는
data 참조라 삭제 대상에 포함되지 않는다 (KAN-140 AC).

```
cd infra/envs/staging   # 또는 prod
terraform destroy
```

- prod는 RDS 삭제 보호가 켜져 있다. 먼저 `terraform.tfvars`에서
  `db_deletion_protection = false`로 바꿔 apply한 뒤 destroy한다. 최종
  스냅샷(`accentury-prod-final-<suffix>`)이 남는다. 접미사는 스택마다 다른
  고정 난수라 재구축 후 다시 destroy해도 이름이 충돌하지 않고, 남은 스냅샷은
  필요 없어지면 콘솔에서 직접 지운다.
- web S3 버킷은 `force_destroy = true`라 객체가 있어도 지워진다 (번들은
  파이프라인이 재생성 가능한 산출물).
- CloudFront 배포 삭제는 비활성화 전파 때문에 수 분 걸린다.
- bootstrap의 state 버킷은 `prevent_destroy`로 보호된다. 두 환경 state가
  전부 필요 없어진 것이 확실할 때만 코드에서 보호를 풀고 지운다.

## 설계 결정 기록

- **잠금**: Terraform 1.15.8이 S3 네이티브 잠금(`use_lockfile`)을 지원해
  DynamoDB 테이블을 만들지 않는다 (티켓의 버전 확인 항목 해소, 2026-08-24).
- **인증서 조회 domain**: us-east-1 인증서의 주 도메인은 `accentury.app`
  (SAN `*.accentury.app`)임을 CLI로 확인했다 (2026-08-24). 조회는
  `*.accentury.app`이 아니라 `accentury.app`으로 한다.
- **alb-sg 인바운드**: AWS 문서 기준, VPC 오리진 생성 시 CloudFront가 VPC에
  `CloudFront-VPCOrigins-Service-SG`를 만들어 오리진행 ENI에 붙인다. alb-sg는
  그 SG만 소스로 허용한다 (IP 대역 아닌 SG 참조, KAN-121 원칙).
- **SPA 재작성 Function**: 소스 파일(`modules/edge/spa-rewrite.js`) 하나를
  정본으로 두고 환경별 Function 2개를 만든다 (2026-08-24 확정). 환경별 state
  분리 구조에서 단일 Function을 공유 소유할 수 없기 때문이다. KAN-126의
  "두 배포가 공유" 문구는 이 결정으로 수정됐다.
- **RDS 마스터 비밀번호**: `manage_master_user_password`로 RDS가 생성해
  Secrets Manager에 보관한다. 코드, tfvars, state 어디에도 평문이 없다.
- **AMI 고정**: AL2023 최신 AMI를 SSM 파라미터로 읽되 `ignore_changes = [ami]`.
  AMI 갱신이 "plan No changes" AC를 깨고 인스턴스 교체를 유발하지 않게 한다.
- **PriceClass_200**: 한국이 포함되는 최소 티어.
- **EC2 인스턴스 1대, ai 워커 1개 고정**: backend 인메모리 상태(요청 제한 5축,
  회로 차단기, 혼잡 판정, 디스패처 풀) 때문. scale 금지 (KAN-124).
