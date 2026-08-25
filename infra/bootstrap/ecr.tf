# ECR 리포지토리 2개 (KAN-124). KAN-120이 콘솔에서 IMMUTABLE로 만든 것을 코드로 이관한다.
#
# 환경 스택(envs/*)이 아니라 여기 있는 이유: staging과 prod가 같은 리포지토리의 같은 SHA
# 태그를 승격 모델로 나눠 쓴다 (KAN-128). 환경별 state에 넣으면 소유자가 둘이 된다.
#
# 이미 존재하는 리포지토리는 아래 import 블록이 첫 plan/apply에서 state로 흡수한다. 흡수된
# 뒤에는 블록이 무시되므로(멱등) 지우지 않아도 된다. scan_on_push와 라이프사이클 정책은
# 콘솔 생성본에 없던 설정이라 첫 apply가 in-place 갱신으로 나온다 - 정상이다.

locals {
  ecr_repositories = toset(["accentury/backend", "accentury/ai"])
}

import {
  for_each = local.ecr_repositories
  to       = aws_ecr_repository.this[each.key]
  id       = each.key
}

resource "aws_ecr_repository" "this" {
  for_each = local.ecr_repositories

  name = each.key
  # 같은 태그 덮어쓰기를 AWS가 거부한다. 태그가 commit SHA 하나뿐이라(scripts/push-images.sh)
  # "떠 있는 컨테이너 = 그 커밋"이 성립하는 근거다 (KAN-120).
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  # 리포지토리가 사라지면 두 환경의 롤백 대상이 함께 사라진다.
  lifecycle {
    prevent_destroy = true
  }
}

# 오래된 태그 정리 (KAN-120 후속). 롤백은 "이전 SHA로 같은 절차 재실행"이라(KAN-128) 최근
# 이미지가 남아 있어야 한다. 50개면 Dev 병합 50번 분량이고, prod가 그보다 오래된 태그를
# 돌리고 있으면 그 태그는 만료돼 재기동 시 pull이 실패한다 - Release 주기가 그만큼 벌어지면
# 값을 올린다. 태그 없는 이미지는 재푸시 잔여물이라 하루 뒤 지운다.
resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "태그 없는 이미지는 1일 후 정리"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "최근 50개만 유지 (롤백 범위)"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 50
        }
        action = { type = "expire" }
      },
    ]
  })
}

output "ecr_repository_urls" {
  value       = { for k, r in aws_ecr_repository.this : k => r.repository_url }
  description = "scripts/push-images.sh와 EC2 compose가 가리키는 이미지 주소"
}
