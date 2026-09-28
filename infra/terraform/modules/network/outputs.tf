output "vpc_id" {
  description = "VPC ID."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "VPC CIDR."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Public subnet IDs."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "Private subnet IDs (EKS)."
  value       = aws_subnet.private[*].id
}

output "private_subnet_cidrs" {
  description = "Private subnet CIDRs."
  value       = aws_subnet.private[*].cidr_block
}

output "database_subnet_ids" {
  description = "Database subnet IDs."
  value       = aws_subnet.database[*].id
}

output "database_subnet_cidrs" {
  description = "Database subnet CIDRs (use in the Helm chart networkPolicy.database.cidrs)."
  value       = aws_subnet.database[*].cidr_block
}

output "database_subnet_group_name" {
  description = "DB subnet group for Aurora."
  value       = aws_db_subnet_group.this.name
}

output "nat_public_ips" {
  description = "NAT gateway public IPs (share with partners that allow-list source IPs, e.g. bureaus, NPCI aggregators)."
  value       = aws_eip.nat[*].public_ip
}
