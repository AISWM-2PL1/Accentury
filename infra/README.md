# infra - Terraform으로 staging, prod 환경 구축 (KAN-140)

AWS 스택 2벌(staging, prod)을 같은 모듈에서 tfvars만 바꿔 짓는다.
콘솔로 먼저 짓고 나중에 코드를 맞추는 순서는 금지다 (KAN-140).

## 구조

```
infra/
  bootstrap/          state 백엔드(S3 버킷)와 ECR 리포지토리 등 계정 공유 리소스. 여기만 로컬 state.
  modules/
    network/          VPC, 서브넷, 보안 그룹 3종 (KAN-121)
    data/             RDS PostgreSQL (KAN-122)
    compute/          EC2, 운영 compose 파일, 기동 스크립트, systemd 유닛 (KAN-124)
    config/           backend 환경 변수의 정본인 SSM 파라미터와 관리자 토큰 (KAN-129)
    edge/             internal ALB, VPC 오리진, CloudFront, S3 (KAN-125, KAN-126)
  envs/
    staging/          main.tf + terraform.tfvars
    prod/             main.tf + terraform.tfvars
```

## 구성도

에픽 KAN-118(프로토타입 스텁 배포)이 정한 구조다. 요청은 위에서 아래로 한 줄로만
내려가고, 각 단계는 바로 앞 단계의 보안 그룹만 허용한다 (참조 사슬, KAN-121).
인터넷에서 ALB, 8080, 5432에 직접 닿을 길이 없고, ai는 EC2 안 compose 내부
네트워크에만 있어 backend 말고는 아무도 부를 수 없다.

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
  │ EC2 t3.small x86 (AL2023, 환경당 1대 고정)         KAN-124 │
  │   systemd accentury.service → accentury-up.sh            │
  │   docker compose (/opt/accentury/docker-compose.yml)     │
  │   ┌──────────────┐  http://ai:8000  ┌────────────────┐   │
  │   │ backend :8080│ ───────────────▶ │ ai :8000        │   │
  │   │ Spring Boot  │   내부 네트워크만 │ FastAPI 워커 1  │   │
  │   └──────┬───────┘                  └────────────────┘   │
  └──────────┼───────────────────────────────────────────────┘
             │ 5432 (rds-sg: ec2-sg만)
  사설 서브넷 ▼
  ┌──────────────────────────────────────────────────────────┐
  │ RDS PostgreSQL 16  db.t4g.micro, 단일 AZ, 백업 7일  KAN-122 │
  │   마스터 비밀번호 = Secrets Manager 관리형                 │
  │   스키마 = Flyway 마이그레이션                      KAN-123 │
  └──────────────────────────────────────────────────────────┘

EC2가 밖으로 거는 연결 (퍼블릭 서브넷이라 NAT 불필요)
  ├─ SSM Session Manager (접속, SSH 키 없음)                     KAN-124
  ├─ SSM Parameter Store /accentury/{env}/*  → 컨테이너 env       KAN-129
  │    SPRING_PROFILES_ACTIVE=deploy, SPRING_DATASOURCE_URL, ACCENTURY_* (config 모듈)
  │    IMAGE_TAG (파이프라인 KAN-128)
  ├─ Secrets Manager RDS 마스터 시크릿 → backend가 연결 시점에 직접 읽음  KAN-129
  │    (awsSecretsManager 플러그인, 7일 회전을 앱이 따라감)
  └─ ECR accentury/backend, accentury/ai (commit SHA 태그)        KAN-120

배포 파이프라인 (GitHub Actions, OIDC)                            KAN-128
  Dev 병합     → 테스트 → 이미지 빌드 → ECR push → SSM IMAGE_TAG 갱신
                 → systemctl reload accentury → staging 자동 → E2E 스모크 KAN-138
  Release 병합 → 재빌드 없이 같은 SHA → 승인 → prod
  웹 번들      → S3 업로드 + CloudFront 무효화                     KAN-127

운영
  ├─ ALB 5xx, 헬스체크 실패 알림 (CloudWatch)                      KAN-134
  └─ Terraform: bootstrap(state 버킷, ECR) + envs/{staging,prod}    KAN-140
     같은 모듈 x 2환경, 차이는 tfvars뿐. staging은 미사용 시 destroy
```

ECS/Fargate가 없는 이유: backend의 요청 제한, 회로 차단기, 혼잡 판정, 디스패처
큐가 전부 프로세스 메모리라 인스턴스를 1개로 고정해야 하고, 그러면 ECS의 이점이
사라진다 (아래 설계 결정 기록). AI 실모델용 GPU 서버 분리는 KAN-36, KAN-57 판정
이후다. WAF(KAN-149)는 이 문서 작성 시점(2026-08-25)에 아직 코드가 없다.

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
- ECR 이미지 (KAN-120): EC2가 t3.small(x86_64)이므로 이미지는 linux/amd64여야
  한다. `scripts/push-images.sh`의 기본 PLATFORM이 그 값이다 (KAN-124에서 고정).
  ECR 리포지토리 자체는 bootstrap 스택이 소유한다.

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
ECR 리포지토리 `accentury/backend`와 `accentury/ai` (IMMUTABLE, 라이프사이클
정책: 태그 없는 이미지 1일, 최근 50개 유지).
로컬에 남는 `terraform.tfstate`는 커밋하지 않는다 (.gitignore 처리 완료).

ECR 리포지토리는 KAN-120이 콘솔에서 먼저 만들었다. `ecr.tf`의 import 블록이
첫 plan에서 기존 리포지토리를 state로 흡수하므로 별도 import 명령이 없다.
plan에 `scan_on_push`와 라이프사이클 정책이 갱신/생성으로 나오는 것은 정상이다.
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

- 이미지 태그 반영: `/accentury/{env}/IMAGE_TAG`에 ECR의 commit SHA 태그를 넣고
  `systemctl start accentury` (첫 기동) 또는 `systemctl reload accentury`
  (교체). 파이프라인(KAN-128)이 이 두 단계를 자동화한다.
- 웹 번들과 등급 이미지 S3 업로드 (KAN-127).

## EC2 컨테이너 기동 (KAN-124)

EC2는 무상태 호스트다. 첫 부팅 user_data가 docker, compose, 운영 compose 파일
(`modules/compute/docker-compose.yml`), 기동 스크립트
(`modules/compute/accentury-up.sh`), systemd 유닛 `accentury.service`를 놓는다.
두 환경의 호스트 구성은 완전히 같고, 환경별 값은 전부 SSM Parameter Store에서
온다. compose 파일이나 스크립트를 고치면 user_data가 바뀌어 **인스턴스가
교체된다** (`user_data_replace_on_change`). 그래도 되는 이유가 무상태다.

기동 순서 (`accentury-up.sh`, 부팅마다 그리고 reload마다):

1. `aws ssm get-parameters-by-path --path /accentury/{env} --recursive --with-decryption`
2. 파라미터 이름의 마지막 조각을 환경 변수 이름으로 삼아 `/run/accentury/`
   (tmpfs, root 전용)에 env 파일 3개를 쓴다.
3. 인스턴스 프로파일로 ECR 로그인.
4. `docker compose --env-file /run/accentury/compose.env up -d --remove-orphans`
5. 어느 컨테이너도 쓰지 않는 이미지 정리 (`docker image prune -af`). 이전 SHA가
   루트 볼륨에 쌓이지 않게 한다. 롤백은 ECR에서 다시 당긴다.

1, 3, 4는 일시 장애(IAM 전파 지연, 네트워크)에 대비해 최대 8회 백오프 재시도한다
(합계 약 4분). `IMAGE_TAG` 부재는 "배포 전" 상태라 재시도 없이 바로 실패한다.

| SSM 파라미터 (`/accentury/{env}/` 아래) | 가는 곳 | 용도 |
| --- | --- | --- |
| `IMAGE_TAG` | compose.env | `image:` 보간. 두 서비스가 같은 SHA 태그를 쓴다. **없으면 기동 실패.** 파이프라인(KAN-128)이 쓴다 |
| `ACCENTURY_AI_*` | ai.env | ai 컨테이너 환경 변수 (스텁은 없어도 뜬다) |
| 그 외 전부 | backend.env | backend 컨테이너 환경 변수. `modules/config`가 만든다 (아래 표, KAN-129) |

backend 환경 변수는 전부 Terraform `modules/config`가 만든다 - 값이 다른 모듈의
출력(RDS 주소, 시크릿 ARN, VPC CIDR, 도메인)이라 손으로 넣으면 재구축 때 어긋난다.
compute 모듈의 인스턴스가 config의 파라미터 이름 목록을 참조(precondition)하므로
첫 부팅의 기동 스크립트가 SSM을 읽는 시점에 파라미터가 이미 있다 (없으면 env 파일이 빈 채로 만들어지고 컨테이너
재시작은 그 파일을 재사용하므로 reload 전까지 기동이 막힌다).

| `/accentury/{env}/` 아래 | 값 | 타입 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `deploy` (두 환경 동일). 이 프로파일에서만 backend가 아래 5개 누락 시 기동을 세운다 (`DeploymentConfigGuard`) | String |
| `SPRING_DATASOURCE_URL` | `jdbc:aws-wrapper:postgresql://<RDS 주소>:5432/accentury?secretsManagerSecretId=<마스터 시크릿 ARN>` | String |
| `ACCENTURY_ANALYSIS_AIBASEURL` | `http://ai:8000` (compose 내부 네트워크, 두 환경 동일) | String |
| `ACCENTURY_TRUSTEDPROXIES` | 해당 환경 VPC CIDR 하나 | String |
| `ACCENTURY_RESULT_WEBTESTURL` | `https://<도메인>/t?c=kko_share` | String |
| `ACCENTURY_ADMIN_TOKEN` | `random_password` 48자 영숫자. 관리자 API(§6)와 E2E 스모크(KAN-138)가 쓴다 | SecureString |

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

재발급은 `terraform taint 'module.config.random_password.admin_token'` 후 apply, 그리고
인스턴스에서 `systemctl reload accentury`다. 값은 Terraform state(S3 암호화 + 버전 관리
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
정식 해법은 파이프라인(KAN-128)이 OIDC로 해당 환경의 SSM을 읽어 `workflow_call`의
`admin-token`으로 넘기는 것이고, 그 전까지 수동 실행은 staging 값으로 맞춘다.

### 이미 있는 SSM 파라미터 (재구축, 수동 생성분)

`modules/config`가 만드는 이름이 계정에 이미 있으면 apply가 `ParameterAlreadyExists`로
실패한다 - `aws_ssm_parameter`는 Terraform이 만들지 않은 값을 덮어쓰지 않는다.
2026-08-26 기준 `/accentury/*` 아래에는 파라미터가 없다 (CLI 확인). 이후 손으로 만든 값이
있거나 destroy 없이 이 코드를 처음 적용하는 스택이면 두 가지를 한다.

1. 같은 이름 6개는 import로 state에 흡수한다. 값은 apply가 코드 값으로 갱신한다
   (`admin_token`은 `random_password`의 새 값으로 덮인다).

   ```
   cd infra/envs/staging
   for r in spring_profiles_active:SPRING_PROFILES_ACTIVE ai_base_url:ACCENTURY_ANALYSIS_AIBASEURL \
            datasource_url:SPRING_DATASOURCE_URL trusted_proxies:ACCENTURY_TRUSTEDPROXIES \
            web_test_url:ACCENTURY_RESULT_WEBTESTURL admin_token:ACCENTURY_ADMIN_TOKEN; do
     terraform import "module.config.aws_ssm_parameter.${r%%:*}" "/accentury/staging/${r##*:}"
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
| RDS 삭제 보호, 최종 스냅샷 | 없음, 생략 | 켬, 남김 | RDS |

staging 설정으로 prod에 닿을 수 없는 이유: VPC가 분리돼 있고(피어링 없음), EC2
역할이 자기 환경의 SSM 경로와 시크릿 ARN만 허용하며, 시크릿 ARN 자체가 환경별로
다르다.

운영 확인 (SSM Session Manager로 접속한 뒤):

```
systemctl status accentury          # 마지막 기동 결과
journalctl -u accentury -n 50       # 기동 스크립트 로그 (어느 태그를 읽었는지)
docker compose -f /opt/accentury/docker-compose.yml --env-file /run/accentury/compose.env ps
```

`backend`만 호스트 8080을 발행하고(ALB 대상), `ai`는 포트를 발행하지 않는다.
두 컨테이너는 `restart: unless-stopped`라 프로세스가 죽으면 docker가 다시 띄우고,
호스트가 재부팅되면 systemd 유닛이 SSM 값을 새로 읽어 `up -d`를 다시 건다.

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
- **EC2 인스턴스 1대, backend 컨테이너 1개, ai 워커 1개 고정. scale 금지
  (KAN-124)**: backend가 다음 상태를 전부 프로세스 메모리에 둔다. 둘 이상이면
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
- **compose 파일은 Terraform user_data로 배치** (2026-08-25 확정): 별도 전달
  채널(S3, Run Command) 없이 "재부팅 자동 기동"과 "두 환경 같은 파일" AC가
  성립한다. 대가는 compose 변경 시 인스턴스 교체인데, 호스트가 무상태라 감수한다.
  배포 파이프라인(KAN-128)은 SSM `IMAGE_TAG` 갱신과 `systemctl reload`만 한다.
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
- **backend `mem_limit: 1g`**: KAN-120 실측 권고 하한. t3.small 2GB에서 힙
  768MB(75%) + 나머지 25%로 네이티브 영역, 남은 1GB를 OS, docker, ai 스텁이 쓴다.
- **ECR 라이프사이클 최근 50개**: 롤백(KAN-128)이 이전 SHA를 다시 당기는 방식이라
  최근 이미지가 남아 있어야 한다. prod가 50번 이상 전의 Dev 커밋을 돌리게 되면
  값을 올린다.
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
