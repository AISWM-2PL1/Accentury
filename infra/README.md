# infra - Terraform으로 staging, prod 환경 구축 (KAN-140)

AWS 스택 2벌(staging, prod)을 같은 모듈에서 tfvars만 바꿔 짓는다.
콘솔로 먼저 짓고 나중에 코드를 맞추는 순서는 금지다 (KAN-140).

## 구조

```
infra/
  bootstrap/          state 백엔드(S3 버킷), ECR 리포지토리, GitHub OIDC 공급자, ECS 서비스 연결 역할 등 계정 공유 리소스. 여기만 로컬 state.
  modules/
    network/          VPC, 서브넷, 보안 그룹 4종(alb, backend, rds, ai), 내부 호출용 프라이빗 DNS 영역 (KAN-121, KAN-36, KAN-165)
    data/             RDS PostgreSQL (KAN-122)
    fargate/          backend ECS Fargate 서비스 - 클러스터, 태스크 정의, 서비스, 실행 역할과 태스크 역할, 로그 그룹 (KAN-165)
    ai-host/          ai 전용 EC2 - ASG, 운영 compose, 기동 스크립트, systemd 유닛, egress 가드와 health 타이머 (KAN-124, KAN-36)
    config/           backend, ai 환경 변수의 정본인 SSM 파라미터와 시크릿 2종 (관리자 토큰, 내부 호출 토큰) (KAN-129, KAN-36)
    edge/             internal ALB(대상 그룹 ip), VPC 오리진, CloudFront, S3 (KAN-125, KAN-126)
    waf/              CloudFront 앞단 웹 ACL. us-east-1 프로바이더로 호출한다 (KAN-149)
    monitoring/       SNS 이메일과 CloudWatch 경보 7종 - ALB, RDS 3종 + backend 서비스 2종 + AI 호스트 2종 (KAN-134, KAN-165, KAN-36)
    deploy/           GitHub Actions가 OIDC로 맡는 환경별 배포 역할 (KAN-127)
  envs/
    staging/          main.tf + terraform.tfvars
    prod/             main.tf + terraform.tfvars
```

## 구성도

에픽 KAN-118(프로토타입 스텁 배포)이 정한 구조에 AI 전용 호스트 분리(KAN-36 A단계)와
backend의 Fargate 전환(KAN-165)을 더한 것이다. 요청은 위에서 아래로 한 줄로만 내려가고,
각 단계는 바로 앞 단계의 보안 그룹만 허용한다 (참조 사슬, KAN-121). 인터넷에서 ALB, 8080,
8000, 5432에 직접 닿을 길이 없고, ai는 backend 태스크(backend-sg)만 8000으로 부를 수 있으며
요청마다 공유 시크릿 헤더를 대조한다.

```
사용자 (Android 앱 WebView / 스탠드얼론 웹)
  │  https://accentury.app  (staging: staging.accentury.app)
  ▼
Route 53 호스팅 영역 ── Porkbun에서 NS 위임 ── ACM 인증서 2장 (us-east-1, 서울)   KAN-119
  │
  ▼
┌─────────────────────────────────────────────────────────────────┐
│ CloudFront 배포 (단일 출처, PriceClass_200)                KAN-126 │
│   [WAF 웹 ACL us-east-1: 관리형 규칙 2종 + rate limit]     KAN-149 │
│   기본 /*            → S3 (SPA 재작성 Function)                  │
│   /v0/*  /admin/v0/* → VPC 오리진 (캐싱 끔)                      │
└──────┬─────────────────────────────────┬────────────────────────┘
       │                                 │ VPC 오리진 ENI
       ▼                                 │ (CloudFront-VPCOrigins-Service-SG)
  S3 web 버킷                            │
  (웹 번들 KAN-127, 등급 이미지 KAN-132, │
   개인정보처리방침 KAN-133)             │
                                         │
═══════ VPC (staging 10.1.0.0/16 | prod 10.0.0.0/16, 피어링/NAT/VPC 엔드포인트 없음) ═══════ KAN-121
                                         │
  사설 서브넷 x2 (AZ a, c)               ▼
  ┌──────────────────────────────────────────────────────────────┐
  │ internal ALB  alb-sg: VPC 오리진 SG → 443만 (HTTPS)     KAN-125 │
  │   대상 그룹 ip:8080, 헬스체크 /actuator/health 10초 x 3  KAN-131 │
  │   등록 해제 지연 30초                                    KAN-166 │
  └──────────────────────────┬───────────────────────────────────┘
                             │ 8080 (backend-sg: alb-sg만)
  퍼블릭 서브넷 x2           ▼
  ┌──────────────────────────────────────────────────────────────┐
  │ ECS 클러스터 accentury-{env}  용량 공급자 FARGATE만       KAN-165 │
  │   서비스 backend  desired 1, 롤링 배포, 회로 차단기 + 자동 롤백   │
  │   태스크 정의 accentury-{env}-backend  0.5 vCPU / 2 GB, x86_64   │
  │   ┌──────────────┐  awsvpc ENI + 퍼블릭 IP                      │
  │   │ backend :8080│ Spring Boot, secrets = SSM 7개, 로그 → CloudWatch │
  │   └──┬───────┬───┘  stopTimeout 120초 (KAN-166), 회로 상태 지표    │
  └──────┼───────┼───────────────────────────────────────────────┘
         │       │ http://ai.accentury.internal:8000
         │       │ + X-Accentury-Internal-Token (ai-sg: backend-sg만 8000)
         │       ▼
         │  ┌──────────────────────────────────────────────────────┐
         │  │ ai 호스트 EC2 c7i.xlarge x86, ASG min 1 max 1     KAN-36 │
         │  │   부팅 시 Route 53 프라이빗 영역에 자기 A 레코드 UPSERT │
         │  │   iptables DOCKER-USER: 컨테이너 → VPC 밖 egress DROP  │
         │  │   IMDSv2 hop limit 1, health 타이머 → CloudWatch       │
         │  │   docker compose (docker-compose.ai.yml)               │
         │  │   ┌────────────────┐                                   │
         │  │   │ ai :8000        │ FastAPI 워커 1, 스텁 (실모델 KAN-22) │
         │  │   └────────────────┘                                   │
         │  └──────────────────────────────────────────────────────┘
         │ 5432 (rds-sg: backend-sg만)
  사설 서브넷 ▼
  ┌──────────────────────────────────────────────────────────┐
  │ RDS PostgreSQL 16  db.t4g.micro, 단일 AZ, 백업 7일  KAN-122 │
  │   마스터 비밀번호 = Secrets Manager 관리형                 │
  │   스키마 = Flyway 마이그레이션                      KAN-123 │
  └──────────────────────────────────────────────────────────┘

밖으로 거는 연결 (퍼블릭 서브넷 + 퍼블릭 IP라 NAT도 VPC 엔드포인트도 없다)
  ├─ backend 태스크, 실행 역할: ECR pull, CloudWatch Logs, SSM /accentury/{env}/* 7개 → secrets   KAN-165
  │    (태스크가 시작할 때 ECS 에이전트가 읽어 컨테이너 env로 준다. 정본은 config 모듈 KAN-129)
  ├─ backend 태스크, 태스크 역할: Secrets Manager RDS 마스터 시크릿(연결 시점, 7일 회전 추종)  KAN-129
  │    CloudWatch PutMetricData accentury/backend (Micrometer, 회로 상태)                    KAN-36
  ├─ ai 호스트: SSM Session Manager, SSM /accentury/{env}/ai/*와 IMAGE_TAG → ai.env, ECR pull,
  │    Route 53 accentury.internal A 레코드 UPSERT, CloudWatch accentury/ai (health 타이머)   KAN-36
  ├─ SSM IMAGE_TAG (파이프라인 KAN-128이 쓴다): ai 호스트가 compose 보간에, Terraform이 backend
  │    태스크 정의 image에 읽는다 - 두 서비스가 같은 SHA다                                    KAN-165
  ├─ ECR accentury/backend, accentury/ai (commit SHA 태그)        KAN-120
  └─ ECR accentury/ai-model (실모델 베이스 이미지, 모델 해시 태그, 사람이 push)  KAN-173

배포 파이프라인 (GitHub Actions, OIDC, 환경별 역할 modules/deploy)
  이미지 deploy.yml                                                KAN-128, KAN-165
    Dev 병합     → 이미지 빌드 → ECR push(SHA) → SSM IMAGE_TAG 갱신
                   → ai 호스트 Run Command reload → healthy
                   → backend 태스크 정의 리비전 등록 → ecs update-service → rolloutState COMPLETED
                   → 실패 시 역순 롤백(backend 직전 태스크 정의, ai 직전 SHA) → E2E 스모크 KAN-138
    Release 병합 → 재빌드 없이 Dev 이력의 최신 빌드 SHA → (environment 승인) → prod
    롤백         → 수동 실행에 이전 SHA 입력, 같은 절차
  웹 번들 web-deploy.yml → S3 업로드 + CloudFront 무효화            KAN-127

운영
  ├─ healthy 대상 없음, ALB 5xx, RDS 스토리지 알림 (CloudWatch)     KAN-134
  ├─ backend 서비스 CPU, 메모리 알림 (AWS/ECS)                      KAN-165
  ├─ AI health 실패, backend AI 회로 열림 알림 (커스텀 지표)         KAN-36
  └─ Terraform: bootstrap(state 버킷, ECR, SLR) + envs/{staging,prod}  KAN-140
     같은 모듈 x 2환경, 차이는 tfvars뿐. staging은 미사용 시 destroy
```

backend는 KAN-165부터 ECS Fargate다 (온디맨드만, Spot 없음 - 2026-09-01 결정, 아래 설계
결정 기록). 태스크 1개 고정은 backend의 인메모리 상태(요청 제한, 회로 차단기, 혼잡 판정,
디스패처 큐) 때문이고 KAN-167(다중 인스턴스 상태 정리)과 KAN-168(오토스케일링)이 푼다.
AI는 KAN-36 A단계로 전용 EC2에 스텁 모드로 분리됐고, 실모델 이미지 반영(B단계)과 GPU
여부는 KAN-22, KAN-57 이후다. WAF(KAN-149)는 `modules/waf`가 us-east-1에 만들어 CloudFront
배포에 붙인다 (아래 'WAF 웹 ACL').

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
- AWS CLI 프로파일(accentury-cli, ap-northeast-2). Terraform 실행용이다. 배포
  파이프라인은 이 프로파일이 아니라 GitHub OIDC 역할을 쓴다 (KAN-127, 아래
  "GitHub 설정").
- 선행 완료 상태: Route 53 호스팅 영역과 ACM 인증서 2장 (KAN-119). us-east-1
  인증서는 CloudFront 뷰어 구간, 서울 인증서는 ALB 오리진 구간(KAN-125)에 쓴다.
  셋 다 Terraform이 소유하지 않고 data 소스로 조회만 한다. destroy에도 지워지지
  않는다.
- 인증서 2장에는 태그 `accentury-role = wildcard`가 붙어 있어야 한다. 조회가
  도메인 + 이 태그로 고정돼 있어서(apex 전용 인증서가 추가돼도 잘못 잡히지
  않게), 태그가 없으면 plan이 "empty result"로 실패한다. 인증서를 재발급하면
  다시 붙인다:

  ```
  aws acm add-tags-to-certificate --region us-east-1      --certificate-arn <arn> --tags Key=accentury-role,Value=wildcard
  aws acm add-tags-to-certificate --region ap-northeast-2 --certificate-arn <arn> --tags Key=accentury-role,Value=wildcard
  ```
- ECR 이미지 (KAN-120): backend Fargate 태스크(runtime_platform X86_64)와 ai 호스트
  (c7i.xlarge)가 모두 x86_64이므로 이미지는 linux/amd64여야 한다. `scripts/push-images.sh`의
  기본 PLATFORM이 그 값이다 (KAN-124에서 고정). arm64 이미지를 배포하면 태스크가 exec format
  error로 바로 죽고 회로 차단기가 되돌린다 (아래 "이미지 배포 파이프라인과 롤백").
  ECR 리포지토리 자체는 bootstrap 스택이 소유한다.
  실모델 베이스 이미지(`accentury/ai-model`)도 같은 이유로 linux/amd64여야 한다.
  이 리포지토리만 CI가 아니라 모델 담당이 직접 push한다 (아래 "실모델 베이스 이미지
  push" 절, KAN-173).
- 첫 apply 전에 SSM `/accentury/{env}/IMAGE_TAG`가 있어야 한다 (KAN-165). fargate 모듈이
  이 값을 태스크 정의 image에 읽으므로 없으면 plan이 ParameterNotFound로 멈춘다. staging은
  파이프라인이 남긴 값이 destroy 뒤에도 남아 있고, prod 최초 구축(KAN-171)은 staging에서
  verified된 SHA를 먼저 넣는다.

  ```
  aws ssm put-parameter --name /accentury/prod/IMAGE_TAG --type String --value <verified SHA>
  ```

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

생성물: `accentury-tfstate-<account_id>` 버킷 (버전 관리, 암호화, 퍼블릭 차단),
ECR 리포지토리 `accentury/backend`, `accentury/ai`, `accentury/ai-model` (IMMUTABLE,
라이프사이클 정책: 태그 없는 이미지 1일, 최근 50개 유지), GitHub Actions OIDC 공급자
(`token.actions.githubusercontent.com`, KAN-127 - 계정에 1개뿐이라 여기서 만든다.
envs/*의 deploy 모듈이 data 소스로 조회하므로 bootstrap apply가 먼저다), ECS 서비스 연결
역할 `AWSServiceRoleForECS` (KAN-165 - 계정에 1개뿐이고 첫 클러스터 생성이 자동으로 만드는
대신 여기서 만든다. 없으면 envs apply의 클러스터 생성이 실패한다).
로컬에 남는 `terraform.tfstate`는 커밋하지 않는다 (.gitignore 처리 완료).

배포용 리포지토리 `accentury/backend`와 `accentury/ai`는 KAN-120이 콘솔에서 먼저
만들었다. `ecr.tf`의 import 블록이 첫 plan에서 그 둘을 state로 흡수하므로 별도
import 명령이 없다. plan에 `scan_on_push`와 라이프사이클 정책이 갱신/생성으로
나오는 것은 정상이다. `accentury/ai-model`은 KAN-173에서 코드로 처음 만들므로
import 대상이 아니다 (import 블록의 for_each는 배포용 2개만 돈다). 흡수가 이미 끝난
계정에서 KAN-173을 apply하면 plan은 `accentury/ai-model` 리포지토리와 라이프사이클
정책 생성 2건만 보여야 한다.
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
- backend 서비스 (KAN-165): apply 자체가 서비스 안정(태스크 RUNNING + healthy)까지
  기다린다 (`wait_for_steady_state`). 그래도 확인한다.

  ```
  aws ecs describe-services --cluster "$(terraform output -raw ecs_cluster_name)" --services backend \
    --query 'services[0].[runningCount,deployments[0].rolloutState,taskDefinition]' --output text
  aws elbv2 describe-target-health --target-group-arn <대상 그룹 arn>   # 태스크 IP 하나가 healthy
  ```
- ai 호스트 (KAN-36): ASG에 인스턴스 1대가 InService이고, 프라이빗 영역에 `ai.accentury.internal`
  A 레코드가 그 인스턴스의 사설 IP로 생겼는지 (부팅 스크립트가 만든다 - 인스턴스가 뜬 뒤 1분 안).

  ```
  aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$(terraform output -raw ai_asg_name)" \
    --query 'AutoScalingGroups[0].Instances[].[InstanceId,LifecycleState,HealthStatus]' --output table
  aws route53 list-resource-record-sets --hosted-zone-id "$(terraform output -raw private_zone_id)" \
    --query "ResourceRecordSets[?Type=='A'].[Name,ResourceRecords[0].Value]" --output table
  ```

### 2. prod

```
aws ssm put-parameter --name /accentury/prod/IMAGE_TAG --type String --value <verified SHA>   # 사전 요건
cd infra/envs/prod
terraform init
terraform plan   # staging과 diff가 tfvars 차이뿐인지 눈으로 확인
terraform apply
```

### 3. apply 이후 남는 수동 단계 (다른 티켓)

- 이미지 태그 반영: backend 태스크는 apply 시점의 SSM `IMAGE_TAG`로 이미 떠 있다.
  ai 호스트는 같은 태그를 읽어 첫 부팅에 기동한다. 다른 SHA를 반영하려면 Actions
  "Image Deploy"를 해당 환경으로 수동 실행한다 - SSM 갱신, ai 호스트 reload, backend
  리비전 배포를 한 번에 한다 (아래 "이미지 배포 파이프라인과 롤백").
- GitHub environment 변수 3개를 apply 출력으로 채운다 (아래 "GitHub 설정"). 그
  뒤 웹 번들은 Web Deploy 워크플로가 올린다 (KAN-127). 등급 이미지 업로드는
  KAN-132.
- **SNS 이메일 구독을 확인한다** (KAN-134). apply 직후 수신함에 AWS 확인 메일이
  오고, 링크를 누르기 전에는 경보가 울려도 메일이 나가지 않는다. 아래 "경보와
  알림"에 확인 명령이 있다.

## GitHub 설정 (KAN-127)

배포 워크플로(`.github/workflows/web-deploy.yml`)는 GitHub environment `staging`,
`prod`를 지정해 실행되고, 그 environment의 변수에서 대상을 읽는다. 역할의 신뢰
정책이 `repo:AISWM-2PL1/Accentury:environment:{env}`로 묶여 있어 environment
이름은 Terraform의 `env`와 같아야 한다.

역할의 신뢰 정책은 이름만 있는 구형식과 숫자 ID가 붙는 불변 형식
(`repo:AISWM-2PL1@295795156/Accentury@1308814203:environment:{env}`)을 둘 다
허용한다. 이 레포는 불변 형식을 쓴다 (2026-07-15 이후 생성 저장소 기본). 저장소를
옮기거나 다시 만들면 ID가 바뀌므로 `gh api repos/OWNER/REPO/actions/oidc/customization/sub`의
`sub_claim_prefix`를 확인해 `envs/*/variables.tf`의 기본값을 맞춘다.

어느 브랜치가 어느 environment로 배포하는지는 deployment branch policy로 고정한다
(staging = Dev, prod = Release). 이것이 없으면 아무 브랜치의 워크플로가 environment를
지정해 역할을 맡을 수 있다.

```
for env in staging prod; do
  branch=$([ "$env" = prod ] && echo Release || echo Dev)
  gh api -X PUT "repos/AISWM-2PL1/Accentury/environments/$env" --input - <<'JSON'
{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}
JSON
  gh api -X POST "repos/AISWM-2PL1/Accentury/environments/$env/deployment-branch-policies" \
    -f name="$branch" -f type=branch
done
```

변수 4개는 환경 apply 출력에서 채운다 (재구축으로 배포 ID나 역할 ARN이 바뀌면 다시).
`APP_DOMAIN`은 이미지 파이프라인의 E2E 스모크 대상이다 (KAN-128).

```
cd infra/envs/staging   # 또는 prod
env=$(terraform output -raw domain | grep -q '^staging' && echo staging || echo prod)
gh variable set AWS_DEPLOY_ROLE_ARN        -e "$env" --body "$(terraform output -raw github_deploy_role_arn)"
gh variable set WEB_BUCKET                 -e "$env" --body "$(terraform output -raw web_bucket)"
gh variable set CLOUDFRONT_DISTRIBUTION_ID -e "$env" --body "$(terraform output -raw cloudfront_distribution_id)"
gh variable set APP_DOMAIN                 -e "$env" --body "$(terraform output -raw domain)"
```

환경을 철거해 둔 동안에는 변수 `DEPLOY_PAUSED=true`를 추가로 둔다 ("teardown
절차"). 있으면 워크플로가 배포 단계를 건너뛰고, 재구축 뒤 지우면 다시 배포한다.

prod의 승인 게이트는 GitHub environment `prod`의 **required reviewers**다 (KAN-128
확정). Settings > Environments > prod > Deployment protection rules > Required
reviewers에 팀원을 넣으면 Release 병합이 만든 실행이 승인 전까지 `deploy` job에서
멈춘다. 이미지와 웹 배포가 같은 environment라 둘 다 승인을 기다린다. 코드가 아니라
저장소 설정이므로 레포에는 남지 않는다 - 새 저장소에서는 다시 켠다.

## 이미지 배포 파이프라인과 롤백 (KAN-128)

`.github/workflows/deploy.yml`이 한다. 승격 모델이라 이미지는 Dev 푸시에서 한 번만
만들고, prod는 그 SHA를 고르기만 한다.

| 트리거 | 하는 일 |
| --- | --- |
| Dev 푸시 (`backend/**`, `ai/**`) | `scripts/push-images.sh`로 두 이미지를 commit SHA 7자리 태그로 ECR에 push (이미 있으면 건너뜀) → staging 반영 → E2E 스모크 |
| Release 푸시 | 빌드 없음. merge commit의 2번째 부모(Dev 끝)부터 거슬러 **처음 만나는 빌드된 SHA**가 후보다. 그 SHA에 `verified-<sha>` 태그(staging 반영과 스모크 통과 표시)가 없으면 더 오래된 것으로 건너뛰지 않고 실패한다 (서버 변경이 조용히 빠지는 것을 막는다). environment `prod`에 required reviewers가 있으면 승인 대기 |
| 수동 실행 | 환경과 `image_tag` 선택. 비우면 staging은 현재 커밋 빌드, prod는 위 승격 규칙. **롤백 = 이전 SHA를 `image_tag`에 넣는 것** (같은 절차, 재빌드 없음) |

반영 한 번은 SSM `/accentury/{env}/IMAGE_TAG`를 새 SHA로 바꾸고(직전 값과 backend 서비스의
현재 태스크 정의를 기억), 두 갈래로 간다 (KAN-165).

1. ai 호스트: SSM Run Command(`AWS-RunShellScript`, 대상은 인스턴스 ID가 아니라
   `tag:Name=accentury-{env}-ai`)로 `systemctl reload accentury`(첫 기동이면 `start`)를 부른 뒤
   ai 컨테이너의 docker healthcheck가 healthy가 될 때까지 최대 5분 기다린다. ASG 교체 중이라
   대상이 없으면 130초(SSM 전달 창 120초보다 길게 - 에이전트 등록 실측 1.5분에서 2분) 뒤
   실패로 끝나므로 교체가 끝난 뒤 다시 실행한다.
2. backend 서비스: 서비스가 도는 태스크 정의를 `describe-task-definition`으로 읽어 image
   태그만 바꾼 리비전을 `register-task-definition`하고 `update-service`로 롤링 배포한다
   (새 태스크가 healthy가 된 뒤 옛 태스크를 뺀다 - 무중단). 완료 판정은 docker healthcheck가
   아니라 배포의 `rolloutState`가 COMPLETED인 것이고(ECS가 컨테이너 healthcheck와 ALB 대상
   health를 본다) 최대 20분 기다린다. 같은 태그 재실행이면 리비전을 쌓지 않고
   `--force-new-deployment`만 한다. cpu, secrets, stopTimeout 같은 형태는 Terraform
   (`modules/fargate`)이 정하므로 파이프라인은 image 외에 아무것도 바꾸지 않는다.

ai를 먼저 올리는 이유는 backend가 새 AI 계약을 전제로 할 수 있어서다 (새 AI는 옛 backend
호출을 받아야 한다). 어느 쪽이든 실패하면 역순으로 되돌린다. backend는 ECS 회로 차단기
(연속 실패 3회, desired 1일 때의 AWS 최소값)가 이미 직전 배포로 되돌렸으면 그 배포의 완료만
확인하고, 시간 초과처럼 ECS가 되돌리지 않은 경우에는 직전 태스크 정의로 `update-service`
한다. 실패한 리비전은 `deregister-task-definition`한다 - Terraform이 패밀리의 최신 ACTIVE
리비전을 읽으므로(`track_latest`, 아래 "backend Fargate 서비스") 남기면 `terraform plan`에
drift로 나온다. 그다음 IMAGE_TAG를 직전 값으로 되돌려 ai 호스트를 reload한다. 같은 순서로
되돌리면 "옛 AI + 새 backend" 창이 생기는데 그 조합은 아무도 보장하지 않는다. 그래서 "반영
실패 시 기존 이미지 유지"가 성립한다. 직전 값이 없는 첫 배포가 실패하면 backend는 ECS가
직전 배포를 유지하고 ai 호스트만 실패 상태로 남으므로, 유효한 태그를 수동 실행으로 넣는다.

컨테이너가 healthy가 된 뒤에는 공개 경로(`https://도메인/v0/...`)가 게이트웨이 오류
(502, 503, 504) 대신 백엔드 응답을 돌려줄 때까지 최대 6분 더 기다린다. ALB 대상 그룹
헬스체크가 healthy로 바뀌는 데 수십 초가 걸리고, 그동안 CloudFront 경유 요청은 502다.

어느 환경에 어떤 SHA가 떠 있는지는 세 곳에 남는다. 실행 요약 표(환경, 반영 태그,
출처, 직전 태그, backend 태스크 정의 리비전), GitHub environment의 deployments 목록, 그리고
SSM `IMAGE_TAG` 자체다. backend는 `aws ecs describe-services --cluster accentury-{env}
--services backend --query 'services[0].taskDefinition'`의 리비전이 가리키는 image, ai 호스트는
`grep IMAGE_TAG /run/accentury/compose.env`로 확인한다.

staging 반영과 스모크가 모두 통과하면 같은 이미지에 ECR 태그 `verified-<sha>`를 하나 더
붙인다 (IMMUTABLE은 기존 태그 덮어쓰기만 막는다). prod는 수동 입력이든 자동 승격이든
이 표시가 있는 SHA만 받으므로, 빌드는 됐지만 staging에서 실패한 이미지는 prod에 갈 수
없다. 표시는 prod 역할의 ECR 조회 권한만으로 확인되므로 환경 간 SSM 교차 읽기가 없다.

테스트는 파이프라인에서 다시 돌리지 않는다 - PR의 `backend-test`, `ai-test` required
check가 게이트다. E2E 스모크는 staging 반영 직후 같은 job에서 `scripts/e2e_smoke.py`를
직접 부르고, 관리자 토큰은 그 환경 SSM에서 읽어 합성 트래픽으로 표시한다 (아래 "관리자
토큰" 절의 저장소 시크릿 한계가 파이프라인에서는 사라진다). `e2e-smoke.yml`의
`workflow_call`을 쓰지 않는 이유는 토큰을 job output으로 넘기면 GitHub이 마스킹된 값이라며
output을 버리기 때문이다. prod는 승격 직후 사람이 `e2e-smoke.yml`을 workflow_dispatch로
돌린다. 스모크 실패는 실행을 실패로 만들지만 반영을 되돌리지는 않는다 - 스모크가 보는
것은 이미지가 아니라 전 구간이라, 원인이 이미지가 아닐 수 있다.

권한은 `infra/modules/deploy`의 `image-deploy` 정책이다. staging 역할만 ECR push를
갖고(`ci_image_push`, 환경 간 tfvars 차이) prod 역할은 조회뿐이다. Run Command는
자기 환경 이름이 붙은 ai 호스트(`accentury-{env}-ai`)에만 보낼 수 있고, ECS는 이 환경의
backend 서비스 ARN에만 `UpdateService`, `DescribeServices`를 할 수 있다. 태스크 정의 API
셋(`RegisterTaskDefinition`, `DescribeTaskDefinition`, `DeregisterTaskDefinition`)은 AWS가
리소스 수준 권한을 지원하지 않아 `*`이고, 리비전 등록에 필요한 `iam:PassRole`은 이 환경의
실행 역할과 태스크 역할 둘에 `iam:PassedToService = ecs-tasks.amazonaws.com` 조건으로 한정한다.

## 실모델 베이스 이미지 push (KAN-173)

`ai/` 컨테이너는 모델 담당이 만든 이미지 `accentury-track1`(KAN-159, MFA + Whisper
large-v3 + Praat, 약 5GB)을 `ai/Dockerfile`의 `FROM` 베이스로 쓴다 (KAN-22). 그 이미지는
ECR `accentury/ai-model`에 두고, CI가 아니라 **모델 담당이 직접 push한다**. 배포용
리포지토리 2개는 `scripts/push-images.sh`가 commit SHA 태그로 자동 push하는 곳이라
손으로 올린 이미지를 섞지 않는다.

- 권한: IAM 사용자 `jaeyoung`(AdministratorAccess). 콘솔에서 발급하고 Terraform 밖에
  둔다 (다른 팀원 IAM 사용자와 같다). 리포지토리 한정 정책은 만들지 않는다
  (2026-08-31 결정, KAN-173).
- 태그: 모델 해시 (예: `3af5ff6`). 날짜 태그를 쓰지 않는다. IMMUTABLE이라 같은 태그는
  다시 올릴 수 없고, 모델이 바뀌면 새 해시 태그로 올린다.
- 플랫폼: linux/amd64. 맥에서 빌드하면 `--platform linux/amd64`를 붙인다. arm64로
  올린 태그는 IMMUTABLE이라 되돌릴 수 없다.

모델 담당 PC에서 (`aws configure`로 `jaeyoung` 액세스 키를 넣은 뒤):

```
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.ap-northeast-2.amazonaws.com"
LOCAL_IMAGE=accentury-track1:latest   # 로컬에서 빌드한 이미지 이름:태그
MODEL_HASH=3af5ff6                    # 모델 해시 = ECR 태그

aws ecr get-login-password --region ap-northeast-2 \
  | docker login --username AWS --password-stdin "$REGISTRY"
docker tag "$LOCAL_IMAGE" "${REGISTRY}/accentury/ai-model:${MODEL_HASH}"
docker push "${REGISTRY}/accentury/ai-model:${MODEL_HASH}"
```

push한 태그를 KAN-173 코멘트로 남기면 인프라 담당이 받아서 확인한다:

```
docker pull "${REGISTRY}/accentury/ai-model:${MODEL_HASH}"
docker image inspect "${REGISTRY}/accentury/ai-model:${MODEL_HASH}" \
  --format '{{.Os}}/{{.Architecture}} {{.Size}}'
```

`linux/amd64`가 아니면 그 태그는 쓰지 않고 새 해시로 다시 올린다.

## backend Fargate 서비스 (KAN-165)

backend는 `modules/fargate`가 만드는 ECS Fargate 서비스다. EC2 위 docker compose(KAN-124)를
대체했고, ai는 그대로 전용 EC2다 (다음 절).

| 구성 | 값 | 비고 |
| --- | --- | --- |
| 클러스터 | `accentury-{env}`, 용량 공급자 `FARGATE`만 | `FARGATE_SPOT`은 연결하지 않는다 (2026-09-01 결정). Container Insights 끔 |
| 태스크 정의 | 패밀리 `accentury-{env}-backend`, 0.5 vCPU / 2 GB, `X86_64`, 컨테이너 `backend` 1개 | image = ECR `accentury/backend:<SSM IMAGE_TAG>`, secrets = SSM 파라미터 7개 (아래 표), `stopTimeout` 120초, awslogs `/accentury/{env}/backend`(14일), 컨테이너 healthCheck = compose와 같은 bash `/dev/tcp` 검사 |
| 서비스 | `backend`, desired 1, 용량 공급자 전략 `FARGATE` weight 1 | 롤링 배포(min 100% / max 200%), 회로 차단기 + 자동 롤백, `health_check_grace_period_seconds` 150초(실측 기반, 아래), 퍼블릭 서브넷 + 퍼블릭 IP, `backend-sg`, 대상 그룹 ip:8080. 오토스케일링은 KAN-168 |
| 실행 역할 | `accentury-{env}-backend-execution` | `AmazonECSTaskExecutionRolePolicy`(ECR pull, 로그) + 이 환경 config 파라미터 7개의 `ssm:GetParameters`. ECS 에이전트 몫이라 컨테이너 안에서는 보이지 않는다 |
| 태스크 역할 | `accentury-{env}-backend-task` | RDS 마스터 시크릿 `GetSecretValue` + `cloudwatch:PutMetricData`(네임스페이스 `accentury/backend` 조건). 애플리케이션이 SDK 기본 체인으로 받는다 - IMDS hop limit 조정이 없다 |

**이미지 태그의 정본은 SSM `IMAGE_TAG` 하나다.** Terraform은 그 값을 data 소스로 읽어 태스크
정의 image에 넣고, 파이프라인은 서비스가 도는 리비전을 복제해 image만 바꾼 리비전을 등록한다.
태스크 정의가 `track_latest = true`라 Terraform은 자기가 만든 리비전이 아니라 패밀리의 최신
ACTIVE 리비전을 state로 읽는다. 그래서 파이프라인 배포 뒤에도 "SSM 태그 = 배포된 태그 = 최신
리비전의 image"가 성립해 `terraform plan`이 No changes다. 반대로 `modules/fargate`의 태스크
정의(cpu, secrets, stopTimeout 등)를 고쳐 apply하면 Terraform이 새 리비전을 만들고 서비스를
갱신해 굴린다 - apply가 서비스 안정까지 기다린다. 두 주체가 같은 패밀리에 리비전을 쌓되
바꾸는 것이 다르고, 파이프라인이 실패한 리비전을 deregister하는 것이 이 정합의 조건이다.
파이프라인이 만든 리비전에는 프로바이더 default_tags가 없어 태스크 정의의 태그는
`ignore_changes`다 (비용 태그는 서비스가 태스크에 전파한다).

secrets는 태스크가 시작할 때 한 번 읽힌다. SSM 값이 바뀌어도(관리자 토큰 재발급 등) 도는
태스크에는 반영되지 않으므로 태스크를 새로 띄운다.

```
aws ecs update-service --cluster accentury-staging --service backend --force-new-deployment
```

운영 확인:

```
cd infra/envs/staging
cluster=$(terraform output -raw ecs_cluster_name)
aws ecs describe-services --cluster "$cluster" --services backend \
  --query 'services[0].[status,runningCount,taskDefinition,deployments[].[id,status,rolloutState,rolloutStateReason]]' --output json
aws ecs list-tasks --cluster "$cluster" --service-name backend                       # 도는 태스크
aws logs tail "$(terraform output -raw backend_log_group)" --since 10m --follow       # 컨테이너 로그
aws ecs describe-services --cluster "$cluster" --services backend --query 'services[0].events[:10]'  # 서비스 이벤트 (배포, 교체 사유)
```

2026-09-02 staging 실측값이다 (첫 apply의 첫 태스크, 0.5 vCPU / 2 GB, 이미지 약 180MB,
Flyway 마이그레이션 포함. 값은 `describe-tasks`의 시각, 서비스 이벤트, 컨테이너 로그에서 읽었다).

| 구간 | 걸린 시간 |
| --- | --- |
| 태스크 생성(PROVISIONING)부터 RUNNING까지 (ENI 5초, 이미지 pull 6초 포함) | 45초 |
| 컨테이너 시작부터 Spring `Started BackendApplication`까지 (JVM 기동) | 71초 (t3.small 시절 로컬 실측 2.8초의 25배 - 0.5 vCPU는 버스트가 없다) |
| RUNNING부터 ALB 대상 healthy(배포 completed)까지 | 1분 16초 (기동 71초 + 헬스체크 10초 x 3회) |
| 태스크 생성부터 서비스 steady state까지 | 2분 1초 |
| `terraform apply`의 서비스 생성(`wait_for_steady_state`) | 2분 41초 |

파이프라인(`deploy.yml`의 apply.sh를 로컬 CLI로 그대로 실행)의 2026-09-02 staging 실측값이다.

| 시나리오 | 결과 | 걸린 시간 |
| --- | --- | --- |
| 정방향 배포 (ai reload + backend 리비전 2 등록 + update-service) | 성공, 무중단 (옛 태스크가 새 태스크 healthy 뒤 등록 해제) | 전체 4분 16초. ai reload 25초, backend 롤링 배포 3분 43초 |
| 같은 태그 재실행 | 리비전을 쌓지 않고 `--force-new-deployment` | (KAN-165 실증 코멘트) |
| 배포 실패 1: pull 불가 이미지 (arm64 `f5c2123`, `CannotPullContainerError`) | 태스크 배치 실패 4회 뒤 회로 차단기 FAILED, ECS가 리비전 2 배포로 롤백. 파이프라인은 PRIMARY 교체를 감지해 실패 반환, 역순 롤백이 리비전 3 deregister | FAILED까지 15분 17초, 역순 롤백 41초, 그 뒤 plan No changes |
| 배포 실패 2: 헬스체크만 실패하는 리비전 (`healthCheck` = `exit 1`) | 태스크 3개가 각각 startPeriod 120초 + retries 60초 뒤 `Task failed container health checks`로 교체, 3회째에 FAILED, 롤백. 그동안 옛 태스크가 계속 서비스 (min healthy 100%) | FAILED까지 15분 33초, 역순 롤백 29초, plan No changes |

회로 차단기가 실패 3회를 채우는 데 15분 안팎이 걸린다 - 회당 태스크 시작 45초 + (pull 재시도 약 1분 30초 또는
grace 150초 + 헬스체크 판정) + 교체 대기이고, ECS가 실패 횟수를 평가하는 주기가 더해진다. 파이프라인의
rolloutState 대기 상한 20분은 이 값 기준이다. 그보다 빨리 되돌리려면 `thresholdConfiguration`(COUNT)이
필요한데 프로바이더 6.61에 아직 없다.

`health_check_grace_period_seconds`(150초)와 컨테이너 healthCheck `startPeriod`(120초)는 이
실측의 기동 시간에 여유를 더한 값이다. 이보다 짧으면 기동 중인 태스크를 ECS가 unhealthy로
판정해 교체를 반복하고, 회로 차단기가 3회에 배포를 접는다. 길면 죽은 이미지를 배포했을 때
회로 차단기 판정(실패 3회, 회당 grace period + 헬스체크 30초 + 교체)이 그만큼 늦어진다.

## ai 호스트 컨테이너 기동 (KAN-124, 전용 호스트 KAN-36)

ai 호스트는 무상태다. `modules/ai-host`의 첫 부팅 user_data가 docker, compose, 운영 compose
파일(`modules/ai-host/docker-compose.ai.yml`, 호스트에서는 `/opt/accentury/docker-compose.yml`),
기동 스크립트(`modules/ai-host/accentury-up.sh`), systemd 유닛 `accentury.service`를 놓는다. 두
환경의 호스트 구성은 완전히 같고, 환경별 값은 전부 SSM Parameter Store에서 온다. compose
파일이나 스크립트를 고치면 user_data가 바뀌어 **인스턴스가 교체된다** (시작 템플릿 새 버전 +
ASG instance refresh). 그래도 되는 이유가 무상태다. 교체 동안 분석만 끊기고 backend 회로가
열렸다 닫힌다. user_data는 raw 16KB 상한이 있어 ai-host 모듈의 precondition이 plan에서 크기를
검사한다. backend는 이 호스트에 없다 - KAN-165 전의 backend 호스트(`accentury-{env}`, t3.small,
`aws_instance`)는 Fargate 서비스로 대체됐다.

| | ai 호스트 `accentury-{env}-ai` |
| --- | --- |
| 인스턴스 | ASG min 1 max 1, c7i.xlarge (2026-09-01 결정으로 스텁 모드부터) |
| 보안 그룹 | ai-sg (backend-sg만 8000) |
| 컨테이너 | ai (호스트 8000, backend 태스크만 닿는다) |
| SSM 읽기 | `/accentury/{env}/ai/*`와 `IMAGE_TAG`만 (IAM으로도 그것만) |
| IMDSv2 hop limit | 1 (컨테이너는 IMDS를 못 본다) |
| 추가 | Route 53 A 레코드 UPSERT, iptables egress 가드, health 타이머 |

기동 순서 (`accentury-up.sh`, 부팅마다 그리고 reload마다):

1. SSM 읽기. `get-parameters-by-path --path /accentury/{env}/ai`와
   `get-parameter --name /accentury/{env}/IMAGE_TAG`.
2. 파라미터 이름의 마지막 조각을 환경 변수 이름으로 삼아 `/run/accentury/`
   (tmpfs, root 전용)에 env 파일 2개(compose.env, ai.env)를 쓴다. 여기에 `ACCENTURY_ENV`
   (env.conf)를 더한다 - 지표 차원이다.
3. IMDSv2로 자기 사설 IP를 읽어 프라이빗 영역에 `ai.accentury.internal` A 레코드(TTL
   10초)를 UPSERT한다. ASG가 인스턴스를 교체해도 backend가 보는 이름은 그대로다.
4. 인스턴스 프로파일로 ECR 로그인.
5. `docker compose --env-file /run/accentury/compose.env up -d --remove-orphans`
6. 어느 컨테이너도 쓰지 않는 이미지 정리 (`docker image prune -af`). 이전 SHA가
   루트 볼륨에 쌓이지 않게 한다. 롤백은 ECR에서 다시 당긴다.

1, 3, 4, 5는 일시 장애(IAM 전파 지연, 네트워크)에 대비해 최대 8회 백오프 재시도한다
(합계 약 4분). `IMAGE_TAG` 부재는 "배포 전" 상태라 재시도 없이 바로 실패한다.

유닛이 둘 더 있다 (KAN-36). `accentury-egress-guard.service`는 docker 뒤,
`accentury.service` 앞에 iptables `DOCKER-USER` 체인에 "컨테이너 브리지에서 시작되는 연결 중
목적지가 VPC 대역 밖이면 DROP"과 IMDS(169.254.169.254) DROP을 넣는다 - 실패하면 compose도 뜨지
않는다. `accentury-ai-health.timer`는 1분마다 `ai-health-metric.sh`로 `127.0.0.1:8000/internal/v0/health`를
찔러 CloudWatch `accentury/ai` `Healthy` 0|1(차원 env)을 올린다. 확인:

```
# ai 호스트 (SSM Session Manager)
iptables -S DOCKER-USER                         # DROP 4줄 (docker0, br+ 각각 VPC 밖 NEW, IMDS)
systemctl list-timers accentury-ai-health.timer  # 다음 실행 시각
journalctl -u accentury-ai-health -n 3           # "ai health=200 -> Healthy=1"
docker compose exec ai python -c "import urllib.request; urllib.request.urlopen('http://169.254.169.254/latest/meta-data/', timeout=2)"  # 실패해야 정상
```

| SSM 파라미터 (`/accentury/{env}/` 아래) | 가는 곳 | 용도 |
| --- | --- | --- |
| `IMAGE_TAG` | ai 호스트 compose.env, backend 태스크 정의 image (Terraform data 소스) | 두 서비스가 같은 SHA 태그를 쓴다. **없으면 ai 기동 실패, plan 실패.** 파이프라인(KAN-128)이 쓴다 |
| `ai/*` (하위 경로 전부) | ai.env (ai 호스트만) | ai 컨테이너 환경 변수. 지금은 내부 호출 토큰 하나 (KAN-36). 실모델 설정은 KAN-22가 이 경로 아래 어떤 이름으로든 더한다 - 이 호스트가 읽는 것은 이 경로뿐이라 이름 규칙이 없다 |
| 그 외 전부 (`modules/config` 출력 7개) | backend 태스크 정의 secrets (KAN-165) | backend 컨테이너 환경 변수. 태스크 시작 시 실행 역할이 읽는다 (아래 표, KAN-129) |

backend 환경 변수는 전부 Terraform `modules/config`가 만든다 - 값이 다른 모듈의
출력(RDS 주소, 시크릿 ARN, VPC CIDR, 도메인)이라 손으로 넣으면 재구축 때 어긋난다.
fargate 모듈이 config의 파라미터 이름 목록을 그대로 태스크 정의 secrets와 실행 역할의
허용 목록으로 쓰므로, 파라미터를 더하면(KAN-132 등급 이미지 URL 등) 태스크 정의와 역할에
같이 반영된다. ai-host 모듈은 같은 목록(`ai_parameter_names`)을 precondition으로 참조해
첫 부팅의 기동 스크립트가 SSM을 읽는 시점에 파라미터가 이미 있게 한다.

| `/accentury/{env}/` 아래 | 값 | 타입 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `deploy` (두 환경 동일). 이 프로파일에서만 backend가 아래 6개 누락 시 기동을 세운다 (`DeploymentConfigGuard`) | String |
| `SPRING_DATASOURCE_URL` | `jdbc:aws-wrapper:postgresql://<RDS 주소>:5432/accentury?secretsManagerSecretId=<마스터 시크릿 ARN>` | String |
| `ACCENTURY_ANALYSIS_AIBASEURL` | `http://ai.accentury.internal:8000` (프라이빗 영역의 고정 이름, 두 환경 동일, KAN-36) | String |
| `ACCENTURY_ANALYSIS_AITOKEN` | `random_password` 48자 영숫자. backend가 AI 호출마다 `X-Accentury-Internal-Token`으로 싣는다 (KAN-36) | SecureString |
| `ACCENTURY_TRUSTEDPROXIES` | 해당 환경 VPC CIDR 하나 | String |
| `ACCENTURY_RESULT_WEBTESTURL` | `https://<도메인>/t?c=kko_share` | String |
| `ACCENTURY_ADMIN_TOKEN` | `random_password` 48자 영숫자. 관리자 API(§6)와 E2E 스모크(KAN-138)가 쓴다 | SecureString |
| `ai/ACCENTURY_AI_INTERNAL_TOKEN` | `ACCENTURY_ANALYSIS_AITOKEN`과 같은 난수. ai 서버가 health를 뺀 모든 요청에서 대조한다 (KAN-36). ai 호스트 역할만 읽는다 | SecureString |

**DB 사용자 이름과 비밀번호 파라미터는 없다.** RDS 관리형 마스터 시크릿은 7일마다
자동 회전되므로(AWS 문서, 일정 변경만 가능) 값을 SSM에 복사하면 첫 회전에서 접속이
끊긴다. 대신 backend가 AWS Advanced JDBC Wrapper의 `awsSecretsManager` 플러그인으로
연결 시점에 Secrets Manager를 직접 읽고, 회전 뒤 인증 실패가 나면 시크릿을 다시 받아
재접속한다 (`backend/src/main/resources/application-deploy.yml`). 태스크 역할은 자기
환경 시크릿 1개의 `GetSecretValue`만 가진다 (fargate/main.tf). 비밀번호는 SSM,
state, 태스크 정의 어디에도 없다.

관리자 토큰 값 읽기 (스모크 실행, 관리자 API 호출용):

```
aws ssm get-parameter --with-decryption --name /accentury/staging/ACCENTURY_ADMIN_TOKEN \
  --query Parameter.Value --output text
```

재발급은 `terraform apply -replace='module.config.random_password.admin_token'`(taint는
deprecated) 뒤 backend 태스크를 새로 띄우는 것이다 (`aws ecs update-service --force-new-deployment`,
위 "backend Fargate 서비스" - secrets는 태스크 시작 시 한 번 읽힌다). 내부 호출 토큰은
`-replace='module.config.random_password.ai_internal_token'` 뒤 **ai 호스트 reload 먼저, backend
force-new-deployment 다음**이다 (그 사이 backend 호출은 401로 끊겨 회로가 열렸다가 닫힌다). 값은
Terraform state(S3 암호화 + 버전 관리 버킷)에 남는다 - KAN-140이 수용한 범위이고, 레포, 이미지,
로그에는 없다.

**E2E 스모크의 GitHub 시크릿도 함께 맞춘다** (KAN-138). `.github/workflows/e2e-smoke.yml`은
토큰을 `workflow_call` 시크릿 `admin-token` 또는 저장소 시크릿 `ACCENTURY_ADMIN_TOKEN`에서
읽는다. 토큰이 Terraform 생성으로 바뀌었으므로 apply나 재발급 뒤에 시크릿을 갱신하지
않으면 원격 스모크가 전부 401로 끝난다.

```
gh secret set ACCENTURY_ADMIN_TOKEN --body "$(aws ssm get-parameter --with-decryption \
  --name /accentury/staging/ACCENTURY_ADMIN_TOKEN --query Parameter.Value --output text)"
```

저장소 시크릿은 하나뿐이라 staging과 prod의 서로 다른 토큰을 동시에 담을 수 없다.
파이프라인(`deploy.yml`, KAN-128)은 이 시크릿을 쓰지 않고 해당 환경 SSM에서 직접
읽으므로 staging 자동 스모크는 항상 맞는 토큰을 쓴다. 이 시크릿이 필요한 것은
`e2e-smoke.yml`을 사람이 workflow_dispatch로 돌릴 때(prod 승격 직후)뿐이라, 그때
대상 환경의 값으로 맞춘다.

### 이미 있는 SSM 파라미터 (재구축, 수동 생성분)

`modules/config`가 만드는 이름이 계정에 이미 있으면 apply가 `ParameterAlreadyExists`로
실패한다 - `aws_ssm_parameter`는 Terraform이 만들지 않은 값을 덮어쓰지 않는다.
2026-08-26 기준 `/accentury/*` 아래에는 파라미터가 없다 (CLI 확인). 이후 손으로 만든 값이
있거나 destroy 없이 이 코드를 처음 적용하는 스택이면 두 가지를 한다.

1. 같은 이름 8개는 import로 state에 흡수한다. 값은 apply가 코드 값으로 갱신한다
   (`admin_token`과 토큰 2개는 `random_password`의 새 값으로 덮인다).

   ```
   cd infra/envs/staging
   for r in spring_profiles_active:SPRING_PROFILES_ACTIVE ai_base_url:ACCENTURY_ANALYSIS_AIBASEURL \
            datasource_url:SPRING_DATASOURCE_URL trusted_proxies:ACCENTURY_TRUSTEDPROXIES \
            web_test_url:ACCENTURY_RESULT_WEBTESTURL admin_token:ACCENTURY_ADMIN_TOKEN \
            ai_token_backend:ACCENTURY_ANALYSIS_AITOKEN ai_token_ai:ai/ACCENTURY_AI_INTERNAL_TOKEN; do
     terraform import "module.config.aws_ssm_parameter.${r%%:*}" "/accentury/staging/${r#*:}"
   done
   ```

2. 이 설계 이전 이름(`SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` 등)은
   Terraform이 모르므로 그대로 남고, 기동 스크립트가 backend.env에 그대로 싣는다. 비어 있지
   않은 사용자 이름과 비밀번호는 wrapper 플러그인이 시크릿에서 읽는 값과 충돌하므로 지운다.

   ```
   aws ssm delete-parameters --names /accentury/staging/SPRING_DATASOURCE_USERNAME \
     /accentury/staging/SPRING_DATASOURCE_PASSWORD
   ```

값에 탭이나 개행은 넣을 수 없다 (ai 호스트의 `--output text` 파싱 단위). 그 외 문자(`$`,
따옴표, `#`, 공백)는 그대로 컨테이너에 들어간다 - ai 호스트는 env_file을 `format: raw`로
읽어 Compose의 보간과 따옴표 처리를 끄고, ECS secrets는 값을 그대로 env로 준다. 시크릿은
SecureString으로 두면 되고(AWS 관리 키라 별도 kms 권한 불요), 실행 역할은 자기 환경의
파라미터 7개만, ai 호스트 역할은 자기 하위 경로만 읽는다. ai 호스트의 env 파일은 tmpfs라
재부팅 시 사라졌다가 다시 만들어진다 (낡은 사본이 쌓이지 않는다). docker 자체는 컨테이너
환경 변수를 `/var/lib/docker/containers/*/config.v2.json`(암호화된 루트 볼륨, root 전용)에
기록하므로 호스트 디스크에 평문이 전혀 없는 것은 아니다. Fargate 태스크는 호스트가 없어
그 사본도 없다. 레포, 이미지, 로그에 없다는 것이 KAN-122/129의 요구이고 그것은 충족한다.

## WAF 웹 ACL (KAN-149)

`modules/waf`가 us-east-1에 CLOUDFRONT 스코프 웹 ACL과 로그 그룹을 만들고, `modules/edge`가
배포의 `web_acl_id`에 그 ARN을 넣는다. envs는 `providers = { aws = aws.us_east_1 }`로
모듈을 호출한다 (서울 리전에 만들면 배포의 WAF 목록에 나타나지 않고 오류도 없다).

| 우선순위 | 규칙 | 하는 일 | 예외 |
| --- | --- | --- | --- |
| 10 | `rate-limit-costly-posts` | IP당 5분 창에 `POST /v0/sessions` + `POST …/recording` 합산 `waf_rate_limit`건 초과 시 429 | 폴링, 어휘 답안, 정적 자산은 세지 않음 |
| 20 | `aws-common` | `AWSManagedRulesCommonRuleSet` (본문 8KB 상한, XSS, LFI/RFI, 경로 조작 등. SQLi 규칙은 이 그룹에 없음) | 경로가 `/recording`으로 끝나는 요청은 그룹 전체를 건너뜀 (multipart 음성이 본문 규칙에 걸리므로) |
| 30 | `aws-known-bad-inputs` | `AWSManagedRulesKnownBadInputsRuleSet` (Log4j, Java 역직렬화 등) | 없음 |

차단 응답은 backend의 429 봉투와 같은 JSON(`RATE_LIMITED`, `retryable: true`,
`retryAfterMs: 300000`) + `Retry-After: 300`이다. 앱과 웹이 backend 429와 똑같이
처리한다. 대기 시간이 평가 창(5분)과 같은 이유는 한 번 넘긴 IP의 요청이 창에서
빠질 때까지 차단이 이어지기 때문이다. 관리형 규칙의 차단은 WAF 기본 403(본문
없음)이다. 경로 비교는 `URL_DECODE` + `NORMALIZE_PATH` 뒤에 정규식으로 하고, 세그먼트
뒤의 matrix 파라미터(`;x=1`)를 허용한다 - Tomcat과 Spring이 `%73essions`, `./`, `;x=1`을
풀어 라우팅하므로, 변환 없이는 변형 경로가 컨트롤러에는 닿으면서 집계만 비껴간다
(backend `UploadRateLimitFilter`가 같은 이유로 파싱된 경로로 매칭한다).

### Count 관찰 후 Block 전환

1. `waf_enforce = false`(기본)로 apply한다. 세 규칙 전부 Count라 아무것도 막지 않고
   매치만 로그에 남긴다.
2. 실제 녹음 파일로 앱 전 구간(세션 생성, 업로드 10건 이상, 완료, 결과)을 돌린다.
3. us-east-1 CloudWatch Logs Insights에서 로그 그룹 `aws-waf-logs-accentury-<env>`
   (output `waf_log_group`)에 다음을 돌린다. 기본 Allow는 로그 필터가 버리므로 남는
   건 매치된 요청뿐이다.

   ```
   fields @timestamp, httpRequest.httpMethod as method, httpRequest.uri as uri,
          httpRequest.clientIp as ip, action, terminatingRuleId
   | parse @message /"ruleId":"(?<rule>[^"]+)"/
   | stats count() by method, uri, rule
   | sort count desc
   ```

   업로드 경로(`…/recording`)에 `aws-common` 매치가 0건이어야 한다. 매치가 있으면
   스코프 다운이 빠진 것이다. 다른 경로의 매치는 규칙 이름을 티켓에 기록하고 오탐
   여부를 판단한다.
4. 오탐 건수와 규칙을 KAN-149에 기록한 뒤 `waf_enforce = true`로 apply한다.
   staging 먼저, 기록 뒤 prod.
5. 전환 확인: 같은 IP에서 `POST /v0/sessions`를 `waf_rate_limit`건 넘게 보내면 429와
   위 JSON 봉투가 오고, 로그에 `action = BLOCK`, `terminatingRuleId =
   rate-limit-costly-posts`가 남는다. WAF는 약 10초 간격으로 집계하므로 넘긴 직후
   몇 건은 통과한다. 차단은 IP의 5분 창 요청 수가 임계값 아래로 내려가면 풀린다.

### rate limit 임계값 300의 근거 (2026-08-28)

- 정상 사용자 1명의 5분 창: 세션 1 + 업로드 5문항(재녹음 포함 10건 이내) = 최대 11건.
- backend의 IP당 제한이 세션 생성, 업로드 각각 분당 30(5분 150)이라 보통의 초과는
  backend가 먼저 잡아 정식 429 봉투를 낸다. WAF는 그 2배인 300에서만 개입해 backend가
  multipart를 받느라 지치기 전에 홍수를 엣지에서 자른다.
- 공유 Wi-Fi(학교, 시연장)는 외부 IP가 하나다. 동시 응시 20명 x 11건 = 220 < 300.
  시연 인원이 그보다 많으면 당일 tfvars 값을 올려 apply한다.
- staging도 같은 값이다. staging에서 본 결과가 prod에 그대로 적용되어야 Count 관찰의
  의미가 있다.

### 비용

환경당 월 약 8달러(웹 ACL 5 + 규칙 3개 x 1) + 요청 100만 건당 0.60달러. 로그는 매치된
요청만 7일 보존이라 1달러 미만. 두 환경 합산 월 16달러 안팎을 감수한다 (KAN-149 코멘트,
2026-08-28).

## 경보와 알림 (KAN-134)

prod는 무인으로 돈다. 서버가 죽은 것을 사용자보다 먼저 알아야 하므로 SNS 토픽
하나(`accentury-{env}-alerts`)에 이메일 구독을 걸고 CloudWatch 경보 7종을 붙인다
(`infra/modules/monitoring`, 두 환경 각 1벌). AI 지표 2종을 빼면 지표를 새로 수집하지
않는다 - ALB, RDS, ECS가 이미 내보내는 표준 지표다. 지표 수집과 대시보드, correlation ID
규약은 KAN-38이고, 이 절은 그중 최소 선행분이다.

| 경보 | 지표 | 조건 | 결측 처리 |
| --- | --- | --- | --- |
| `no-healthy-target` (KAN-165에서 교체) | `HealthyHostCount` (TargetGroup + LoadBalancer) | 1분 최소 < 1이 2회 연속 | `breaching` |
| `alb-5xx` | `HTTPCode_ELB_5XX_Count` + `HTTPCode_Target_5XX_Count` 합 | 1분 합계 > 5가 2회 연속 | `notBreaching` |
| `rds-free-storage` | `FreeStorageSpace` | 5분 최소 < 2GiB | `missing` |
| `backend-cpu-high` (KAN-165) | `AWS/ECS` `CPUUtilization` (ClusterName + ServiceName) | 1분 평균 >= 80%가 5회 연속 | `notBreaching` |
| `backend-mem-high` (KAN-165) | `AWS/ECS` `MemoryUtilization` (ClusterName + ServiceName) | 1분 평균 >= 85%가 3회 연속 | `notBreaching` |
| `ai-unhealthy` (KAN-36) | `accentury/ai` `Healthy` (차원 env, ai 호스트 타이머가 올린다) | 1분 최소 < 1이 3회 연속 | `breaching` |
| `ai-circuit-open` (KAN-36) | `accentury/backend` `accentury.ai.circuit.state.value` (차원 env, backend Micrometer) | 1분 최대 >= 2(열림)가 2회 연속 | `notBreaching` |

앞의 둘이 KAN-134의 필수 2종이고 셋째는 "선택" 항목인데 두 환경 모두 넣기로 했다
(2026-08-28). 넷째와 다섯째는 Fargate 전환(KAN-165)이 KAN-134의 `ec2-cpu-surplus` 자리에
넣은 것이고, 마지막 둘은 AI 전용 호스트 분리(KAN-36)가 더한 것이다 - AI 호스트는 ALB 뒤가
아니라 대상 그룹 health가 없고, 회로 상태는 어떤 표준 지표에도 없다. 일곱 경보 모두 ALARM과
OK 양쪽을 알린다. 해제 알림이 없으면 아직 죽어 있는지를 콘솔에서 확인해야 하기 때문이다.

몇 가지가 의도된 선택이다.

- **5xx는 두 지표의 합이다.** `ELB_5XX`만 보면 backend가 500을 쏟는 상황을 놓치고,
  `Target_5XX`만 보면 backend가 아예 죽어 ALB가 502를 내는 상황을 놓친다. 합계
  하나로 보면 경보 개수도 한 개로 유지되어 "이 티켓은 경보 2종만"이라는 KAN-38
  경계가 그대로 선다.
- **backend 다운은 `UnHealthyHostCount >= 1`이 아니라 `HealthyHostCount < 1`로 본다
  (KAN-165).** KAN-134의 `unhealthy-hosts`는 EC2 대상 전제였다 - 컨테이너가 죽어도
  인스턴스가 등록된 채 unhealthy로 남아 그 지표가 1이 됐다. Fargate 태스크는 죽는 순간
  ECS가 대상 그룹에서 등록 해제해 `UnHealthyHostCount`가 0에 머물고, 헬스체크만 실패하는
  태스크도 ECS가 곧 교체해 unhealthy 구간이 1분 안팎이라 "2회 연속"에 못 미친다. 사용자
  관점의 장애는 "받아 줄 healthy 대상이 없다"이고 그것은 교체 중이든 등록 해제됐든
  `HealthyHostCount` 0으로 나타난다. 오토스케일링(KAN-168) 뒤 "여럿 중 하나가 죽었다"는
  부분 장애라 ECS 교체에 맡긴다.
- **`no-healthy-target`만 결측을 장애로 센다.** 이 지표는 대상이 등록돼 있는 한 계속
  나오므로 끊겼다는 것은 등록된 대상이 하나도 없거나(서비스가 태스크를 못 띄움) ALB나
  대상 그룹이 사라졌다는 뜻이다. 대가로 스택을 새로 apply한 직후 첫 태스크가 뜨는 몇 분
  동안 한 번 울린다. 이때는 대상이 실제로 없어 경보가 맞고, 뜨고 나면 OK 알림이 따라온다.
- **5xx는 결측을 정상으로 센다.** 트래픽이 0인 시간대에는 지표가 나오지 않는다.
  서버가 죽은 것은 `no-healthy-target`이 트래픽과 무관하게 잡는다. 다만 이미 ALARM인
  상태에서 트래픽이 완전히 끊기면 **해제되지 않고 ALARM에 머문다.** 경보를 다시
  평가하려면 데이터포인트가 최소 하나는 필요하기 때문이다 (2026-08-28 실측: 8분간
  요청 0인 동안 ALARM 유지, 정상 요청이 들어온 직후 OK). 아무도 복구를 확인해 주지
  않은 상태에서 알아서 내려가지 않는 편이 안전하므로 그대로 둔다.
- **토픽에 SSE를 켜지 않는다.** AWS 관리형 키 `alias/aws/sns`는 키 정책에
  `cloudwatch.amazonaws.com`이 없어서, 켜면 경보 상태는 ALARM인데 메일만 조용히
  안 오는 상태가 된다. 나르는 내용이 "어느 경보가 어느 상태로 바뀌었다"뿐이라
  고객 관리형 키를 따로 만들 이유가 없다.
- **backend 서비스 경보 2종은 KAN-134의 `ec2-cpu-surplus` 자리다 (KAN-165, 2026-09-02
  사용자 결정).** 그 경보는 t3.small의 초과 CPU 크레딧을 봤는데 backend EC2가 사라졌고,
  ai EC2(c7i.xlarge)는 버스트 계열이 아니라 크레딧 지표 자체가 없어 옮길 곳이 없다.
  대신 `AWS/ECS`의 서비스 단위 평균 둘을 본다 (Container Insights 없이 나온다).
  CPU는 Fargate 0.5 vCPU에 버스트가 없다는 점 때문이다 - t3.small은 순간 2 vCPU까지
  끌어 썼지만 여기서는 항상 정확히 0.5라 평균이 지속적으로 높으면 요청이 느려지는
  중이자 태스크 크기 상향 신호다 (오토스케일링 KAN-168은 CPU가 아니라 요청 수로
  늘린다). 5분 연속을 요구해 기동 직후 JIT 스파이크와 스모크 한 바퀴로는 서지 않는다.
  메모리는 힙이 태스크 2 GB의 75%라 그 위는 네이티브 영역까지 차오른 상태이고, 힙이 마르면
  `ExitOnOutOfMemoryError`로 태스크가 죽어 `no-healthy-target`이 사후에 잡는다 - 이 경보는
  그 전에 "차오르고 있다"를 알린다. 둘 다 결측(태스크 0개)은 정상으로 센다 - 그것은
  `no-healthy-target` 몫이다.
- **AI 지표는 둘 다 커스텀이다 (KAN-36).** `Healthy`는 ai 호스트의 systemd 타이머가 1분마다
  `aws cloudwatch put-metric-data`로 올린다 (호스트 역할의 PutMetricData는 네임스페이스
  `accentury/ai`로만 허용). 워밍업 중(503 STARTING)도 0이다. 결측을 장애로 세므로 apply 직후와
  ASG 교체 직후 몇 분은 한 번 운다 - `no-healthy-target`과 같은 성격이다. 회로 상태는 backend의
  Micrometer CloudWatch 레지스트리(`CloudWatchMetricsConfig`, 배포 프로파일에서만)가 `accentury.*`
  지표만 1분마다 올린다 - 게이지라 이름에 `.value`가 붙는다 (0 닫힘, 1 반열림, 2 열림). 경보는
  2(열림)에만 선다 - 반열림은 "다음 업로드로 시험한다"는 대기라 트래픽이 없으면 밤새 1에 머물고, 1 이상으로
  걸면 잠깐 죽었다 복구된 AI가 아침까지 ALARM으로 남는다. JVM이나 HTTP 지표는 올리지 않는다 - 이름당
  월 0.30달러다. 두 지표의 환경 구분은 차원 `env`다.
- **`ec2-cpu-surplus`가 있던 동안(KAN-134에서 KAN-165 전까지) `CPUCreditBalance`가 아니라
  `CPUSurplusCreditBalance`를 봤던 이유.** unlimited 인스턴스는 잔액 0으로 시작해 시간당
  24개씩 쌓는다. 잔액 하한으로 경보를 걸면 새로 뜬 인스턴스가 임계값에 닿을 때까지 몇
  시간 동안 무조건 운다 (2026-08-28 staging 실측). 초과 크레딧은 정상 부하에서 0 근처에
  머물다가 기준선을 넘겨 쓴 만큼만 쌓여 기동 오탐이 없었다. 버스트 인스턴스를 다시 쓰게
  되면 같은 선택이 유효하다.

### 수신 주소와 구독 확인

수신 주소는 `envs/{env}/variables.tf`의 `alert_email` 기본값이다 (2026-08-28 확정).
두 환경이 같은 값이라 `github_repository`와 같은 이유로 tfvars가 아니라 기본값에 둔다.
환경별로 나누려면 해당 tfvars에 `alert_email = "..."` 한 줄을 넣으면 된다.

SNS 이메일 구독은 Terraform이 확인까지 해 줄 수 없다. apply 직후 상태는
`PendingConfirmation`이고 수신자가 AWS 메일의 링크를 눌러야 활성화된다. **누르기
전에는 경보가 울려도 메일이 나가지 않는다.** 환경을 새로 지을 때마다 확인한다.

```
aws sns list-subscriptions-by-topic \
  --topic-arn "$(terraform output -raw alerts_topic_arn)" \
  --query 'Subscriptions[].[Protocol,Endpoint,SubscriptionArn]' --output table
```

`SubscriptionArn`이 `PendingConfirmation`이면 아직 안 누른 것이고, `arn:aws:sns:...`
형태면 활성이다.

### 경보 상태 확인과 실증

```
aws cloudwatch describe-alarms \
  --alarm-names $(terraform output -json alarm_names | jq -r '.[]') \
  --query 'MetricAlarms[].[AlarmName,StateValue,StateReason]' --output table
```

backend 쪽 AC 실증은 서비스의 태스크 수를 0으로 내려서 한다 (KAN-165). 태스크 하나를
`stop-task`로 죽이면 ECS가 2분에서 3분 안에 교체해 버려 `no-healthy-target`의 2회 연속과
`alb-5xx`의 2분이 채 안 찬다 - 그것은 KAN-169의 강제 종료 시나리오다. desired 0이면 대상
그룹이 비어 `HealthyHostCount` 0과 ALB 503이 트래픽 있는 동안 함께 나온다. Terraform이
desired_count 1을 아는 상태라 실증 뒤 1로 되돌리고 `terraform plan`이 No changes인지 본다.

```
aws ecs update-service --cluster accentury-staging --service backend --desired-count 0
```

여기까지로 `no-healthy-target`이 선다. 대상이 빠져도 대상 그룹이 남아 있는 한 매 분 0이
나오므로(빠진 직후 결측이어도 breaching) 2회 연속 조건이 저절로 충족된다.

`alb-5xx`는 **2분 이상 요청을 계속 보내야** 선다. 20건을 한 번에 몰아치면 그것이
전부 한 분에 들어가고 다음 분은 결측이 되는데, 결측이 `notBreaching`이라 "2회 연속"이
성립하지 않는다 (Codex 지적, 2026-08-28). 3분 동안 흘려보낸다.

```
end=$(( $(date +%s) + 180 ))
while [ "$(date +%s)" -lt "$end" ]; do
  for i in $(seq 1 6); do
    curl -s -o /dev/null -w '%{http_code} ' \
      "https://staging.accentury.app/v0/tests/gn-2026.08.1"
  done
  echo " @ $(date +%H:%M:%S)"
  sleep 10
done
```

분당 약 36건이라 임계치 5를 세 분 연속 넘긴다. 복구는 태스크 수를 되돌리면 된다 -
새 태스크가 healthy가 되면 두 경보가 OK로 돌아온다.

```
aws ecs update-service --cluster accentury-staging --service backend --desired-count 1
```

AI 경보 2종 (KAN-36)은 ai 호스트의 컨테이너를 내려서 실증한다. 회로는 backend가 AI 호출에
연속 5회 실패하면 열리므로(KAN-28) 컨테이너를 내린 채 녹음 업로드를 5건 이상 보낸다 (E2E
스모크의 업로드 단계, 또는 앱). `ai-unhealthy`는 트래픽 없이 3분 안에 선다.

```
aws ssm send-command --document-name AWS-RunShellScript \
  --targets Key=tag:Name,Values=accentury-staging-ai \
  --parameters 'commands=["cd /opt/accentury && docker compose --env-file /run/accentury/compose.env stop ai"]'
# 복구
aws ssm send-command --document-name AWS-RunShellScript \
  --targets Key=tag:Name,Values=accentury-staging-ai \
  --parameters 'commands=["systemctl reload accentury"]'
```

ASG 교체(KAN-36 AC)는 인스턴스를 강제 종료해 본다. 새 인스턴스가 뜨고 A 레코드가 갱신되고
backend 회로가 닫힐 때까지의 시간을 티켓에 기록한다.

```
id=$(aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names accentury-staging-ai \
  --query 'AutoScalingGroups[0].Instances[0].InstanceId' --output text)
aws ec2 terminate-instances --instance-ids "$id"
```

2026-08-28 staging 실측값이다 (EC2 대상, KAN-134 당시).

| 구간 | 걸린 시간 |
| --- | --- |
| 컨테이너 정지부터 `unhealthy-hosts` ALARM까지 | 5분 47초 |
| 트래픽 시작부터 `alb-5xx` ALARM까지 | 3분 38초 |

2026-09-02 staging 실측값이다 (Fargate 대상, KAN-165 재실증. 값은 KAN-165 실증 코멘트).

| 구간 | 걸린 시간 |
| --- | --- |
| desired 0부터 첫 503까지 (등록 해제 지연 30초 뒤 옛 태스크 종료) | 22초 |
| 트래픽 시작부터 `alb-5xx` ALARM까지 | 3분 52초 |
| desired 0부터 `no-healthy-target` ALARM까지 | 9분 9초 |
| desired 1 복구부터 `no-healthy-target` OK까지 | 5분 7초 (태스크 healthy 약 2분 + 1분 x 2회 + 평가 지연) |
| 트래픽 종료부터 `alb-5xx` OK까지 | 8분 22초 (결측이 notBreaching이라 트래픽 없이도 내려왔다 - 08-28 실측과 다른 점) |

`no-healthy-target`이 9분 걸린 이유는 대상이 0개일 때 `HealthyHostCount`가 0으로 나오지 않고 결측이
되고, CloudWatch가 결측을 breaching으로 세기 전에 지표가 끊겼는지 판단하는 유예를 두기 때문이다
(08-28의 EC2 대상은 unhealthy 값 1이 매 분 나와 5분 47초였다). 태스크가 죽어 ECS가 2분에서 3분 안에
교체하는 정상 경로는 이 경보에 안 잡히는 것이 맞고, 잡아야 하는 것은 "몇 분째 healthy 대상이 없다"라
9분도 티켓 AC "수 분 내" 안이다. 더 빠르게 하려면 결측 없이 계속 나오는 지표(Container Insights의
`RunningTaskCount`, 이름당 요금)가 필요해 KAN-38에서 판단한다.

AI 경보 2종의 2026-09-01 staging 실측값이다 (KAN-36). ai 컨테이너를 내린 뒤 스모크 업로드
3건이 202를 받고 그 재전송 실패로 회로가 열려 4건째부터 503이었다.

| 구간 | 걸린 시간 |
| --- | --- |
| ai 정지부터 회로 열림(업로드 503)까지 | 약 20초 |
| ai 정지부터 `ai-circuit-open` ALARM까지 | 2분 34초 |
| ai 정지부터 `ai-unhealthy` ALARM까지 | 3분 57초 |
| `systemctl reload accentury`부터 스모크 통과(회로 닫힘)까지 | 45초 |
| reload부터 `ai-unhealthy` OK까지 | 1분 54초 |
| reload부터 `ai-circuit-open` OK까지 | 2분 34초 |
| ai 인스턴스 강제 종료부터 새 인스턴스 InService까지 | 1분 34초 |
| 강제 종료부터 A 레코드 갱신(새 사설 IP)까지 | 2분 20초 |
| 강제 종료부터 backend(당시 EC2 호스트)에서 health 200까지 | 2분 39초 |

교체 중 업로드가 없으면 회로는 닫힌 채고, 결측 2분은 `ai-unhealthy`의 3회 조건에 못 미쳐 경보가 서지 않았다.

EC2 시절 `unhealthy-hosts`가 더 걸린 이유는 앞에 ALB 헬스체크 판정이 한 겹 더 있었기 때문이다
(컨테이너가 멈춰도 대상이 unhealthy로 바뀌는 데 30초 x 5회). Fargate에서는 등록 해제가 즉시라
`no-healthy-target`이 그보다 빠르다. 티켓 AC의 "수 분 내"는 이 값 기준이다. 복구 뒤에는 같은
간격으로 OK 메일이 온다.

### 비용

CloudWatch 표준 경보는 개당 월 0.10달러, 지표 math 경보(`alb-5xx`)는 지표 2개를 세어
0.20달러다. 경보 7종에 환경당 월 약 0.80달러, 커스텀 지표(`Healthy` 1개 + backend `accentury.*`
약 5개)가 이름당 월 0.30달러로 약 1.80달러다. SNS 이메일 알림은 월 1000건까지 무료다. 두 환경
합산 월 5달러 안팎이라 상시 켜 둔다.

## 환경별 값 차이 (KAN-129)

Terraform 입력의 차이는 `diff -r infra/envs/staging infra/envs/prod`가 전부다
(`terraform.tfvars`와 `backend.tf`의 state key). 그 차이가 backend 설정으로
어떻게 흘러가는지의 표다. 여기 없는 backend 설정은 두 환경이 같다.

| 항목 | staging | prod | 흘러가는 곳 |
| --- | --- | --- | --- |
| 도메인 | `staging.accentury.app` | `accentury.app` | CloudFront 대체 도메인, Route 53, `ACCENTURY_RESULT_WEBTESTURL` |
| VPC CIDR | `10.1.0.0/16` | `10.0.0.0/16` | 서브넷 4개, `ACCENTURY_TRUSTEDPROXIES` |
| RDS 엔드포인트 | `accentury-staging.<id>.ap-northeast-2.rds.amazonaws.com` | `accentury-prod.<id>...` | `SPRING_DATASOURCE_URL` (apply 후 output `rds_endpoint`) |
| RDS 마스터 시크릿 | `rds!db-<staging uuid>` | `rds!db-<prod uuid>` | `SPRING_DATASOURCE_URL`의 `secretsManagerSecretId`, backend 태스크 역할 정책 |
| SSM 경로 | `/accentury/staging/*` | `/accentury/prod/*` | backend 실행 역할 정책(secrets 7개), ai 호스트 역할 정책과 기동 스크립트, backend 태스크 정의의 `IMAGE_TAG` 조회 |
| backend 태스크 (KAN-165) | 0.5 vCPU / 2 GB, desired 1 | 같은 값 | `modules/fargate` 기본값 (tfvars 아님) |
| 관리자 토큰 | 환경별 난수 | 환경별 난수 | `ACCENTURY_ADMIN_TOKEN` |
| 내부 호출 토큰 (KAN-36) | 환경별 난수 | 환경별 난수 | `ACCENTURY_ANALYSIS_AITOKEN`, `ai/ACCENTURY_AI_INTERNAL_TOKEN` |
| AI 호스트 (KAN-36) | c7i.xlarge, 루트 20GB | 같은 값 | `ai_instance_type`, `ai_root_volume_size` (실모델 전환 시 40) |
| RDS 삭제 보호, 최종 스냅샷 | 없음, 생략 | 켬, 남김 | RDS |
| 배포 역할 ECR push | 허용 | 불가 | `modules/deploy` image-deploy 정책 (KAN-128 승격 모델) |

staging 설정으로 prod에 닿을 수 없는 이유: VPC가 분리돼 있고(피어링 없음), backend
실행 역할과 태스크 역할, ai 호스트 역할이 자기 환경의 SSM 경로와 시크릿 ARN만 허용하며,
시크릿 ARN 자체가 환경별로 다르다. 프라이빗 영역 `accentury.internal`은 이름이 같지만 각 환경 VPC에만 연결돼
있어 staging backend가 prod ai를 풀 수 없다.

운영 확인. ai 호스트는 SSM Session Manager로 접속한 뒤:

```
systemctl status accentury          # 마지막 기동 결과
journalctl -u accentury -n 50       # 기동 스크립트 로그 (어느 태그를 읽었는지, Route 53 갱신)
docker compose -f /opt/accentury/docker-compose.yml --env-file /run/accentury/compose.env ps
```

backend는 호스트가 없다 - 위 "backend Fargate 서비스"의 `describe-services`와 `logs tail`로
본다. ai 컨테이너는 호스트 8000(ai-sg가 backend 태스크만 허용)을 발행하고
`restart: unless-stopped`라 프로세스가 죽으면 docker가 다시 띄우며, 호스트가 재부팅되면
systemd 유닛이 SSM 값을 새로 읽어 `up -d`를 다시 건다. ai 호스트 자체가 죽으면 ASG가
교체하고 새 인스턴스가 같은 이름의 A 레코드를 갱신한다. backend 태스크가 죽으면 ECS
서비스가 desired 수만큼 새 태스크를 띄운다.

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
- destroy 뒤에는 그 환경의 GitHub environment 변수 `DEPLOY_PAUSED`를 `true`로
  둔다. 환경이 없는데 `web/**` 변경이 병합되면 Web Deploy가 역할 부재로
  `AssumeRoleWithWebIdentity` 거부라는 헷갈리는 메시지로 실패하기 때문이다
  (2026-08-27 실제 발생). 변수가 있으면 워크플로는 빌드만 하고 배포를 건너뛴 채
  요약에 "환경 철거 중"을 남긴다. 재구축 뒤 변수를 지운다.

  ```
  gh variable set DEPLOY_PAUSED -e staging --body true      # destroy 직후
  gh variable delete DEPLOY_PAUSED -e staging               # apply와 변수 3개 갱신 뒤
  ```
- Terraform이 만들지 않는 `/accentury/{env}/IMAGE_TAG`는 destroy가 지우지
  않는다. 남겨 두면 다음 재구축 때 backend 태스크와 ai 호스트가 그 SHA로 뜬다 - 재구축의
  plan이 이 값을 요구하므로(사전 요건) 지우지 않는 편이 맞다.
- ECS 서비스 삭제는 태스크를 먼저 내린다 (등록 해제 30초 + stopTimeout 최대 120초). 로그
  그룹은 로그째 지워진다. Terraform은 자기가 아는 태스크 정의 리비전(최신 ACTIVE)만
  deregister하고, 파이프라인이 만든 옛 리비전은 INACTIVE로 남는다 - 요금이 없고 잔존
  확인의 대상이 아니다. 목록에서 치우려면 `aws ecs delete-task-definitions`.
- 잔존 확인은 `terraform state list`에 더해 `aws ecs list-clusters`,
  `aws ecs list-task-definitions --status ACTIVE`, `aws logs describe-log-groups
  --log-group-name-prefix /accentury`로 본다 (SCP가 tag:GetResources를 막는다).
- 프라이빗 영역의 `ai.accentury.internal` A 레코드는 ai 인스턴스가 만든 것이라 Terraform
  밖이지만, 영역이 `force_destroy = true`라 destroy가 레코드째 지운다 (KAN-36). ASG는
  인스턴스를 먼저 종료한 뒤 삭제된다.
- 미확인 SNS 이메일 구독은 AWS가 지워 주지 않아 state에서만 빠지지만, 토픽이
  삭제되면 딸린 구독도 함께 사라져 잔존물이 남지 않는다 (KAN-134). 재구축 때는
  토픽이 새로 생기므로 확인 메일이 다시 오고 다시 눌러야 한다.

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
- **AMI 고정 (ai 호스트)**: AL2023 최신 AMI를 SSM 파라미터로 읽되 `ignore_changes = [image_id]`.
  AMI 갱신이 "plan No changes" AC를 깨고 instance refresh를 유발하지 않게 한다.
- **PriceClass_200**: 한국이 포함되는 최소 티어.
- **backend 태스크 1개, ai 워커 1개 고정 (KAN-124, KAN-36, Fargate 전환 뒤에도 KAN-165)**:
  backend가 다음 상태를 전부 프로세스 메모리에 둔다. 둘 이상이면 상태가 갈라져 요청
  제한이 배로 풀리고 회로 차단기가 서로 다른 판단을 한다. 처리 방침은 KAN-167이 정하고
  오토스케일링(min 1 max 3)은 KAN-168이 붙인다.
  - 요청 제한 5축 (`RateLimits`: 세션 생성 IP, 업로드 IP, 업로드 세션, 어휘
    세션, 완료)
  - AI 회로 차단기 (`AiCircuitBreaker`: 닫힘/열림/반열림 상태, 연속 실패 카운터)
  - pollAfterMs 혼잡 판정 (진행 중 AI 전달 건수 임계치)
  - 분석 디스패처 풀 (`dispatch-concurrency` 4워커의 인메모리 큐)

  ai는 임시 디렉터리 하나를 프로세스 하나가 전용으로 쓴다 (KAN-27). 워커를
  늘리면 기동 시 잔여물 정리가 형제 워커의 처리 중 오디오를 지운다.
  `--workers 1`은 `ai/Dockerfile`이 고정한다. 같은 내용이 운영 compose 파일
  머리에도 있다.
- **backend는 ECS Fargate 온디맨드, Spot 없음 (2026-08-28 채택, 2026-09-01 Spot 제외, KAN-165)**:
  채택 근거는 비용이 아니라 학습이다 - 2026-08-19 멘토링이 Fargate 오토스케일링과 회수
  시 내결함성을 직접 경험하라고 했다. 순수 기술 논거로는 EC2 ASG가 이 규모에 맞고 Fargate가
  이기는 것은 스케일아웃 속도 하나다. 크기는 0.5 vCPU / 2 GB x86 (t3.small 18.98달러 대
  24.46달러/월). Spot을 빼는 근거 셋: base 1이 온디맨드라 프로토타입 트래픽에서 절감이 0에
  가깝다, 멘토 지시의 핵심(회수 시 제대로 꺼지고 켜지는지)은 KAN-169가 `stop-task`로 같은
  SIGTERM 후 stopTimeout 경로를 모사한다, Fargate Spot은 용량 부족 시 온디맨드로 대체하지
  않고 재시도만 한다. 다시 볼 조건은 스케일아웃 태스크가 상시 존재할 때이고 그때는 클러스터
  용량 공급자와 서비스 전략에 `FARGATE_SPOT`을 더한다. prod는 처음부터 이 구조로 세운다
  (2026-08-31, KAN-171).
- **이미지 태그의 정본은 SSM `IMAGE_TAG` 하나, Terraform 태스크 정의는 `track_latest` (KAN-165)**:
  Terraform이 태그를 data 소스로 읽어 태스크 정의에 넣고, 파이프라인은 리비전을 복제해
  image만 바꾼다. Terraform이 패밀리의 최신 ACTIVE 리비전을 state로 읽으므로 파이프라인
  배포 뒤에도 plan이 No changes이고, Terraform이 바꾼 형태(cpu, secrets)는 apply가 곧
  롤링 배포다. 검토한 대안 `ignore_changes = [task_definition]`은 Terraform 변경이 다음
  파이프라인 배포까지 서비스에 닿지 않는다. 조건은 파이프라인이 실패한 리비전을
  deregister하는 것이고, 파이프라인 리비전에 default_tags가 없어 태그는 `ignore_changes`다.
  첫 구축은 IMAGE_TAG가 먼저 있어야 한다 (ai 호스트와 같은 전제).
- **대상 그룹은 ip, 이름은 `-backend` (KAN-165)**: `target_type`은 교체 강제라 instance에서 ip로
  가며 그룹이 새로 만들어졌고, 리스너가 옛 그룹을 가리키는 동안 옛 그룹을 지울 수 없어
  `create_before_destroy`가 필요한데 같은 이름은 먼저 만들 수 없다. 등록 해제 지연은 30초
  (KAN-166 종료 예산 - HTTP 요청이 전부 1초 미만), 헬스체크는 10초 x 3회 (기본 30초 x 5회면
  롤링 배포와 회로 차단기 판정이 2분 30초씩 늦다).
- **실행 역할과 태스크 역할 분리 (KAN-165)**: EC2 시절 인스턴스 역할 하나가 ECR pull, SSM,
  Secrets Manager, CloudWatch를 다 가졌다. 실행 역할(ECS 에이전트 몫: ECR pull, awslogs,
  secrets 주입용 `ssm:GetParameters` 7개)은 컨테이너 안에서 보이지 않고, 태스크 역할
  (애플리케이션 몫: RDS 시크릿, PutMetricData)만 SDK 기본 체인으로 흘러간다. 신뢰 정책에
  `aws:SourceAccount`, `aws:SourceArn` 조건을 둔다 (AWS 문서의 혼동된 대리인 방지).
  SecureString은 AWS 관리 키라 kms 권한이 따로 없다.
- **backend 태스크도 퍼블릭 서브넷 + 퍼블릭 IP, VPC 엔드포인트 없음 (KAN-165)**: ECR, SSM,
  Secrets Manager, CloudWatch에 닿으려면 사설 서브넷에서는 NAT(KAN-121이 뺌) 또는 VPC
  엔드포인트 5종(ecr.api, ecr.dkr, logs, ssm, secretsmanager - 개당 시간당 0.013달러, 월
  약 47달러)이 필요하다. 지금 EC2도 NAT 없이 퍼블릭 IP로 도는 구조와 같고, 인바운드는
  backend-sg가 alb-sg만 허용해 닫혀 있다. 대가는 태스크마다 붙는 퍼블릭 IPv4 3.65달러/월인데
  EC2도 같은 값을 냈다.
- **회로 차단기 + 파이프라인 시간 초과 롤백 (KAN-165)**: 서비스는 `deployment_circuit_breaker`
  enable + rollback이다. desired 1이면 임계가 AWS 최소값 3회라(2026-09 문서, 종전 10) 즉시
  죽는 이미지(arm64 exec format error 등)는 몇 분 안에 FAILED가 되고 ECS가 직전 배포로
  되돌린다. 헬스체크만 계속 실패하는 태스크는 grace period + 판정 + 교체가 3번이라 10분
  넘게 걸리므로 파이프라인이 20분을 상한으로 두고, 그 안에 COMPLETED가 아니면 직전 태스크
  정의로 직접 `update-service` 한다. 프로바이더 6.61은 `thresholdConfiguration`을 아직
  지원하지 않는다.
- **경보 `no-healthy-target`, `backend-cpu-high`, `backend-mem-high` (KAN-165, 2026-09-02 사용자
  결정)**: 근거는 위 "경보와 알림"의 의도된 선택. `ec2-cpu-surplus`는 옮길 대상(버스트
  인스턴스)이 없어 제거했다.
- **롤링 배포 중 태스크 2개 겹침은 수용 (KAN-165)**: 배포의 몇 분 동안 인메모리 상태(요청 제한,
  회로, 혼잡 판정)가 갈라지고 디스패처도 둘이 된다 - "태스크 1개 고정"의 의도된 예외다. KAN-166이
  롤링 배포의 겹침을 전제로 종료 순서를 만들었고, 상시 다중 태스크 방침은 KAN-167, 오토스케일링은
  KAN-168이 정한다. 겹침을 없애는 min 0 / max 100은 배포마다 수 분의 전면 중단이라 기각했다.
- **ECS Exec(대화형 접속)은 켜지 않는다 (KAN-165)**: EC2 시절의 SSM Session Manager 자리인데, 평시
  진단은 로그와 서비스 이벤트, 지표로 충분하다. 태스크 안에서 재현해야 하면 서비스에
  `enable_execute_command = true`와 태스크 역할의 ssmmessages 4종을 더해 새 배포를 강제한다
  (fargate/main.tf 주석).
- **배포 역할의 태스크 정의 권한 범위 (KAN-165)**: AWS 서비스 권한 레퍼런스 기준
  RegisterTaskDefinition만 리소스 수준 권한을 지원해 이 환경 패밀리로 좁혔다. Describe와 Deregister와
  List는 `*`뿐이라 staging 역할이 prod 리비전을 deregister할 수 있는 표면이 남는데, 삭제가 아니라
  INACTIVE 전환이고 도는 서비스에 영향이 없어 수용한다.
- **보안 그룹 description의 옛 문구 (KAN-165)**: alb, rds, ai SG의 description에 "ec2-sg"가
  남아 있다. description은 교체 강제 속성이고 이름이 고정이라(같은 이름을 먼저 만들 수
  없다) 살아 있는 스택에서 바꾸면 SG 교체가 RDS와 ASG 부착 때문에 실패한다. 규칙의 참조는
  전부 backend-sg로 옮겼고 문구만 남았다 - 다음에 스택을 처음부터 지을 때 고친다.
- **ec2-sg 제거 apply의 순서 함정 (2026-09-02 staging 실측, KAN-165)**: ai-sg 인바운드 규칙의
  참조를 ec2-sg에서 backend-sg로 바꾸는 in-place 갱신이 ec2-sg 삭제보다 뒤에 잡혀, 삭제가
  DependencyViolation으로 계속 재시도됐다 (규칙이 옛 그룹을 참조하는 동안은 못 지운다).
  `aws ec2 modify-security-group-rules`로 참조를 먼저 바꿔 풀었다. 처음부터 짓는 스택에는
  없는 문제이고, 살아 있는 스택에서 SG를 갈아 끼울 때는 규칙 참조를 먼저 apply한다.
- **AI는 전용 EC2, 스텁 모드부터 (2026-08-31, 2026-09-01 확정, KAN-36 A단계)**: backend와 같은 호스트에
  두면 Fargate 전환(KAN-165)과 오토스케일링(KAN-168)이 ai까지 복제해 의미가 없고, 실모델(RSS 7.1GB,
  지속 추론)은 backend와 메모리를 나눌 수 없다. 분리 시점을 "실모델이 나오면"에서 "지금, 스텁으로"로
  당겨 prod를 최종 구조로 한 번만 세운다 (KAN-171). 인스턴스는 처음부터 c7i.xlarge다 (2026-09-01) -
  t3.small로 시작하는 안은 단계 전환에 인스턴스 교체를 하나 더 만들고 staging 실증과 prod의 유형을
  다르게 했다. 대가는 스텁 기간의 월 147달러다. 루트 볼륨(40GB), `mem_limit`, 실모델 이미지는 B단계에서
  tfvars와 KAN-22가 바꾼다. Fargate가 아니라 EC2인 이유는 모델 로드가 길고 이미지가 커서(약 8.5GB)
  스케일링 이점이 없고 GPU 선택지가 Fargate에 없기 때문이다. 1대 고정(ASG min 1 max 1)이라 교체 동안
  분석은 끊기고 회로가 열렸다 닫힌다 - 2대 이상은 내부 LB(월 약 20달러)가 필요해 미뤘다.
- **backend가 AI를 부르는 이름은 Route 53 프라이빗 영역 (KAN-36)**: ASG가 인스턴스를 교체하면 사설
  IP가 바뀌므로 주소를 SSM에 박을 수 없다. 인스턴스가 부팅 시 자기 IP로 `ai.accentury.internal` A
  레코드(TTL 10초)를 UPSERT하고 backend는 이름만 안다 - `ACCENTURY_ANALYSIS_AIBASEURL`의 정본은 여전히
  `modules/config`다. IAM은 그 영역, 그 이름, A 타입, UPSERT만 허용한다. 검토한 대안은 고정 ENI 사전
  생성(AZ에 묶여 ASG를 서브넷 1개로 제한, attach 실패 경로 추가)과 내부 NLB(월 16달러 이상, 2대
  이상일 때 쓰기로 한 것)였고, 프라이빗 영역은 월 0.5달러다. 영역 이름은 두 환경이 같다 - VPC별로
  풀리므로 충돌하지 않고 tfvars 차이도 늘지 않는다.
- **내부 호출은 보안 그룹 + 공유 시크릿 헤더 (KAN-36)**: 같은 compose 네트워크였을 때는 "backend만
  부른다"가 네트워크 구조로 보장됐다. 호스트가 갈라지면 ai-sg(ec2-sg만 8000)가 한 겹이고, 잘못 붙은
  SG나 뒤에 추가되는 VPC 안 호스트를 위해 `X-Accentury-Internal-Token`을 요청마다 대조한다
  (`ai/app/auth.py`, backend `RestAiAnalysisClient`). 난수 하나를 SSM 두 이름으로 싣고, ai 쪽은 하위
  경로 `/accentury/{env}/ai/`에 두어 ai 호스트 역할이 backend 시크릿(DB URL, 관리자 토큰)을 읽을 수
  없게 한다. health는 예외다 - compose healthcheck와 호스트 타이머가 토큰 없이 두드린다. 토큰이 어긋난
  배포는 backend가 429와 같은 추론 전 거절(UNREACHED)로 접어 사용자 시도 상한을 깎지 않고, 회로를
  열어 `ai-circuit-open` 경보로 드러낸다. ai 쪽은 운영 compose가 `ACCENTURY_AI_INTERNAL_TOKEN_REQUIRED=true`를
  주므로 토큰 없이는 기동하지 않는다 (fail-closed, backend 가드와 대칭).
- **ai 컨테이너의 인터넷과 IMDS 차단은 호스트 iptables (KAN-36)**: 같은 호스트 시절의 internal
  네트워크는 8000을 발행해야 하는 전용 호스트에서 쓸 수 없고, 보안 그룹 egress는 호스트와 컨테이너를
  못 가른다(호스트는 ECR, SSM, Route 53, CloudWatch에 나가야 한다). 그래서 `DOCKER-USER` 체인에
  "컨테이너 브리지에서 시작되는 연결 중 VPC 밖 목적지 DROP"을 넣고(docker가 만들고 비우지 않는
  사용자 체인), IMDS는 규칙 한 줄과 시작 템플릿의 hop limit 1로 겹쳐 막는다. 가드 유닛이 실패하면
  compose도 뜨지 않는다 (fail closed). 실모델이 런타임에 밖을 봐야 하면 그때 목적지를 열어 준다 -
  Whisper 가중치는 빌드 시 이미지에 넣는다 (KAN-22).
- **AI 경보는 커스텀 지표 2종 (KAN-36 사용자 결정)**: 대상 그룹 health가 없는 호스트라 타이머가
  `Healthy`를 올리고, 회로 열림은 backend의 Micrometer CloudWatch 레지스트리가 올린다. Boot 4는 이
  레지스트리를 자동 구성하지 않아 `CloudWatchMetricsConfig`가 직접 조립하고, `accentury.*` 지표만
  내보내 이름당 요금을 묶는다. 대안(회로 열림을 KAN-38로 미루기)은 "AI가 떠 있어도 추론만 죽은
  장애를 아무도 모른다"는 이유로 기각됐다.
- **파이프라인은 ai 호스트를 먼저 reload하고 롤백은 역순이다 (KAN-36)**: backend가 새 AI 계약을 전제로
  할 수 있어 AI가 먼저 올라와야 하고(새 AI는 옛 backend 호출을 받아야 한다), 롤백은 backend를 먼저
  되돌려 "옛 AI + 새 backend" 창을 만들지 않는다. Run Command 대상은 두 호스트의 Name 태그이고
  deploy 모듈이 그 둘만 허용한다.
- **ai 호스트의 compose 파일은 Terraform user_data로 배치** (2026-08-25 확정): 별도 전달
  채널(S3, Run Command) 없이 "재부팅 자동 기동"과 "두 환경 같은 파일" AC가
  성립한다. 대가는 compose 변경 시 인스턴스 교체인데, 호스트가 무상태라 감수한다.
  배포 파이프라인(KAN-128)은 SSM `IMAGE_TAG` 갱신과 `systemctl reload`만 한다.
  backend는 KAN-165부터 compose가 없다 - 태스크 정의가 그 자리다.
  compose 파일과 기동 스크립트는 `base64gzip`으로 실어 온다 (2026-08-26, KAN-129
  리뷰) - 평문이면 user_data가 16KB 상한의 91%라 주석 몇 줄에 apply가 터진다.
  압축 뒤 약 11KB이고, 더 커지면 S3 배치로 옮긴다.
- **ssm_prefix는 env와 결합** (2026-08-26): envs 변수 validation이 `/accentury/{env}`와
  정확히 같은지 plan에서 세운다. prod tfvars에 staging 접두사를 잘못 적으면 prod EC2
  역할이 staging 경로를 읽게 되기 때문이다. compute의 인스턴스 precondition도 config가
  만든 이름이 같은 접두사 아래에 있는지 본다.
- **env 파일은 tmpfs(/run)**: 재부팅마다 SSM에서 다시 읽으므로 "마지막으로
  반영된 태그"의 정본은 SSM이고, 호스트에 낡은 env 사본이 쌓이지 않는다. docker의
  컨테이너 config에는 환경 변수가 남는다 (암호화 루트 볼륨). 파일 시크릿 마운트로
  그것까지 없애려면 앱이 파일을 읽어야 하므로 KAN-129 판단 사항으로 넘긴다
  (Codex P2, 2026-08-25).
- **DB 자격 증명은 Secrets Manager에서 연결 시점에** (2026-08-25 확정, KAN-129):
  RDS 관리형 마스터 시크릿의 7일 회전을 앱이 따라가야 한다. 검토한 대안은 셋이다.
  (1) Terraform `random_password`로 고정 비밀번호 - 회전이 없고 단순하지만 state와
  SSM에 비밀번호가 남고 KAN-122의 관리형 시크릿 결정을 되돌린다. (2) 기동 스크립트가
  Secrets Manager를 읽어 env로 주입 - 회전 시점마다 reload가 필요해 운영 부담이 남는다.
  (3) AWS Advanced JDBC Wrapper `awsSecretsManager` 플러그인 - AWS가 문서화한 경로이고,
  회전 뒤 인증 실패에 시크릿을 다시 받아 재접속하며, 비밀번호가 어디에도 복사되지
  않는다. (3)을 골랐다. 앱 전용 DB 사용자 분리(최소 권한)는 정식 개발 전환 시 검토한다.
- **trusted-proxies는 VPC CIDR 하나** (KAN-129): VPC 오리진 구조에서 CloudFront 발
  트래픽은 VPC 안 ENI 사설 IP로 들어오고 ALB도 VPC 안이라 XFF의 오른쪽 두 홉이
  같은 대역에 든다. CloudFront 오리진 페이싱 공인 대역은 매칭될 일이 없어 넣지 않는다.
- **Spring 프로파일 이름은 `deploy`** (KAN-129): staging과 prod가 같은 이름을 쓴다.
  환경 이름을 프로파일로 쓰면 `application-staging.yml` 같은 환경별 파일이 생길 여지가
  남아 "환경 간 차이는 tfvars와 SSM 값뿐"이 깨진다.
- **SSM 파라미터는 Terraform 소유, IMAGE_TAG만 예외** (KAN-129): 값이 모듈 출력에서
  오므로 코드가 정본이다. IMAGE_TAG는 배포마다 바뀌어 Terraform이 소유하면 매 배포가
  drift라 파이프라인(KAN-128)이 쓴다. destroy해도 IMAGE_TAG는 남으므로 재구축 시
  이전 태그로 바로 뜰 수 있다 - 그래서 인스턴스가 config 파라미터 뒤에 만들어지도록
  이름 목록을 참조한다. 모듈 `depends_on`은 쓰지 않는다 - compute의 data 소스까지
  apply 시점으로 미뤄 user_data가 unknown이 되고, config 값 하나만 바뀌어도 인스턴스가
  교체된다. Fargate에서는 같은 목록이 태스크 정의 secrets와 실행 역할 허용 목록이 된다.
- **ai 컨테이너는 internal 네트워크에만** (2026-08-26, KAN-129 리뷰): backend가 Secrets
  Manager를 읽으려면 IMDSv2 hop limit이 2여야 하는데, 그 값은 bridge 안 컨테이너 전부에
  인스턴스 자격 증명을 연다. 신뢰하지 않는 오디오를 받는 ai가 뚫리면 RDS 시크릿과 관리자
  토큰까지 읽는 자격 증명이 새므로, ai는 게이트웨이 없는 internal 네트워크에만 붙여 IMDS와
  인터넷 경로를 끊는다. backend는 default와 internal 둘 다에 붙는다.
- **WAF 업로드 예외는 스코프 다운, override가 아님** (2026-08-28, KAN-149):
  `rule_action_override`로 본문 규칙 5개를 Count로 내리면 모든 경로에서 XSS/LFI/RFI 본문
  검사가 꺼진다. 스코프 다운은 업로드 경로만 CommonRuleSet에서 빼고 JSON 본문 경로의
  검사는 남긴다. 스코프 다운은 규칙 그룹 단위라 "업로드 경로에서 특정 규칙만 빼기"는
  불가능하고, 같은 관리형 그룹을 한 웹 ACL에 두 번 넣을 수도 없다.
- **rate-based rule은 세션 생성과 업로드만 센다** (KAN-149): 웹이 `/complete`를 800ms
  마다 POST로 폴링해 `POST /v0/*` 전체를 세면 정상 사용자 1명이 5분에 375건이다. GPU
  비용이 되는 경로는 세션 생성과 업로드뿐이다.
- **WAF 차단 응답은 backend 봉투와 같은 429 JSON** (KAN-149): 앱은 봉투 없는 응답을
  `retryable = false`로 판정해 재시도 버튼을 지운다 (`UploadClient.toResult`,
  `UploadStatusBar`). 기본 403을 쓰면 사용자가 막다른 길에 선다.
- **WAF 로그는 매치만, Authorization 마스킹, 샘플 요청 끔** (KAN-149): 기본 Allow까지
  남기면 정적 자산이 대부분이라 비용만 든다. 로그 헤더의 세션 Bearer 토큰과
  X-Admin-Token은 `redacted_fields`로 가린다. 샘플 요청 저장은 마스킹 보장을 문서에서
  확인하지 못해 끈다.
- **WAF는 staging에도 붙인다** (2026-08-28 확정, KAN-149 코멘트): 실모델 전환이 임박해
  오탐과 임계값을 staging에서 먼저 봐야 하고, 두 환경 구조를 같게 유지한다. 월 8달러를
  감수한다.
- **backend 태스크 0.5 vCPU / 2 GB (2026-08-28 확정, KAN-165)**: 힙은 2 GB의 75%(1.5 GB,
  `backend/Dockerfile` MaxRAMPercentage)이고 나머지가 네이티브 영역이다. EC2 시절의
  compose `mem_limit: 1g`(KAN-120 실측 권고 하한 - t3.small 2GB를 OS, docker와 나눴다)보다
  넉넉하다. Fargate 0.5 vCPU는 버스트가 없어 JVM 기동이 느리므로 grace period를 실측으로
  잡는다 (위 "backend Fargate 서비스").
- **ECR 라이프사이클 최근 50개**: 롤백(KAN-128)이 이전 SHA를 다시 당기는 방식이라
  최근 이미지가 남아 있어야 한다. prod가 50번 이상 전의 Dev 커밋을 돌리게 되면
  값을 올린다.
- **실모델 베이스 이미지는 별도 리포지토리 `accentury/ai-model`에 사람이 push**
  (2026-08-31 확정, KAN-173): 배포용 2개는 CI가 commit SHA 태그로 자동 push하는
  곳이라 손으로 올린 모델 이미지를 섞으면 "태그 = 커밋" 전제가 깨진다. 모델용은 태그가
  모델 해시다. 라이프사이클은 배포용과 같은 정책을 쓴다. 이미지 하나가 약 5GB라 50개가
  실제로 차면 저장 비용(GB당 월 0.10달러)이 드러나지만 모델 해시가 바뀔 때만 push하므로
  상한에 닿지 않는다. push 권한은 리포지토리 한정 정책 대신 모델 담당의 IAM 사용자
  `jaeyoung`에 AdministratorAccess를 주는 것으로 정했다 (3인 팀 규모의 사용자 결정).
- **graceful shutdown 유예 (2026-08-31 확정, KAN-166)**: backend는 SIGTERM에 readiness를 내리고
  (집계 `/actuator/health` 503), 웹 요청을 15초 안에 마친 뒤, 실행 중인 분석만 90초 예산 안에서
  기다리고 대기 중(미시작) 분석은 즉시 실패로 정리한다 - 원본 음성을 저장하지 않아(FR-DP-01)
  끊긴 분석은 재녹음뿐이라, 예산을 큐 길이만큼 늘리다 SIGKILL을 맞는 것보다 즉시 재녹음 안내가
  낫다. 그래서 compose `stop_grace_period`는 110초(docker 기본 10초면 시작하자마자 죽는다),
  systemd `TimeoutStopSec`는 180초(ExecStop의 compose down이 그 유예를 기다린다)다. 티켓 표의
  웹 유예 90초는 워커 예산과 합쳐 최악 180초라 ECS stopTimeout 120초를 넘어 15초로 줄였다.
  Fargate(KAN-165)에서는 태스크 정의 `stopTimeout` 120초와 대상 그룹 `deregistration_delay`
  30초가 그 자리다 - ECS는 등록 해제 지연이 끝난 뒤 SIGTERM을 보내고, 120초 뒤 SIGKILL이다.
  compose 값은 ai 호스트에만 남는다.
- **push-images.sh 기본 PLATFORM=linux/amd64**: IMMUTABLE 태그라 arm64로 잘못
  올린 SHA는 다시 올릴 수 없다. 기본값을 운영 아키텍처로 고정해 그 사고를 막는다.
- **오리진 구간(CloudFront -> ALB) HTTPS** (2026-08-25 확정, KAN-125): ALB
  리스너는 443 하나뿐이고(HTTP 80 없음) 서울 리전 ACM 인증서(accentury.app +
  *.accentury.app)를 건다. CloudFront는 오리진 인증서 도메인이 Origin domain 값
  또는 오리진으로 전달되는 Host 헤더와 맞으면 받아들이는데(AWS 문서 "Require
  HTTPS for communication between CloudFront and your custom origin"), API 동작이
  Managed-AllViewer라 Host가 ALB까지 가므로 ALB DNS 이름과 인증서가 달라도
  된다. VPC 오리진 정책은 https-only, alb-sg 인바운드는 443만 연다. 인증서
  2장(us-east-1은 뷰어 구간, 서울은 오리진 구간)이 각각 쓰인다.
