output "github_deploy_role_arn" {
  value       = aws_iam_role.github_deploy.arn
  description = "GitHub environment 변수 AWS_DEPLOY_ROLE_ARN 값 (KAN-127)"
}
