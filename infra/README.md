# infra - Terraform으로 staging, prod 환경 구축 (KAN-140)

AWS 스택 2벌(staging, prod)을 같은 모듈에서 tfvars만 바꿔 짓는다.
콘솔로 먼저 짓고 나중에 코드를 맞추는 순서는 금지다 (KAN-140).

## 구조

```
infra/
  bootstrap/          state 백엔드(S3 버킷), ECR 리포지토리, GitHub OIDC 공급자 등 계정 공유 리소스. 여기만 로컬 state.
  modules/
    network/          VPC, 서브넷, 보안 그룹 3종 + ai-sg, 내부 호출용 프라이빗 DNS 영역 (KAN-121, KAN-36)
    data/             RDS PostgreSQL (KAN-122)
    compute/          역할별 호스트 - backend 고정 EC2 1대, ai ASG. 운영 compose 파일 2개, 기동 스크립트,
                      systemd 유닛, ai egress 가드와 health 타이머 (KAN-124, KAN-36). 환경마다 두 번 호출한다
    config/           backend, ai 환경 변수의 정본인 SSM 파라미터와 시크릿 2종 (관리자 토큰, 내부 호출 토큰) (KAN-129, KAN-36)
    edge/             internal ALB, VPC 오리진, CloudFront, S3 (KAN-125, KAN-126)
    waf/              CloudFront 앞단 웹 ACL. us-east-1 프로바이더로 호출한다 (KAN-149)
    monitoring/       SNS 이메일과 CloudWatch 경보 6종 - 표준 지표 4종 + AI 호스트 2종 (KAN-134, KAN-36)
    deploy/           GitHub Actions가 OIDC로 맡는 환경별 배포 역할 (KAN-127)
  envs/
    staging/          main.tf + terraform.tfvars
    prod/             main.tf + terraform.tfvars
```

## 구성도

에픽 KAN-118(프로토타입 스텁 배포)이 정한 구조에 AI 전용 호스트 분리(KAN-36 A단계)를
더한 것이다. 요청은 위에서 아래로 한 줄로만 내려가고, 각 단계는 바로 앞 단계의 보안
그룹만 허용한다 (참조 사슬, KAN-121). 인터넷에서 ALB, 8080, 8000, 5432에 직접 닿을 길이
없고, ai는 backend 호스트(ec2-sg)만 8000으로 부를 수 있으며 요청마다 공유 시크릿 헤더를
대조한다.

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
═══════ VPC (staging 10.1.0.0/16 | prod 10.0.0.0/16, 피어링/NAT 없음) ═══════ KAN-121
                                         │
  사설 서브넷 x2 (AZ a, c)               ▼
  ┌──────────────────────────────────────────────────────────┐
  │ internal ALB  alb-sg: VPC 오리진 SG → 443만 (HTTPS) KAN-125 │
  │   대상 그룹 instance:8080, 헬스체크 /actuator/health KAN-131 │
  └──────────────────────────┬───────────────────────────────┘
                             │ 8080 (ec2-sg: alb-sg만, SSH 없음)
  퍼블릭 서브넷 x2           ▼
  ┌──────────────────────────────────────────────────────────┐
  │ backend 호스트 EC2 t3.small x86 (AL2023, 1대 고정)  KAN-124 │
  │   systemd accentury.service → accentury-up.sh            │
  │   docker compose (docker-compose.backend.yml)            │
  │   ┌──────────────┐                                       │
  │   │ backend :8080│ Spring Boot, mem 1g, 회로 상태 지표    │
  │   └──┬───────┬───┘                                       │
  └──────┼───────┼───────────────────────────────────────────┘
         │       │ http://ai.accentury.internal:8000
         │       │ + X-Accentury-Internal-Token (ai-sg: ec2-sg만 8000)
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
         │ 5432 (rds-sg: ec2-sg만)
  사설 서브넷 ▼
  ┌──────────────────────────────────────────────────────────┐
  │ RDS PostgreSQL 16  db.t4g.micro, 단일 AZ, 백업 7일  KAN-122 │
  │   마스터 비밀번호 = Secrets Manager 관리형                 │
  │   스키마 = Flyway 마이그레이션                      KAN-123 │
  └──────────────────────────────────────────────────────────┘

호스트가 밖으로 거는 연결 (퍼블릭 서브넷이라 NAT 불필요)
  ├─ SSM Session Manager (접속, SSH 키 없음)                     KAN-124
  ├─ SSM Parameter Store /accentury/{env}/*  → 컨테이너 env       KAN-129
  │    backend 호스트: 직계 파라미터 - SPRING_PROFILES_ACTIVE=deploy, SPRING_DATASOURCE_URL,
  │                    ACCENTURY_* (내부 호출 토큰 ACCENTURY_ANALYSIS_AITOKEN 포함, config 모듈)
  │    ai 호스트:      하위 경로 /ai/* (ACCENTURY_AI_INTERNAL_TOKEN)와 IMAGE_TAG만         KAN-36
  │    IMAGE_TAG (파이프라인 KAN-128, 두 호스트가 같은 값)
  ├─ Secrets Manager RDS 마스터 시크릿 → backend가 연결 시점에 직접 읽음  KAN-129
  │    (awsSecretsManager 플러그인, 7일 회전을 앱이 따라감)
  ├─ Route 53 프라이빗 영역 accentury.internal → ai 호스트가 ai.<영역> A 레코드 UPSERT  KAN-36
  ├─ CloudWatch PutMetricData → backend accentury/backend (Micrometer), ai accentury/ai (타이머)  KAN-36
  ├─ ECR accentury/backend, accentury/ai (commit SHA 태그)        KAN-120
  └─ ECR accentury/ai-model (실모델 베이스 이미지, 모델 해시 태그, 사람이 push)  KAN-173

배포 파이프라인 (GitHub Actions, OIDC, 환경별 역할 modules/deploy)
  이미지 deploy.yml                                                KAN-128
    Dev 병합     → 이미지 빌드 → ECR push(SHA) → SSM IMAGE_TAG 갱신 → Run Command 2회
                   (ai 호스트 → backend 호스트, KAN-36) systemctl reload accentury → healthy 대기
                   → 실패 시 직전 SHA로 두 호스트 자동 롤백 → E2E 스모크 KAN-138
    Release 병합 → 재빌드 없이 Dev 이력의 최신 빌드 SHA → (environment 승인) → prod
    롤백         → 수동 실행에 이전 SHA 입력, 같은 절차
  웹 번들 web-deploy.yml → S3 업로드 + CloudFront 무효화            KAN-127

운영
  ├─ ALB 5xx, 헬스체크 실패 알림 (CloudWatch)                      KAN-134
  ├─ AI health 실패, backend AI 회로 열림 알림 (커스텀 지표)         KAN-36
  └─ Terraform: bootstrap(state 버킷, ECR) + envs/{staging,prod}    KAN-140
     같은 모듈 x 2환경, 차이는 tfvars뿐. staging은 미사용 시 destroy
```

ECS/Fargate가 아직 없는 이유: backend의 요청 제한, 회로 차단기, 혼잡 판정, 디스패처
큐가 전부 프로세스 메모리라 인스턴스를 1개로 고정해 왔다 (아래 설계 결정 기록). 이
전제는 KAN-165(Fargate 전환)부터 KAN-169까지가 푼다. AI는 KAN-36 A단계로 전용 EC2에
스텁 모드로 분리됐고, 실모델 이미지 반영(B단계)과 GPU 여부는 KAN-22, KAN-57 이후다.
WAF(KAN-149)는 `modules/waf`가 us-east-1에 만들어 CloudFront 배포에 붙인다
(아래 'WAF 웹 ACL').

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
- ECR 이미지 (KAN-120): 두 호스트(backend t3.small, ai c7i.xlarge)가 모두 x86_64이므로
  이미지는 linux/amd64여야 한다. `scripts/push-images.sh`의 기본 PLATFORM이 그 값이다
  (KAN-124에서 고정).
  ECR 리포지토리 자체는 bootstrap 스택이 소유한다.
  실모델 베이스 이미지(`accentury/ai-model`)도 같은 이유로 linux/amd64여야 한다.
  이 리포지토리만 CI가 아니라 모델 담당이 직접 push한다 (아래 "실모델 베이스 이미지
  push" 절, KAN-173).

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
envs/*의 deploy 모듈이 data 소스로 조회하므로 bootstrap apply가 먼저다).
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
- `aws elbv2 describe-target-health --target-group-arn <arn>`으로 대상 healthy
  여부 (컨테이너 기동 전이면 unhealthy가 정상이다. KAN-124, KAN-128, KAN-129
  이후 healthy).
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
cd infra/envs/prod
terraform init
terraform plan   # staging과 diff가 tfvars 차이뿐인지 눈으로 확인
terraform apply
```

### 3. apply 이후 남는 수동 단계 (다른 티켓)

- 이미지 태그 반영: `/accentury/{env}/IMAGE_TAG`에 ECR의 commit SHA 태그를 넣고
  `systemctl start accentury` (첫 기동) 또는 `systemctl reload accentury`
  (교체). 파이프라인 `deploy.yml`(KAN-128)이 이 두 단계를 하므로 재구축 뒤에는
  Actions "Image Deploy"를 staging으로 수동 실행하면 된다 (아래 "이미지 배포
  파이프라인과 롤백").
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

반영 한 번은 SSM `/accentury/{env}/IMAGE_TAG`를 새 SHA로 바꾸고(직전 값은 기억), 호스트
둘에 차례로 SSM Run Command(`AWS-RunShellScript`)를 보낸다 (KAN-36): 먼저 ai 호스트
(`tag:Name=accentury-{env}-ai`), 다음 backend 호스트(`tag:Name=accentury-{env}`). 각 호스트에서
`systemctl reload accentury`(첫 기동이면 `start`)를 부른 뒤 그 호스트 compose에 정의된
컨테이너(ai 호스트는 ai, backend 호스트는 backend)의 docker healthcheck가 healthy가 될 때까지
최대 5분 기다린다. ai를 먼저 올리는 이유는 backend가 새 AI 계약을 전제로 할 수 있어서다.
어느 호스트든 unhealthy거나 시간을 넘기면 IMAGE_TAG를 직전 값으로 되돌려 두 호스트를
같은 순서로 다시 돌리고 실행을 실패로 끝낸다. 그래서 "반영 실패 시 기존 이미지 유지"가
성립한다 (컨테이너는 compose가 교체하므로 이미지 기준이다). 직전 값이 없는 첫 배포가
실패하면 호스트는 실패 상태로 남고, 유효한 태그를 수동 실행으로 넣는다. ai 호스트가 ASG
교체 중이라 대상이 없으면 60초 뒤 실패로 끝나므로 교체가 끝난 뒤 다시 실행한다.

컨테이너가 healthy가 된 뒤에는 공개 경로(`https://도메인/v0/...`)가 게이트웨이 오류
(502, 503, 504) 대신 백엔드 응답을 돌려줄 때까지 최대 6분 더 기다린다. ALB 대상 그룹
헬스체크가 healthy로 바뀌는 데 수십 초가 걸리고, 그동안 CloudFront 경유 요청은 502다.

어느 환경에 어떤 SHA가 떠 있는지는 세 곳에 남는다. 실행 요약 표(환경, 반영 태그,
출처, 직전 태그), GitHub environment의 deployments 목록, 그리고 SSM `IMAGE_TAG`
자체다. 인스턴스에서 확인하려면 `grep IMAGE_TAG /run/accentury/compose.env`.

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
자기 환경 이름이 붙은 두 호스트(`accentury-{env}`, `accentury-{env}-ai`)에만 보낼 수 있다.

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

## 호스트 컨테이너 기동 (KAN-124, 역할별 호스트 KAN-36)

호스트는 무상태다. 환경마다 둘이고(`modules/compute`를 `role = "backend"`, `role = "ai"`로
두 번 호출) 첫 부팅 user_data가 docker, compose, 그 역할의 운영 compose 파일
(`modules/compute/docker-compose.backend.yml` 또는 `docker-compose.ai.yml`, 호스트에서는
둘 다 `/opt/accentury/docker-compose.yml`), 기동 스크립트(`modules/compute/accentury-up.sh`),
systemd 유닛 `accentury.service`를 놓는다. 두 환경의 호스트 구성은 완전히 같고, 환경별
값은 전부 SSM Parameter Store에서 온다. compose 파일이나 스크립트를 고치면 user_data가
바뀌어 **인스턴스가 교체된다** (backend: `user_data_replace_on_change`, ai: 시작 템플릿 새
버전 + ASG instance refresh). 그래도 되는 이유가 무상태다.

| | backend 호스트 `accentury-{env}` | ai 호스트 `accentury-{env}-ai` |
| --- | --- | --- |
| 인스턴스 | `aws_instance` 1대 고정, t3.small | ASG min 1 max 1, c7i.xlarge (2026-09-01 결정으로 스텁 모드부터) |
| 보안 그룹 | ec2-sg (alb-sg만 8080) | ai-sg (ec2-sg만 8000) |
| 컨테이너 | backend (호스트 8080, ALB 대상) | ai (호스트 8000, backend 호스트만 닿는다) |
| SSM 읽기 | `/accentury/{env}/` 직계 (하위 경로 제외) | `/accentury/{env}/ai/*`와 `IMAGE_TAG`만 (IAM으로도 그것만) |
| IMDSv2 hop limit | 2 (backend가 Secrets Manager, CloudWatch를 읽는다) | 1 (컨테이너는 IMDS를 못 본다) |
| 추가 | - | Route 53 A 레코드 UPSERT, iptables egress 가드, health 타이머 |

기동 순서 (`accentury-up.sh`, 부팅마다 그리고 reload마다):

1. SSM 읽기. backend 호스트는 `get-parameters-by-path --path /accentury/{env}` (재귀 없음 -
   `/ai` 하위 경로는 내려오지 않는다), ai 호스트는 `--path /accentury/{env}/ai`와
   `get-parameter --name /accentury/{env}/IMAGE_TAG`.
2. 파라미터 이름의 마지막 조각을 환경 변수 이름으로 삼아 `/run/accentury/`
   (tmpfs, root 전용)에 env 파일 3개를 쓴다. 여기에 `ACCENTURY_ENV`(env.conf)를 더한다 -
   지표 차원이다.
3. (ai) IMDSv2로 자기 사설 IP를 읽어 프라이빗 영역에 `ai.accentury.internal` A 레코드(TTL
   10초)를 UPSERT한다. ASG가 인스턴스를 교체해도 backend가 보는 이름은 그대로다.
4. 인스턴스 프로파일로 ECR 로그인.
5. `docker compose --env-file /run/accentury/compose.env up -d --remove-orphans`
6. 어느 컨테이너도 쓰지 않는 이미지 정리 (`docker image prune -af`). 이전 SHA가
   루트 볼륨에 쌓이지 않게 한다. 롤백은 ECR에서 다시 당긴다.

1, 3, 4, 5는 일시 장애(IAM 전파 지연, 네트워크)에 대비해 최대 8회 백오프 재시도한다
(합계 약 4분). `IMAGE_TAG` 부재는 "배포 전" 상태라 재시도 없이 바로 실패한다.

ai 호스트에는 유닛이 둘 더 있다 (KAN-36). `accentury-egress-guard.service`는 docker 뒤,
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
| `IMAGE_TAG` | compose.env (두 호스트) | `image:` 보간. 두 서비스가 같은 SHA 태그를 쓴다. **없으면 기동 실패.** 파이프라인(KAN-128)이 쓴다 |
| `ai/ACCENTURY_AI_*` | ai.env (ai 호스트만) | ai 컨테이너 환경 변수. 지금은 내부 호출 토큰 하나 (KAN-36). 실모델 설정은 KAN-22가 더한다 |
| 그 외 전부 | backend.env (backend 호스트만) | backend 컨테이너 환경 변수. `modules/config`가 만든다 (아래 표, KAN-129) |

backend 환경 변수는 전부 Terraform `modules/config`가 만든다 - 값이 다른 모듈의
출력(RDS 주소, 시크릿 ARN, VPC CIDR, 도메인)이라 손으로 넣으면 재구축 때 어긋난다.
compute 모듈의 인스턴스가 config의 파라미터 이름 목록을 참조(precondition)하므로
첫 부팅의 기동 스크립트가 SSM을 읽는 시점에 파라미터가 이미 있다 (없으면 env 파일이 빈 채로 만들어지고 컨테이너
재시작은 그 파일을 재사용하므로 reload 전까지 기동이 막힌다).

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
재접속한다 (`backend/src/main/resources/application-deploy.yml`). EC2 역할은 자기
환경 시크릿 1개의 `GetSecretValue`만 가진다 (compute/main.tf). 비밀번호는 SSM,
state, env 파일 어디에도 없다.

관리자 토큰 값 읽기 (스모크 실행, 관리자 API 호출용):

```
aws ssm get-parameter --with-decryption --name /accentury/staging/ACCENTURY_ADMIN_TOKEN \
  --query Parameter.Value --output text
```

재발급은 `terraform apply -replace='module.config.random_password.admin_token'`(taint는
deprecated) 뒤 backend 호스트에서 `systemctl reload accentury`다. 내부 호출 토큰은
`-replace='module.config.random_password.ai_internal_token'` 뒤 **두 호스트 모두** reload한다 (ai 먼저 -
그 사이 backend 호출은 401로 끊겨 회로가 열렸다가 닫힌다). 값은 Terraform state(S3 암호화 + 버전 관리
버킷)에 남는다 - KAN-140이 수용한 범위이고, 레포, 이미지, 로그에는 없다.

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

값에 탭이나 개행은 넣을 수 없다 (`--output text` 파싱 단위). 그 외 문자(`$`,
따옴표, `#`, 공백)는 그대로 컨테이너에 들어간다 - env_file을 `format: raw`로
읽어 Compose의 보간과 따옴표 처리를 끈다. 시크릿은 SecureString으로 두면 되고,
EC2 역할은 자기 환경 경로만 읽는다 (compute/main.tf IAM 정책). env 파일은
tmpfs라 재부팅 시 사라졌다가 다시 만들어진다 (낡은 사본이 쌓이지 않는다).
docker 자체는 컨테이너 환경 변수를 `/var/lib/docker/containers/*/config.v2.json`
(암호화된 루트 볼륨, root 전용)에 기록하므로 호스트 디스크에 평문이 전혀 없는
것은 아니다. 레포, 이미지, 로그에 없다는 것이 KAN-122/129의 요구이고 그것은
충족한다.

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
하나(`accentury-{env}-alerts`)에 이메일 구독을 걸고 CloudWatch 경보 4종을 붙인다
(`infra/modules/monitoring`, 두 환경 각 1벌). 지표를 새로 수집하지 않는다. 전부 ALB,
RDS, EC2가 이미 내보내는 표준 지표라 backend와 ai 코드는 손대지 않는다. 지표 수집과
대시보드, correlation ID 규약은 KAN-38이고, 이 절은 그중 최소 선행분이다.

| 경보 | 지표 | 조건 | 결측 처리 |
| --- | --- | --- | --- |
| `unhealthy-hosts` | `UnHealthyHostCount` (TargetGroup + LoadBalancer) | 1분 최대 >= 1이 2회 연속 | `breaching` |
| `alb-5xx` | `HTTPCode_ELB_5XX_Count` + `HTTPCode_Target_5XX_Count` 합 | 1분 합계 > 5가 2회 연속 | `notBreaching` |
| `rds-free-storage` | `FreeStorageSpace` | 5분 최소 < 2GiB | `missing` |
| `ec2-cpu-surplus` | `CPUSurplusCreditBalance` (backend 호스트) | 5분 최대 > 144가 2회 연속 | `missing` |
| `ai-unhealthy` (KAN-36) | `accentury/ai` `Healthy` (차원 env, ai 호스트 타이머가 올린다) | 1분 최소 < 1이 3회 연속 | `breaching` |
| `ai-circuit-open` (KAN-36) | `accentury/backend` `accentury.ai.circuit.state.value` (차원 env, backend Micrometer) | 1분 최대 >= 1이 2회 연속 | `notBreaching` |

앞의 둘이 KAN-134의 필수 2종이고 다음 둘은 "선택" 항목인데 두 환경 모두 넣기로 했다
(2026-08-28). 마지막 둘은 AI 전용 호스트 분리(KAN-36)가 더한 것이다 - AI 호스트는 ALB 뒤가
아니라 대상 그룹 health가 없고, 회로 상태는 어떤 표준 지표에도 없다. 여섯 경보 모두 ALARM과
OK 양쪽을 알린다. 해제 알림이 없으면 아직 죽어 있는지를 콘솔에서 확인해야 하기 때문이다.

몇 가지가 의도된 선택이다.

- **5xx는 두 지표의 합이다.** `ELB_5XX`만 보면 backend가 500을 쏟는 상황을 놓치고,
  `Target_5XX`만 보면 backend가 아예 죽어 ALB가 502를 내는 상황을 놓친다. 합계
  하나로 보면 경보 개수도 한 개로 유지되어 "이 티켓은 경보 2종만"이라는 KAN-38
  경계가 그대로 선다.
- **`unhealthy-hosts`만 결측을 장애로 센다.** 이 지표는 대상이 등록돼 있는 한 계속
  나오므로 끊겼다는 것은 ALB나 대상 그룹이 사라졌다는 뜻이다. 대가로 스택을 새로
  apply한 직후 EC2가 부팅하는 몇 분 동안 한 번 울린다. 이때는 대상이 실제로
  비정상이라 경보가 맞고, 뜨고 나면 OK 알림이 따라온다.
- **5xx는 결측을 정상으로 센다.** 트래픽이 0인 시간대에는 지표가 나오지 않는다.
  서버가 죽은 것은 `unhealthy-hosts`가 트래픽과 무관하게 잡는다. 다만 이미 ALARM인
  상태에서 트래픽이 완전히 끊기면 **해제되지 않고 ALARM에 머문다.** 경보를 다시
  평가하려면 데이터포인트가 최소 하나는 필요하기 때문이다 (2026-08-28 실측: 8분간
  요청 0인 동안 ALARM 유지, 정상 요청이 들어온 직후 OK). 아무도 복구를 확인해 주지
  않은 상태에서 알아서 내려가지 않는 편이 안전하므로 그대로 둔다.
- **토픽에 SSE를 켜지 않는다.** AWS 관리형 키 `alias/aws/sns`는 키 정책에
  `cloudwatch.amazonaws.com`이 없어서, 켜면 경보 상태는 ALARM인데 메일만 조용히
  안 오는 상태가 된다. 나르는 내용이 "어느 경보가 어느 상태로 바뀌었다"뿐이라
  고객 관리형 키를 따로 만들 이유가 없다.
- **`ec2-cpu-surplus`는 성능이 아니라 비용 신호다.** compute 모듈이
  `credit_specification`을 지정하지 않아 t3.small이 기본값 unlimited로 뜬다
  (2026-08-28 실측 확인). 크레딧이 0이 돼도 스로틀이 걸리지 않고 빌린 만큼이
  요금으로 붙으므로, 이 경보는 "느려진다"가 아니라 "부하가 기준선을 넘겨 요금이
  붙기 시작한다"는 뜻이다.
- **AI 지표는 둘 다 커스텀이다 (KAN-36).** `Healthy`는 ai 호스트의 systemd 타이머가 1분마다
  `aws cloudwatch put-metric-data`로 올린다 (호스트 역할의 PutMetricData는 네임스페이스
  `accentury/ai`로만 허용). 워밍업 중(503 STARTING)도 0이다. 결측을 장애로 세므로 apply 직후와
  ASG 교체 직후 몇 분은 한 번 운다 - `unhealthy-hosts`와 같은 성격이다. 회로 상태는 backend의
  Micrometer CloudWatch 레지스트리(`CloudWatchMetricsConfig`, 배포 프로파일에서만)가 `accentury.*`
  지표만 1분마다 올린다 - 게이지라 이름에 `.value`가 붙는다 (0 닫힘, 1 반열림, 2 열림). JVM이나
  HTTP 지표는 올리지 않는다 - 이름당 월 0.30달러다. 두 지표의 환경 구분은 차원 `env`다.
- **그 경보가 `CPUCreditBalance`가 아니라 `CPUSurplusCreditBalance`를 보는
  이유.** unlimited 인스턴스는 잔액 0으로 시작해 시간당 24개씩 쌓는다. 잔액
  하한으로 경보를 걸면 **새로 뜬 인스턴스가 임계값에 닿을 때까지 몇 시간 동안
  무조건 운다.** 2026-08-28 staging 실증에서 실제로 그렇게 됐다 (기동 직후
  `CPUCreditBalance` 0.0, 경보 ALARM). 초과 크레딧은 정상 부하에서 0 근처에
  머물다가 기준선을 넘겨 쓴 만큼만 쌓이므로 기동 오탐이 없고, "크레딧을 다 쓰고
  빚을 지기 시작했다"는 뜻이 지표 그대로다. 임계값 144는 여섯 시간치 벌이만큼
  빚진 상태이고 실제 과금이 시작되는 576의 4분의 1이다. 기동 직후 스파이크는
  실측 0.5로 한참 아래다.

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

AC 실증은 backend 컨테이너를 내려서 한다. EC2는 살려 두고 컨테이너만 멈춰야
헬스체크 실패와 ALB 502가 함께 나온다. 컨테이너 이름은 compose 프로젝트 이름이
붙어 `accentury-backend-1`이므로, 이름 대신 서비스 이름으로 멈춘다.

```
# 컨테이너만 정지 (SSM Run Command, 대상은 인스턴스 ID가 아니라 Name 태그)
aws ssm send-command --document-name AWS-RunShellScript \
  --targets Key=tag:Name,Values=accentury-staging \
  --parameters 'commands=["cd /opt/accentury && docker compose stop backend"]'
```

여기까지로 `unhealthy-hosts`가 선다. 헬스체크가 실패하는 매 분 데이터가 나오므로
2회 연속 조건이 저절로 충족된다.

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

분당 약 36건이라 임계치 5를 세 분 연속 넘긴다. 복구는 기동 스크립트를 다시 태우면
된다 (`up -d`가 멈춘 컨테이너를 다시 올린다).

```
aws ssm send-command --document-name AWS-RunShellScript \
  --targets Key=tag:Name,Values=accentury-staging \
  --parameters 'commands=["systemctl reload accentury"]'
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

2026-08-28 staging 실측값이다.

| 구간 | 걸린 시간 |
| --- | --- |
| 컨테이너 정지부터 `unhealthy-hosts` ALARM까지 | 5분 47초 |
| 트래픽 시작부터 `alb-5xx` ALARM까지 | 3분 38초 |

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
| 강제 종료부터 backend 호스트에서 health 200까지 | 2분 39초 |

교체 중 업로드가 없으면 회로는 닫힌 채고, 결측 2분은 `ai-unhealthy`의 3회 조건에 못 미쳐 경보가 서지 않았다.

`unhealthy-hosts`가 더 걸리는 이유는 앞에 ALB 헬스체크 판정이 한 겹 더 있기 때문이다.
컨테이너가 멈춰도 대상이 unhealthy로 바뀌는 데 연속 실패 판정만큼 걸리고, 그 뒤에야
지표가 1이 되어 1분 × 2회 평가가 시작된다. 티켓 AC의 "수 분 내"는 이 값 기준이다.
복구 뒤에는 같은 간격으로 OK 메일이 온다.

### 비용

CloudWatch 표준 경보는 개당 월 0.10달러, 지표 math 경보(`alb-5xx`)는 지표 2개를 세어
0.20달러다. 경보 6종에 환경당 월 약 0.70달러, 커스텀 지표(`Healthy` 1개 + backend `accentury.*`
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
| RDS 마스터 시크릿 | `rds!db-<staging uuid>` | `rds!db-<prod uuid>` | `SPRING_DATASOURCE_URL`의 `secretsManagerSecretId`, EC2 역할 정책 |
| SSM 경로 | `/accentury/staging/*` | `/accentury/prod/*` | EC2 역할 정책, 기동 스크립트 |
| 관리자 토큰 | 환경별 난수 | 환경별 난수 | `ACCENTURY_ADMIN_TOKEN` |
| 내부 호출 토큰 (KAN-36) | 환경별 난수 | 환경별 난수 | `ACCENTURY_ANALYSIS_AITOKEN`, `ai/ACCENTURY_AI_INTERNAL_TOKEN` |
| AI 호스트 (KAN-36) | c7i.xlarge, 루트 20GB | 같은 값 | `ai_instance_type`, `ai_root_volume_size` (실모델 전환 시 40) |
| RDS 삭제 보호, 최종 스냅샷 | 없음, 생략 | 켬, 남김 | RDS |
| 배포 역할 ECR push | 허용 | 불가 | `modules/deploy` image-deploy 정책 (KAN-128 승격 모델) |

staging 설정으로 prod에 닿을 수 없는 이유: VPC가 분리돼 있고(피어링 없음), EC2
역할이 자기 환경의 SSM 경로와 시크릿 ARN만 허용하며, 시크릿 ARN 자체가 환경별로
다르다. 프라이빗 영역 `accentury.internal`은 이름이 같지만 각 환경 VPC에만 연결돼
있어 staging backend가 prod ai를 풀 수 없다.

운영 확인 (SSM Session Manager로 접속한 뒤, 두 호스트 같은 명령):

```
systemctl status accentury          # 마지막 기동 결과
journalctl -u accentury -n 50       # 기동 스크립트 로그 (어느 태그를 읽었는지, ai는 Route 53 갱신)
docker compose -f /opt/accentury/docker-compose.yml --env-file /run/accentury/compose.env ps
```

backend 호스트의 `backend`는 호스트 8080(ALB 대상), ai 호스트의 `ai`는 호스트 8000(ai-sg가
backend 호스트만 허용)을 발행한다. 두 컨테이너는 `restart: unless-stopped`라 프로세스가
죽으면 docker가 다시 띄우고, 호스트가 재부팅되면 systemd 유닛이 SSM 값을 새로 읽어
`up -d`를 다시 건다. ai 호스트 자체가 죽으면 ASG가 교체하고 새 인스턴스가 같은 이름의
A 레코드를 갱신한다.

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
  않는다. 남겨 두면 다음 재구축 때 인스턴스가 그 SHA로 뜬다.
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
- **AMI 고정**: AL2023 최신 AMI를 SSM 파라미터로 읽되 `ignore_changes = [ami]`.
  AMI 갱신이 "plan No changes" AC를 깨고 인스턴스 교체를 유발하지 않게 한다.
- **PriceClass_200**: 한국이 포함되는 최소 티어.
- **backend 인스턴스 1대, backend 컨테이너 1개, ai 워커 1개 고정. scale 금지
  (KAN-124, 호스트 분리 뒤에도 KAN-36)**: backend가 다음 상태를 전부 프로세스 메모리에 둔다. 둘 이상이면
  상태가 갈라져 요청 제한이 배로 풀리고 회로 차단기가 서로 다른 판단을 한다.
  - 요청 제한 5축 (`RateLimits`: 세션 생성 IP, 업로드 IP, 업로드 세션, 어휘
    세션, 완료)
  - AI 회로 차단기 (`AiCircuitBreaker`: 닫힘/열림/반열림 상태, 연속 실패 카운터)
  - pollAfterMs 혼잡 판정 (진행 중 AI 전달 건수 임계치)
  - 분석 디스패처 풀 (`dispatch-concurrency` 4워커의 인메모리 큐)

  ai는 임시 디렉터리 하나를 프로세스 하나가 전용으로 쓴다 (KAN-27). 워커를
  늘리면 기동 시 잔여물 정리가 형제 워커의 처리 중 오디오를 지운다.
  `--workers 1`은 `ai/Dockerfile`이 고정한다. 같은 내용이 운영 compose 파일
  머리에도 있다.
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
  배포는 backend가 계약 위반으로 접어 회로를 열고 `ai-circuit-open` 경보로 드러난다.
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
- **파이프라인은 ai 호스트를 먼저 reload한다 (KAN-36)**: backend가 새 AI 계약을 전제로 할 수 있어
  AI가 먼저 올라와야 한다. 롤백도 같은 순서다. Run Command 대상은 두 호스트의 Name 태그이고 deploy
  모듈이 그 둘만 허용한다.
- **compose 파일은 Terraform user_data로 배치** (2026-08-25 확정): 별도 전달
  채널(S3, Run Command) 없이 "재부팅 자동 기동"과 "두 환경 같은 파일" AC가
  성립한다. 대가는 compose 변경 시 인스턴스 교체인데, 호스트가 무상태라 감수한다.
  배포 파이프라인(KAN-128)은 SSM `IMAGE_TAG` 갱신과 `systemctl reload`만 한다.
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
  교체된다.
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
- **backend `mem_limit: 1g`**: KAN-120 실측 권고 하한. t3.small 2GB에서 힙
  768MB(75%) + 나머지 25%로 네이티브 영역, 남은 1GB를 OS, docker, ai 스텁이 쓴다.
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
  Fargate 전환(KAN-165)에서는 ECS stopTimeout 120초가 같은 자리다.
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
