<#
Checks a pull request on this machine: GitHub Actions' CI runs only after a merge to main, to keep Actions usage low,
so PRs are checked here. Runs the jobs of .github/workflows/ci.yml at a PR's head (or any ref) and posts each result
as a commit status (context "local-ci/<job>"), which the main branch's ruleset requires. Windows, PowerShell 7.

  .\tools\ci-local.ps1 -Pr 22            # check PR #22's head, post statuses
  .\tools\ci-local.ps1 -Ref main -NoPost # check a ref, print only

Needs: gh signed in with push access (or GH_TOKEN), the tools venv (tools\venv), JDK 17 (JAVA_HOME) and the Android
SDK (ANDROID_HOME, default %LOCALAPPDATA%\Android\Sdk).

Jobs:
  python    python -m unittest discover -s tests; passes when it adds no failure over its base (this machine has
            ~148 environment failures on main too, so "no new failures" stands in for CI's green).
  tables    tools/gen_tables.py --check (CI runs it on main only; here on every run, as a warning for PRs).
  launcher  gradlew testReleaseUnitTest lintRelease assembleRelease, 0 lint errors, release APK <= 1,048,576 B.
Builds without launcher/tv.properties, as CI does (no keys: the mock catalogue).
#>
param([int]$Pr, [string]$Ref, [switch]$NoPost)
$ErrorActionPreference = 'Stop'
# The main checkout, which holds the venv, even when this script runs from a linked worktree.
$main = Split-Path (git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir)
$py = "$main\tools\venv\Scripts\python.exe"
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17.' }
$repo = gh repo view (git -C $main remote get-url origin) --json nameWithOwner -q .nameWithOwner

# One run at a time on this machine: runs share the temporary worktrees and the cached base results.
$lock = New-Object System.Threading.Mutex($false, 'Global\cleanyourtv-ci-local')
try { if (-not $lock.WaitOne(0)) { Write-Host 'another ci-local run is in progress; waiting...'; [void]$lock.WaitOne() } }
catch [System.Threading.AbandonedMutexException] { }
git -C $main fetch -q origin
if ($Pr) {
    $info = gh pr view $Pr --repo $repo --json headRefOid,baseRefName | ConvertFrom-Json
    $sha = $info.headRefOid
    $base = git -C $main merge-base $sha "origin/$($info.baseRefName)"
} else {
    $sha = git -C $main rev-parse $Ref
    $base = git -C $main rev-parse "$sha~1"
}
$short = $sha.Substring(0, 8)
Write-Host "checking $short (base $($base.Substring(0,8)))"

function Post($job, $state, $desc) {
    Write-Host "  [$job] $state - $desc"
    if ($NoPost) { return }
    gh api "repos/$repo/statuses/$sha" -f state=$state -f context="local-ci/$job" -f description=$desc | Out-Null
}

function Tree($rev) {
    $dir = Join-Path $env:TEMP "cytv-ci-$($rev.Substring(0,8))"
    if (-not (Test-Path $dir)) {
        git -C $main worktree add -q --detach $dir $rev
        New-Item -ItemType Junction -Path "$dir\tools\venv" -Target "$main\tools\venv" | Out-Null
    }
    return $dir
}
function Drop($dir) {
    if (Test-Path "$dir\tools\venv") { (Get-Item "$dir\tools\venv").Delete() }
    git -C $main worktree remove --force $dir 2>$null
}

# The suite's failing tests at a commit, cached per commit (a shared base such as main runs once).
function PyFailures($rev) {
    $cache = Join-Path $env:TEMP "cytv-ci-py-$($rev.Substring(0,8)).txt"
    if (Test-Path $cache) { return Get-Content $cache }
    $d = Tree $rev
    Push-Location $d
    $out = & $py -m unittest discover -s tests 2>&1
    Pop-Location
    $fails = $out | Select-String '^(FAIL|ERROR): ' | ForEach-Object { $_.Line } | Sort-Object -Unique
    $fails | Set-Content $cache
    if ($rev -ne $sha) { Drop $d }
    return $fails
}

foreach ($j in 'python', 'tables', 'launcher') { Post $j pending 'running on a maintainer''s machine' }
$dir = Tree $sha
try {
    # python
    $now = @(PyFailures $sha); $was = @(PyFailures $base)
    $new = @($now | Where-Object { $was -notcontains $_ })
    if ($new.Count -eq 0) { Post python success "no new failures ($($now.Count) environment failures, as base)" }
    else {
        $d = "$($new.Count) new: $(($new | Select-Object -First 2) -join '; ')"
        Post python failure $d.Substring(0, [Math]::Min(139, $d.Length)); $new
    }

    # tables
    Push-Location $dir; & $py tools\gen_tables.py --check *> $null; $t = $LASTEXITCODE; Pop-Location
    if ($t -eq 0) { Post tables success 'README tables match examples/*/tv.json' }
    else { Post tables ($(if ($Pr) { 'success' } else { 'failure' })) 'tables want regenerating (main only, as CI)' }

    # launcher
    Push-Location "$dir\launcher"
    if (Test-Path tv.properties) { Remove-Item tv.properties }
    .\gradlew.bat --no-daemon testReleaseUnitTest lintRelease assembleRelease *> "$dir\gradle.log"
    $ok = $LASTEXITCODE -eq 0
    $tests = 0; $tf = 0
    Get-ChildItem app\build\test-results\testReleaseUnitTest\*.xml -ErrorAction SilentlyContinue | ForEach-Object {
        [xml]$x = Get-Content $_; $tests += [int]$x.testsuite.tests; $tf += [int]$x.testsuite.failures + [int]$x.testsuite.errors }
    $lint = 0
    if (Test-Path app\build\reports\lint-results-release.xml) {
        $lint = @(([xml](Get-Content app\build\reports\lint-results-release.xml)).issues.issue | Where-Object severity -eq 'Error').Count }
    $apk = 'app\build\outputs\apk\release\app-release.apk'
    $size = if (Test-Path $apk) { (Get-Item $apk).Length } else { 0 }
    Pop-Location
    $desc = "$tests tests, $tf failed, $lint lint errors, APK $size B"
    if ($ok -and $tf -eq 0 -and $lint -eq 0 -and $size -gt 0 -and $size -le 1048576) { Post launcher success $desc }
    else { Post launcher failure "$desc$(if (-not $ok) { ' (gradle failed)' })"; Get-Content "$dir\gradle.log" -Tail 25 }
}
finally {
    Drop $dir
    $lock.ReleaseMutex()
}
