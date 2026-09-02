# backend를 ECS Fargate 서비스로 띄운다 (KAN-165). EC2 위 docker compose(KAN-124)를 대체한다.
#
#   클러스터      accentury-{env}. 용량 공급자는 FARGATE(온디맨드)만 연결한다 - FARGATE_SPOT은 붙이지 않는다
#                 (2026-09-01 결정: base 1이 온디맨드라 절감이 0에 가깝고, 용량 부족 시 온디맨드로 대체하지
#                 않는 위험만 더한다). 서비스는 launch_type이 아니라 용량 공급자 전략으로 만들어, 나중에 Spot을
#                 붙일 때 전략만 바꾸면 된다.
#   태스크 정의   패밀리 accentury-{env}-backend. 0.5 vCPU / 2 GB, x86_64, 컨테이너 backend 하나. 환경 변수는
#                 SSM /accentury/{env}/* 를 secrets로 주입한다 (목록은 config 모듈 출력이고 정본은 backend의
#                 DeploymentConfigGuard.SSM_NAMES다). stopTimeout 120초 (KAN-166 종료 예산).
#   서비스        backend. desired 1 (오토스케일링은 KAN-168). ALB 대상 그룹(ip)에 붙고 회로 차단기와 자동
#                 롤백을 켠다. 퍼블릭 서브넷 + 퍼블릭 IP다 - VPC 엔드포인트 5종(월 약 47달러)을 두지 않는 결정이고
#                 (티켓), 인바운드는 backend-sg가 alb-sg만 허용하므로 닫혀 있다.
#   역할 2개      실행 역할(ECS 에이전트 몫: ECR pull, awslogs, SSM 파라미터 읽기)과 태스크 역할(애플리케이션
#                 몫: RDS 마스터 시크릿, CloudWatch 지표). EC2 시절 인스턴스 역할 하나가 둘로 나뉜다.
#
# 이미지 태그의 정본은 SSM /accentury/{env}/IMAGE_TAG다 (파이프라인 KAN-128이 쓴다, Terraform 소유 아님).
# 여기서는 data 소스로 읽어 태스크 정의 image에 넣는다. 파이프라인(deploy.yml)은 서비스가 도는 리비전을 복제해
# image 태그만 바꾼 새 리비전을 등록하고 update-service 한다. track_latest = true라 Terraform은 자기가 만든
# 리비전이 아니라 패밀리의 최신 ACTIVE 리비전을 state로 읽으므로, 파이프라인 배포 뒤에도 "SSM 태그 = 배포된
# 태그 = 최신 리비전의 image"가 성립해 plan이 No changes다. 반대로 이 파일의 태스크 정의(cpu, secrets,
# stopTimeout 등)가 바뀌면 Terraform이 새 리비전을 만들고 서비스를 갱신해 굴린다. 두 주체가 같은 패밀리에
# 리비전을 쌓되 바꾸는 것이 다르다. 파이프라인이 실패한 리비전을 deregister하는 것이 이 정합의 조건이다 -
# 남겨 두면 최신 ACTIVE의 image가 SSM 태그와 어긋나 drift로 나온다.
# 첫 구축(prod)에서는 IMAGE_TAG가 먼저 있어야 plan이 선다 - ai 호스트가 같은 파라미터 없이는 기동하지 않는
# 것과 같은 전제다 (README "prod 최초 구축").
# 이 값은 plan 시점에 읽히므로 plan과 apply 사이에 파이프라인이 배포하면 apply가 옛 이미지로 되돌린다 -
# 인프라 apply와 이미지 배포를 겹치지 않게 하고 저장한 plan을 재사용하지 않는다 (README, 2차 리뷰 반영).

locals {
  name           = "accentury-${var.env}"
  family         = "${local.name}-backend"
  container_name = "backend"
  container_port = 8080
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  account_id           = data.aws_caller_identity.current.account_id
  region               = data.aws_region.current.region
  ecr_registry         = "${local.account_id}.dkr.ecr.${local.region}.amazonaws.com"
  parameter_arn_prefix = "arn:aws:ssm:${local.region}:${local.account_id}:parameter"
}

# 배포된 이미지 태그 (파이프라인 소유, KAN-128). String 타입이라 insecure_value로 읽어 plan에 그대로 보인다.
data "aws_ssm_parameter" "image_tag" {
  name = "${var.ssm_prefix}/IMAGE_TAG"
}

# ---- 로그 ----

# awslogs 드라이버가 스트림을 만드는 그룹. EC2 시절의 json-file 로테이션(서비스당 50MB)이 보존 기간으로 바뀐다.
resource "aws_cloudwatch_log_group" "backend" {
  name              = "/accentury/${var.env}/backend"
  retention_in_days = var.log_retention_days
}

# ---- 클러스터 ----

resource "aws_ecs_cluster" "this" {
  name = local.name

  # Container Insights는 켜지 않는다 - 지표 이름당 요금이고, 서비스 CPU와 메모리 평균은 기본 AWS/ECS 지표에
  # 있다 (monitoring 모듈이 그것을 본다). 태스크 단위 상세는 KAN-38에서 판단한다.
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

# FARGATE만 연결한다. FARGATE_SPOT을 붙이지 않는 것이 2026-09-01 결정이다. 다시 볼 조건은 스케일아웃 태스크가
# 상시 존재하게 될 때이고, 그때 capacity_providers에 FARGATE_SPOT을 더하고 서비스 전략에 weight를 나눈 뒤
# 새 배포를 강제하면 된다 (KAN-169의 stop-task 시나리오가 회수 모사가 된다).
resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

# ---- IAM: 실행 역할과 태스크 역할 ----

# 둘 다 ecs-tasks 서비스가 맡는다. SourceAccount와 SourceArn 조건은 혼동된 대리인 방지다 - 이 계정의 ECS
# 태스크만 이 역할을 맡을 수 있다 (AWS 문서 권장).
data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:aws:ecs:${local.region}:${local.account_id}:*"]
    }
  }
}

# 실행 역할: ECS 에이전트가 태스크를 띄울 때 쓴다 - ECR pull, awslogs, secrets 주입. 컨테이너 안 코드는 이
# 자격 증명을 볼 수 없다. 관리형 정책이 ECR과 로그를 덮고, SSM 파라미터 읽기만 이 환경 경로로 더한다.
resource "aws_iam_role" "execution" {
  name               = "${local.family}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# secrets의 valueFrom이 가리키는 파라미터만 허용한다. SecureString(관리자 토큰, 내부 호출 토큰)은 AWS 관리 키
# (aws/ssm)라 별도 kms:Decrypt가 필요 없다 (AWS 문서: 고객 관리형 키일 때만). ai 하위 경로(/ai/*)는 목록에
# 없으므로 backend 태스크에 내려가지 않는다 - EC2 시절 "--recursive 없음"과 같은 경계를 IAM으로 세운다.
data "aws_iam_policy_document" "execution" {
  statement {
    sid       = "ReadEnvParameters"
    actions   = ["ssm:GetParameters"]
    resources = [for name in var.config_parameter_names : "${local.parameter_arn_prefix}${name}"]
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "read-env-parameters"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

# 태스크 역할: 컨테이너 안 애플리케이션이 SDK 기본 체인(ECS 태스크 자격 증명 엔드포인트)으로 받는다. EC2 시절의
# IMDSv2 hop limit 조정이 필요 없다. 권한은 애플리케이션이 실제로 부르는 둘뿐이다.
#
# EC2 시절의 SSM Session Manager 같은 대화형 접속(ECS Exec)은 일부러 켜지 않았다 (2026-09-02 결정) - 평시
# 진단은 로그 그룹과 서비스 이벤트, 지표로 충분하고, 태스크 안에서 재현해야 하는 상황이 오면 서비스에
# enable_execute_command = true를 주고 이 역할에 ssmmessages 4종(CreateControlChannel, CreateDataChannel,
# OpenControlChannel, OpenDataChannel)을 더한 뒤 새 배포를 강제하면 된다.
resource "aws_iam_role" "task" {
  name               = "${local.family}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

data "aws_iam_policy_document" "task" {
  # RDS 마스터 자격 증명을 연결 시점에 읽는다 (KAN-129, awsSecretsManager 플러그인). 7일 회전 때문에 값을
  # 복사하지 않는다. RDS 관리형 시크릿은 AWS 관리 키라 kms 권한이 따로 필요 없다.
  statement {
    sid       = "ReadRdsMasterSecret"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.rds_master_user_secret_arn]
  }

  # Micrometer CloudWatch 레지스트리(회로 상태 게이지 등, KAN-36). PutMetricData는 리소스 수준 제한이 없는
  # API라 네임스페이스 조건으로 좁힌다 - 이 태스크가 다른 서비스 이름으로 지표를 위조하지 못한다.
  statement {
    sid       = "PutBackendMetrics"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = [var.metric_namespace]
    }
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "application"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

# ---- 태스크 정의 ----

locals {
  # SSM 파라미터 이름의 마지막 조각이 컨테이너 환경 변수 이름이다 (KAN-129 규약, EC2 시절 accentury-up.sh와 같다).
  secrets = [
    for name in var.config_parameter_names : {
      name      = basename(name)
      valueFrom = "${local.parameter_arn_prefix}${name}"
    }
  ]

  # compose healthcheck(KAN-131)와 같은 검사. JRE 이미지에 curl이 없어 bash의 /dev/tcp로 HTTP를 말하고 상태줄의
  # 200까지 본다 - "포트만 열리고 컨텍스트는 못 뜬" 기동 실패를 healthy로 오인하지 않는다. graceful shutdown 중
  # readiness가 내려가면(집계 health 503, KAN-166) 이 검사도 실패하지만 그때는 이미 종료 중이라 무해하다.
  health_command = "exec 3<>/dev/tcp/127.0.0.1/${local.container_port} && printf 'GET /actuator/health HTTP/1.1\\r\\nHost: 127.0.0.1\\r\\nConnection: close\\r\\n\\r\\n' >&3 && head -n1 <&3 | grep -q ' 200 '"
}

resource "aws_ecs_task_definition" "backend" {
  family                   = local.family
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  # 패밀리의 최신 ACTIVE 리비전을 state로 읽는다 (파일 머리). 파이프라인이 리비전을 올려도 drift가 아니다.
  track_latest = true

  # 이미지는 linux/amd64다 (scripts/push-images.sh 기본값, ai 호스트와 같다). 태스크 정의에 아키텍처를 못 박아야
  # ECS가 arm64 이미지를 x86 태스크에 올려 exec format error로 죽는 일이 plan이 아니라 배포에서만 드러나는 것을
  # 막지는 못하지만, 어느 아키텍처로 뜨는지는 여기서 분명해진다.
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = local.container_name
      image     = "${local.ecr_registry}/accentury/backend:${data.aws_ssm_parameter.image_tag.insecure_value}"
      essential = true

      portMappings = [
        {
          containerPort = local.container_port
          protocol      = "tcp"
        }
      ]

      # 지표 차원(env)이 쓴다 (EC2 시절 env.conf의 값). 비밀이 아니라 environment다.
      environment = [
        { name = "ACCENTURY_ENV", value = var.env }
      ]

      secrets = local.secrets

      # SIGTERM 뒤 SIGKILL까지 (KAN-166). backend는 웹 유예 15초 + 분석 워커 예산 90초 안에 스스로 나간다 -
      # Fargate 상한 120초가 그보다 조금 길다. compose stop_grace_period 110초의 자리다.
      stopTimeout = var.stop_timeout

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.backend.name
          "awslogs-region"        = local.region
          "awslogs-stream-prefix" = local.container_name
        }
      }

      healthCheck = {
        command     = ["CMD", "bash", "-c", local.health_command]
        interval    = 10
        timeout     = 3
        retries     = 6
        startPeriod = var.container_health_start_period
      }
    }
  ])

  lifecycle {
    # 파이프라인이 등록하는 리비전에는 태그가 없다 (describe -> register 복제에 태그가 안 실린다). track_latest로
    # 그 리비전을 읽으면 default_tags와 어긋나 매 plan이 태그 갱신을 내므로 무시한다. 비용 태그는 태스크에 붙는다
    # (서비스 propagate_tags) - 태스크 정의의 태그는 청구 대상이 아니다.
    ignore_changes = [tags, tags_all]

    precondition {
      condition     = contains(var.config_parameter_names, "${var.ssm_prefix}/SPRING_PROFILES_ACTIVE")
      error_message = "config 모듈의 SSM 파라미터 ${var.ssm_prefix}/SPRING_PROFILES_ACTIVE가 config_parameter_names에 있어야 합니다 (KAN-129)."
    }
  }
}

# ---- 서비스 ----

# 대상 그룹은 리스너에 붙은 뒤에야 서비스에 쓸 수 있다 (CreateService가 "target group does not have an associated
# load balancer"로 거부한다). 리스너는 edge 모듈 것이라 여기서는 ARN을 받아 의존성만 만든다 - 모듈 depends_on은
# 이 모듈의 data 소스(이미지 태그, 계정)까지 apply 시점으로 미뤄 태스크 정의가 매번 unknown이 된다.
resource "terraform_data" "listener" {
  input = var.alb_listener_arn
}

resource "aws_ecs_service" "backend" {
  name            = local.container_name
  cluster         = aws_ecs_cluster.this.arn
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = var.desired_count

  # launch_type 대신 전략으로 - Spot을 붙일 때 이 블록만 바꾼다 (파일 머리).
  capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }

  # 롤링 배포: 새 태스크가 healthy가 된 뒤 옛 태스크를 뺀다 (desired 1이면 잠시 2개). 회로 차단기는 실패 임계
  # (desired 1이면 최소값 3)에 닿으면 배포를 FAILED로 접고 직전 COMPLETED 배포로 되돌린다 - 파이프라인은
  # rolloutState로 이것을 본다 (deploy.yml).
  #
  # 배포 중 두 태스크가 겹치는 몇 분은 인메모리 상태(요청 제한, 회로, 혼잡 판정)가 갈라지고 디스패처도 둘이다 -
  # "태스크 1개 고정" 전제의 의도된 예외다. KAN-166이 이 겹침(롤링 배포)을 전제로 종료 순서를 만들었고, 상시
  # 다중 태스크의 방침은 KAN-167이, 오토스케일링은 KAN-168이 정한다. min 0 / max 100(끊고 새로 띄우기)으로
  # 겹침을 없애는 대안은 배포마다 수 분의 전면 중단을 만들어 기각했다 (2026-09-02 리뷰에서 재확인).
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # 기동 중 ALB와 컨테이너 헬스체크 실패를 무시하는 시간. Fargate 0.5 vCPU는 버스트가 없어 JVM 기동이 t3.small보다
  # 느리다 - 값은 staging 실측으로 정한다 (variables.tf).
  health_check_grace_period_seconds = var.health_check_grace_period_seconds

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = local.container_name
    container_port   = local.container_port
  }

  # 프로바이더 default_tags(env, project)는 서비스에만 붙고 태스크에는 전파되지 않는다 - 비용 태그(KAN-35)를
  # 태스크까지 내리려면 이 둘이 필요하다.
  enable_ecs_managed_tags = true
  propagate_tags          = "SERVICE"

  # apply가 서비스 안정(새 태스크 RUNNING + healthy)까지 기다린다 - 첫 구축과 태스크 정의 변경에서 "떴는지"를
  # apply 결과로 안다. 상한은 timeouts 기본 20분이다.
  wait_for_steady_state = true

  depends_on = [
    terraform_data.listener,
    aws_ecs_cluster_capacity_providers.this,
    aws_iam_role_policy.execution,
    aws_iam_role_policy_attachment.execution_managed,
    aws_iam_role_policy.task,
  ]
}
