# tflint configuration (advisory in CI). Uses only the bundled terraform ruleset so no plugin download
# is needed; add the aws ruleset (terraform-linters/tflint-ruleset-aws) once a version is pinned.
config {
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "recommended"
}
