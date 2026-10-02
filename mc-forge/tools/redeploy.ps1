# Build and install the bridge into a closed Prism instance, optionally launching it.
#   .\tools\redeploy.ps1 -Instance <instance folder name> [-PrismRoot <folder>] [-NoLaunch]
param(
	[string]$Instance = '1.20.1',
	[string]$PrismRoot = (Join-Path $env:APPDATA 'PrismLauncher'),
	[string]$PrismExe = (Join-Path $env:LOCALAPPDATA 'Programs\PrismLauncher\prismlauncher.exe'),
	[switch]$NoLaunch
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$mods = Join-Path $PrismRoot "instances\$Instance\minecraft\mods"
if (-not (Test-Path -LiteralPath $mods -PathType Container)) { throw "No such instance mods folder: $mods" }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
	throw 'Set JAVA_HOME to a JDK 17 installation before building.'
}
if (-not $NoLaunch -and -not (Test-Path -LiteralPath $PrismExe -PathType Leaf)) {
	throw 'Prism Launcher not found. Set -PrismExe to its executable path, or use -NoLaunch.'
}
# Close Minecraft yourself so the instance's world is saved before its JAR is replaced.
if (Get-Process javaw,java -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -like 'Minecraft*' }) {
	throw 'Close Minecraft before redeploying this bridge.'
}

Push-Location $root
try {
	# Compile against the storm mod already installed in this instance.
	& .\gradlew.bat build --console=plain "-Pwitherstorm_dir=$mods" *> build.log
	if ($LASTEXITCODE -ne 0) {
		Get-Content -LiteralPath build.log -Tail 50
		throw 'Build failed (see build.log).'
	}
} finally { Pop-Location }

$jar = Join-Path $root 'build\libs\passthrough-forge-0.1.0.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Built mod not found: $jar" }
Get-ChildItem -LiteralPath $mods -Filter 'passthrough-forge-*.jar' | ForEach-Object {
	Remove-Item -LiteralPath $_.FullName -Force
}
Copy-Item -LiteralPath $jar -Destination $mods
"Installed $(Split-Path $jar -Leaf) -> $mods"

if (-not $NoLaunch) {
	Start-Process -FilePath $PrismExe -ArgumentList '--launch', $Instance -WindowStyle Hidden
	"Launched instance $Instance"
}
