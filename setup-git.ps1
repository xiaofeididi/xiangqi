# Powershell — run once in this project folder to create a clean repo.
# Usage:  .\setup-git.ps1   (then push to your GitHub remote)
$ErrorActionPreference = "Stop"
$proj = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $proj

# safety: abort if this path is already a standalone repo root
if (Test-Path (Join-Path $proj ".git")) {
    Write-Host "This folder already has .git — aborting." -ForegroundColor Yellow
    Pop-Location; exit 1
}

git init
git add -A
git commit -m "xq-assist: android apk + python prototype"
git branch -M main

Write-Host ""
Write-Host "Clean repo ready. To push to GitHub:"
Write-Host "  gh repo create xq-assist --private --source=."
Write-Host "  git remote add origin <your-repo-url>"
Write-Host "  git push -u origin main"
Pop-Location