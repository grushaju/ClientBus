$ErrorActionPreference = "Stop"

# ------------------------------------------------------------
# ClientBus project structure generator
# Windows / PowerShell
#
# Generates index.md containing only:
#   - Maven modules
#   - src/main/java
#   - src/main/resources
#   - src/test/java
#   - src/test/resources
#   - Java packages and classes
#
# Excludes IDE/build/git/generated garbage.
# ------------------------------------------------------------

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$OutputFile = Join-Path $Root "index.md"

Set-Location $Root

# ------------------------------------------------------------
# Git information
# ------------------------------------------------------------

try {
    $CommitSha = (git rev-parse --short HEAD 2>$null).Trim()
}
catch {
    $CommitSha = "unknown"
}

try {
    $Branch = (git branch --show-current 2>$null).Trim()

    if ([string]::IsNullOrWhiteSpace($Branch)) {
        $Branch = "detached"
    }
}
catch {
    $Branch = "unknown"
}

$GeneratedAt = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

# ------------------------------------------------------------
# Helpers
# ------------------------------------------------------------

function Get-RelativePath {
    param (
        [string]$Path
    )

    return $Path.Substring($Root.Path.Length + 1).Replace("\", "/")
}

function Get-JavaPackage {
    param (
        [string]$File
    )

    $package = Select-String `
        -Path $File `
        -Pattern '^\s*package\s+([^;]+);' `
        | Select-Object -First 1

    if ($null -eq $package) {
        return "<default>"
    }

    return $package.Matches.Groups[1].Value.Trim()
}

function Get-JavaClassName {
    param (
        [string]$File
    )

    return [System.IO.Path]::GetFileNameWithoutExtension($File)
}

# ------------------------------------------------------------
# Discover Maven modules
# ------------------------------------------------------------

$Modules = @()

# Root Maven module
$RootPom = Join-Path $Root "pom.xml"

if (Test-Path $RootPom) {
    $Modules += [PSCustomObject]@{
        Name = "root"
        Path = $Root
    }
}

# First-level Maven modules
Get-ChildItem -Path $Root -Directory |
    Where-Object {
        $_.Name -notin @(
            ".git",
            ".idea",
            "target",
            "build",
            "node_modules"
        )
    } |
    ForEach-Object {

        $pom = Join-Path $_.FullName "pom.xml"

        if (Test-Path $pom) {

            $Modules += [PSCustomObject]@{
                Name = $_.Name
                Path = $_.FullName
            }
        }
    }

# ------------------------------------------------------------
# Generate document
# ------------------------------------------------------------

$Lines = New-Object System.Collections.Generic.List[string]

$Lines.Add("# ClientBus Project Structure")
$Lines.Add("")
$Lines.Add("> Generated automatically. Do not edit manually.")
$Lines.Add("")
$Lines.Add("- Generated: $GeneratedAt")
$Lines.Add("- Branch: ``$Branch``")
$Lines.Add("- Commit: ``$CommitSha``")
$Lines.Add("")

# ------------------------------------------------------------
# Project structure
# ------------------------------------------------------------

$Lines.Add("## Project Structure")
$Lines.Add("")

foreach ($Module in $Modules) {

    $Lines.Add("### $($Module.Name)")
    $Lines.Add("")

    $SourceRoots = @(
        "src/main/java",
        "src/main/resources",
        "src/test/java",
        "src/test/resources"
    )

    foreach ($SourceRoot in $SourceRoots) {

        $FullSourceRoot = Join-Path $Module.Path $SourceRoot

        if (-not (Test-Path $FullSourceRoot)) {
            continue
        }

        $Lines.Add("#### ``$SourceRoot``")
        $Lines.Add("")

        # ----------------------------------------------------
        # Java source tree
        # ----------------------------------------------------

        if ($SourceRoot -like "*java") {

            $JavaFiles = Get-ChildItem `
                -Path $FullSourceRoot `
                -Recurse `
                -File `
                -Filter "*.java"

            $Packages = @{}

            foreach ($JavaFile in $JavaFiles) {

                $Package = Get-JavaPackage $JavaFile.FullName

                if (-not $Packages.ContainsKey($Package)) {
                    $Packages[$Package] = @()
                }

                $Packages[$Package] += $JavaFile
            }

            foreach ($Package in ($Packages.Keys | Sort-Object)) {

                $Lines.Add("##### ``$Package``")
                $Lines.Add("")

                foreach ($JavaFile in ($Packages[$Package] | Sort-Object Name)) {

                    $ClassName = Get-JavaClassName $JavaFile.FullName
                    $Relative = Get-RelativePath $JavaFile.FullName

                    $Lines.Add("- ``$ClassName.java`` — ``$Relative``")
                }

                $Lines.Add("")
            }
        }

        # ----------------------------------------------------
        # Resources
        # ----------------------------------------------------

        else {

            $ResourceFiles = Get-ChildItem `
                -Path $FullSourceRoot `
                -Recurse `
                -File

            foreach ($ResourceFile in ($ResourceFiles | Sort-Object FullName)) {

                $Relative = Get-RelativePath $ResourceFile.FullName

                $Lines.Add("- ``$Relative``")
            }

            $Lines.Add("")
        }
    }
}

# ------------------------------------------------------------
# Summary
# ------------------------------------------------------------

$JavaFilesCount = 0

foreach ($Module in $Modules) {

    $JavaRoot = Join-Path $Module.Path "src"

    if (Test-Path $JavaRoot) {

        $JavaFilesCount += (
            Get-ChildItem `
                -Path $JavaRoot `
                -Recurse `
                -File `
                -Filter "*.java" `
                | Measure-Object
        ).Count
    }
}

$Lines.Add("## Summary")
$Lines.Add("")
$Lines.Add("- Maven modules: $($Modules.Count)")
$Lines.Add("- Java files: $JavaFilesCount")
$Lines.Add("- Git commit: ``$CommitSha``")
$Lines.Add("")

# ------------------------------------------------------------
# Write file
# ------------------------------------------------------------

$Lines | Set-Content `
    -Path $OutputFile `
    -Encoding UTF8

Write-Host ""
Write-Host "index.md generated successfully:"
Write-Host $OutputFile
Write-Host ""
