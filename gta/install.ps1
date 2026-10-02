# Put the built plugin and the effect into GTA V Legacy (Windows PowerShell; the rest of the install - ScriptHookV,
# its ASI loader, ReShade - is install.sh's job and is assumed done). Only replaces MCPassthrough.asi and
# reshade-shaders\Shaders\MCPassthrough.fx, keeping a copy of what was there in backup-<date>\ the first time.
#   .\install.ps1 -Gta <folder containing GTA5.exe> [-Restore <backup folder>]
# If -Gta is omitted, use the GTA_DIR environment variable.
param(
	[string]$Gta = $env:GTA_DIR,
	[string]$Restore
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
if (-not $Gta) { throw 'Specify -Gta with the folder containing GTA5.exe, or set GTA_DIR.' }
if (-not (Test-Path (Join-Path $Gta 'GTA5.exe'))) { throw "GTA5.exe not found in: $Gta" }
if (Get-Process GTA5 -ErrorAction SilentlyContinue) { throw 'GTA V is running: close it first' }
$asi = Join-Path $Gta 'MCPassthrough.asi'
$fx = Join-Path $Gta 'reshade-shaders\Shaders\MCPassthrough.fx'

if ($Restore) {
	Copy-Item (Join-Path $Restore 'MCPassthrough.asi') $asi -Force
	Copy-Item (Join-Path $Restore 'MCPassthrough.fx') $fx -Force
	"restored from $Restore"
	return
}

$built = Join-Path $here 'build\MCPassthrough.asi'
if (-not (Test-Path $built)) { throw 'build it first: .\build.bat' }
$backup = Join-Path $here ("backup-" + (Get-Date -Format 'yyyy-MM-dd'))
if (-not (Test-Path $backup) -and (Test-Path $asi)) {
	New-Item -ItemType Directory $backup | Out-Null
	Copy-Item $asi $backup
	if (Test-Path $fx) { Copy-Item $fx $backup }
	"kept the previous files in $backup"
}
Copy-Item $built $asi -Force
New-Item -ItemType Directory -Force (Split-Path $fx) | Out-Null
Copy-Item (Join-Path $here 'shaders\MCPassthrough.fx') $fx -Force
"installed into $Gta"
