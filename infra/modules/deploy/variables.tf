variable "env" {
  type        = string
  description = "환경 이름 (staging | prod). GitHub environment 이름과 같아야 한다 - 신뢰 정책의 sub 조건이 이 값에 묶인다."
}

variable "github_repository" {
  type        = string
  description = "배포 워크플로가 도는 GitHub 저장소 (OWNER/REPO)"
}

variable "github_owner_id" {
  type        = number
  description = "저장소 소유자(조직)의 숫자 ID. 불변 subject claim(repo:OWNER@ID/REPO@ID:...)에 들어간다."
}

variable "github_repository_id" {
  type        = number
  description = "저장소의 숫자 ID. 불변 subject claim에 들어간다."
}

variable "web_bucket_arn" {
  type        = string
  description = "웹 번들 버킷 ARN (edge 모듈 출력). 업로드 권한을 이 버킷 하나로 한정한다."
}

variable "ssm_prefix" {
  type        = string
  description = "이 환경의 SSM 경로 접두사 (/accentury/{env}). IMAGE_TAG 쓰기와 관리자 토큰 읽기를 이 경로로 한정한다 (KAN-128)."
}

variable "ci_image_push" {
  type        = bool
  description = "ECR push 권한 (KAN-128 승격 모델). staging만 true - prod로 가는 이미지는 언제나 staging을 거친 것이어야 한다."
}

variable "cloudfront_distribution_arn" {
  type        = string
  description = "이 환경의 CloudFront 배포 ARN (edge 모듈 출력). 무효화 권한을 이 배포 하나로 한정한다."
}
