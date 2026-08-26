variable "env" {
  type        = string
  description = "환경 이름 (staging | prod). GitHub environment 이름과 같아야 한다 - 신뢰 정책의 sub 조건이 이 값에 묶인다."
}

variable "github_repository" {
  type        = string
  description = "배포 워크플로가 도는 GitHub 저장소 (OWNER/REPO)"
}

variable "web_bucket_arn" {
  type        = string
  description = "웹 번들 버킷 ARN (edge 모듈 출력). 업로드 권한을 이 버킷 하나로 한정한다."
}

variable "cloudfront_distribution_arn" {
  type        = string
  description = "이 환경의 CloudFront 배포 ARN (edge 모듈 출력). 무효화 권한을 이 배포 하나로 한정한다."
}
