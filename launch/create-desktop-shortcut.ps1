# Creates an "OSRS MCP RuneLite" shortcut on your desktop.
# Run: powershell -ExecutionPolicy Bypass -File launch\create-desktop-shortcut.ps1
$ErrorActionPreference = 'Stop'

$launchDir = $PSScriptRoot
$repoRoot  = Split-Path -Parent $launchDir
$bat  = Join-Path $launchDir 'OSRS-MCP-RuneLite.bat'
$icon = Join-Path $launchDir 'osrs-mcp.ico'
$desktop  = [Environment]::GetFolderPath('Desktop')
$lnkPath  = Join-Path $desktop 'OSRS MCP RuneLite.lnk'

$shell = New-Object -ComObject WScript.Shell
$lnk = $shell.CreateShortcut($lnkPath)
$lnk.TargetPath       = $bat
$lnk.WorkingDirectory = $repoRoot
$lnk.IconLocation     = $icon
$lnk.WindowStyle      = 7
$lnk.Description      = 'RuneLite with the OSRS MCP plugin'
$lnk.Save()

Write-Host "Created shortcut: $lnkPath"
Write-Host "  Target:     $($lnk.TargetPath)"
Write-Host "  Start in:   $($lnk.WorkingDirectory)"
Write-Host "  Icon:       $($lnk.IconLocation)"
