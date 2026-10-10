<#
.SYNOPSIS
    Cuts a release: validates the VERSION file, tags that commit, and pushes the tag.

.DESCRIPTION
    Every Messenger client reports one version scheme — the semantic version name
    in the repository-root VERSION file plus the git commit count as the version
    code (see the "Versioning" section of AGENTS.md). A release is therefore
    always "bump VERSION, commit it, tag that commit vX.Y.Z".

    .github/workflows/release.yml re-checks the tag against VERSION at the tagged
    commit and REFUSES to publish a mismatch. This script performs the same checks
    locally, before anything is pushed, and adds the ones CI cannot see:

      * VERSION exists and is a semantic version (MAJOR.MINOR.PATCH);
      * VERSION is COMMITTED and identical to the working copy — a tag points at
        a commit, so an uncommitted bump would tag the old version and fail CI;
      * the working tree is clean (override with -AllowDirty);
      * the tag does not already exist locally or on origin.

    Releasing is a one-way, outward-facing action: the tag push is what triggers
    the release build and publishes artifacts. Use -NoPush to stop after creating
    the tag locally, or -WhatIf to see what would happen without doing it.

.PARAMETER Bump
    Increment VERSION and commit the bump first, instead of tagging the version
    that is already committed. Resets the lower components (major resets minor
    and patch, minor resets patch).

.PARAMETER NoPush
    Create the tag locally but do not push it. Prints the push command instead.

.PARAMETER AllowDirty
    Permit a dirty working tree. Only the VERSION file's committed state is
    still enforced; use this when unrelated local edits are intentional.

.EXAMPLE
    ./release.ps1
    Tags and pushes v1.0.1 for a committed VERSION of 1.0.1.

.EXAMPLE
    ./release.ps1 -Bump patch
    Bumps VERSION 1.0.0 -> 1.0.1, commits it, tags v1.0.1, and pushes the tag.

.EXAMPLE
    ./release.ps1 -NoPush
    Everything except the push — inspect the tag before publishing it.
#>
#Requires -Version 5.1

[CmdletBinding(SupportsShouldProcess)]
param(
    [ValidateSet('major', 'minor', 'patch')]
    [string]$Bump,

    [switch]$NoPush,

    [switch]$AllowDirty
)

$ErrorActionPreference = 'Stop'

function Fail {
    param([string]$Message)
    Write-Host "release: error: $Message" -ForegroundColor Red
    exit 1
}

function Invoke-Git {
    <# Runs git, returning trimmed stdout; throws on a non-zero exit so a
       failure can never be mistaken for an empty result.

       Deliberately uses the automatic `$args` rather than a declared
       `[string[]]$Args` parameter: PowerShell treats `-a` as an unambiguous
       abbreviation of a parameter literally named `Args`, so `git tag -a ...`
       would bind `-a` to it and fail before git ever runs. #>
    $output = & git @args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git $($args -join ' ') failed (exit $LASTEXITCODE): $output"
    }
    return ($output | Out-String).Trim()
}

# Work from the script's own directory so the release is cut from this checkout
# regardless of where the shell happens to be.
Push-Location -LiteralPath $PSScriptRoot
try {
    $versionFile = Join-Path $PSScriptRoot 'VERSION'

    # ---- VERSION ---------------------------------------------------------
    if (-not (Test-Path -LiteralPath $versionFile)) {
        Fail "no VERSION file at $versionFile. It is the single source of truth for the version name."
    }
    $version = (Get-Content -LiteralPath $versionFile -Raw).Trim()
    if ($version -notmatch '^\d+\.\d+\.\d+$') {
        Fail "VERSION must be a semantic version MAJOR.MINOR.PATCH, found '$version'."
    }

    # ---- optional bump ---------------------------------------------------
    if ($Bump) {
        $parts = $version.Split('.')
        $major = [int]$parts[0]
        $minor = [int]$parts[1]
        $patch = [int]$parts[2]
        switch ($Bump) {
            'major' { $major++; $minor = 0; $patch = 0 }
            'minor' { $minor++; $patch = 0 }
            'patch' { $patch++ }
        }
        $version = "$major.$minor.$patch"

        if ($PSCmdlet.ShouldProcess("VERSION -> $version", 'bump and commit')) {
            # Explicit LF so the file keeps the line ending every other client's
            # reader (Gradle, build.rs) already sees.
            [System.IO.File]::WriteAllText($versionFile, "$version`n")
            Invoke-Git add VERSION | Out-Null
            Invoke-Git commit -m "chore(release): bump VERSION to $version" | Out-Null
            Write-Host "release: bumped VERSION to $version and committed it."
        }
    }

    # ---- the tag must describe the COMMITTED file ------------------------
    # The tag records a commit, and release.yml compares the tag against VERSION
    # as it exists in that commit. An uncommitted (or committed-then-edited) bump
    # would therefore tag a tree that still carries the previous version.
    $committedVersion = try { Invoke-Git show 'HEAD:VERSION' } catch { $null }
    if (-not $committedVersion) {
        Fail "VERSION is not committed yet. Commit it first — a tag points at a commit, and release.yml reads VERSION from that commit."
    }
    $committedVersion = $committedVersion.Trim()
    if ($committedVersion -ne $version) {
        Fail "VERSION is '$version' in the working tree but '$committedVersion' in HEAD. Commit the bump (or re-run with -Bump) so the tag describes the committed file."
    }

    # ---- clean tree ------------------------------------------------------
    # --ignore-submodules=dirty: unrelated build artifacts inside a submodule
    # (e.g. server/tsconfig.tsbuildinfo) must not block a superproject release.
    # A submodule whose COMMIT differs from the recorded one still shows up.
    if (-not $AllowDirty) {
        $dirty = Invoke-Git status --porcelain --ignore-submodules=dirty
        if ($dirty) {
            Fail "working tree has uncommitted changes - the tag would not include them:`n$dirty`nCommit or stash them, or pass -AllowDirty if they are unrelated."
        }
    }

    $tag = "v$version"
    $commitCount = Invoke-Git rev-list --count HEAD

    # ---- the tag must be free -------------------------------------------
    # Probe with plain git calls rather than Invoke-Git: "does not exist" is an
    # expected answer here, and Invoke-Git throws on any non-zero exit.
    & git rev-parse -q --verify "refs/tags/$tag" *> $null
    if ($LASTEXITCODE -eq 0) {
        Fail "tag $tag already exists locally. Delete it (git tag -d $tag) or bump VERSION."
    }
    $remoteTag = & git ls-remote --tags origin "refs/tags/$tag" 2>$null
    if ($LASTEXITCODE -eq 0 -and $remoteTag) {
        Fail "tag $tag already exists on origin. Bump VERSION and tag the new commit."
    }
    # A failed ls-remote (offline) is not fatal: the push below is non-forcing,
    # so git itself rejects a tag that turns out to exist on the remote.

    Write-Host "release: Messenger $version (build $commitCount) -> $tag"

    # ---- tag + push ------------------------------------------------------
    if ($PSCmdlet.ShouldProcess($tag, 'create tag')) {
        Invoke-Git tag -a $tag -m $tag | Out-Null
        Write-Host "release: created annotated tag $tag."
    }

    if ($NoPush) {
        Write-Host "release: not pushed (-NoPush). Publish it with:" -ForegroundColor Yellow
        Write-Host "    git push origin $tag"
    }
    elseif ($PSCmdlet.ShouldProcess("origin $tag", 'push tag')) {
        Invoke-Git push origin $tag | Out-Null
        Write-Host "release: pushed $tag - release.yml is now building the artifacts." -ForegroundColor Green
    }
}
finally {
    Pop-Location
}
