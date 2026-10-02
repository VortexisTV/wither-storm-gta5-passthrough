@echo off
rem Builds the plugin's off-line test with MSVC into tests\shv\: a stand-in ScriptHookV.dll (fakeshv.cpp), the host
rem shvhost.exe, and a copy of build\MCPassthrough.asi (run ..\build.bat first). See fakeshv.cpp.
setlocal
if not defined VCVARS for /f "usebackq delims=" %%i in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VCVARS=%%i\VC\Auxiliary\Build\vcvars64.bat"
call "%VCVARS%" >nul 2>nul || exit /b 1
cd /d "%~dp0"
if not exist shv mkdir shv
cl /nologo /LD /O2 /EHsc /std:c++20 /MT /DWIN32_LEAN_AND_MEAN /DNOMINMAX fakeshv.cpp /Fo"shv\\" /Fe"shv\ScriptHookV.dll" || exit /b 1
cl /nologo /O2 /EHsc /std:c++20 /MT /DWIN32_LEAN_AND_MEAN /DNOMINMAX shvhost.cpp /Fo"shv\\" /Fe"shv\shvhost.exe" /link shv\ScriptHookV.lib || exit /b 1
copy /y ..\build\MCPassthrough.asi shv\ >nul || exit /b 1
