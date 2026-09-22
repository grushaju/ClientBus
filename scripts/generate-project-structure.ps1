$ErrorActionPreference = "Stop"

# ------------------------------------------------------------
# ClientBus project structure generator
# Windows / PowerShell
#
# Generates index.md containing:
#   - Maven modules
#   - src/main/java
#   - src/main/resources
#   - src/test/java
#   - src/test/resources
#   - Java packages and classes
#   - UI source tree (ui/src)
#   - UI public/static files (ui/public)
#   - UI configuration files
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
# Discover UI
# ------------------------------------------------------------

$UiPath = Join-Path $Root "ui"
$HasUi = Test-Path (Join-Path $UiPath "package.json")

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
# UI source tree
# ------------------------------------------------------------

if ($HasUi) {

    $Lines.Add("### ui")
    $Lines.Add("")

    # --------------------------------------------------------
    # ui/src
    # --------------------------------------------------------

    $UiSourceRoot = Join-Path $UiPath "src"

    if (Test-Path $UiSourceRoot) {

        $Lines.Add("#### ``src``")
        $Lines.Add("")

        $UiFiles = Get-ChildItem `
            -Path $UiSourceRoot `
            -Recurse `
            -File |
            Where-Object {
                $_.Extension -in @(
                    ".ts",
                    ".tsx",
                    ".js",
                    ".jsx",
                    ".css",
                    ".scss"
                )
            }

        foreach ($UiFile in ($UiFiles | Sort-Object FullName)) {

            $Relative = Get-RelativePath $UiFile.FullName

            $Lines.Add("- ``$Relative``")
        }

        $Lines.Add("")
    }

    # --------------------------------------------------------
    # ui/public
    # --------------------------------------------------------

    $UiPublicRoot = Join-Path $UiPath "public"

    if (Test-Path $UiPublicRoot) {

        $Lines.Add("#### ``public``")
        $Lines.Add("")

        $UiPublicFiles = Get-ChildItem `
            -Path $UiPublicRoot `
            -Recurse `
            -File

        foreach ($UiPublicFile in ($UiPublicFiles | Sort-Object FullName)) {

            $Relative = Get-RelativePath $UiPublicFile.FullName

            $Lines.Add("- ``$Relative``")
        }

        $Lines.Add("")
    }

    # --------------------------------------------------------
    # UI configuration
    # --------------------------------------------------------

    $Lines.Add("#### UI configuration")
    $Lines.Add("")

    $UiConfigFiles = @(
        "package.json",
        "index.html",
        "tsconfig.json",
        "tsconfig.app.json",
        "tsconfig.node.json",
        "vite.config.ts",
        "vite.config.js"
    )

    foreach ($ConfigFile in $UiConfigFiles) {

        $FullConfigFile = Join-Path $UiPath $ConfigFile

        if (Test-Path $FullConfigFile) {

            $Relative = Get-RelativePath $FullConfigFile

            $Lines.Add("- ``$Relative``")
        }
    }

    $Lines.Add("")
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
                -Filter "*.java" |
                Measure-Object
        ).Count
    }
}

$UiFilesCount = 0

if ($HasUi) {

    $UiSourceRoot = Join-Path $UiPath "src"

    if (Test-Path $UiSourceRoot) {

        $UiFilesCount = (
            Get-ChildItem `
                -Path $UiSourceRoot `
                -Recurse `
                -File |
                Where-Object {
                    $_.Extension -in @(
                        ".ts",
                        ".tsx",
                        ".js",
                        ".jsx",
                        ".css",
                        ".scss"
                    )
                } |
                Measure-Object
        ).Count
    }
}

$Lines.Add("## Summary")
$Lines.Add("")
$Lines.Add("- Maven modules: $($Modules.Count)")
$Lines.Add("- Java files: $JavaFilesCount")
$Lines.Add("- UI source files: $UiFilesCount")
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