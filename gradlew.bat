@echo off
setlocal

set APP_HOME=%~dp0
set PROPERTIES_FILE=%APP_HOME%gradle\wrapper\gradle-wrapper.properties

if not exist "%PROPERTIES_FILE%" (
  echo Missing %PROPERTIES_FILE%
  exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop';" ^
  "$props=Get-Content '%PROPERTIES_FILE%';" ^
  "$url=($props | Where-Object { $_ -like 'distributionUrl=*' }) -replace '^distributionUrl=','' -replace '\\:', ':';" ^
  "if (-not $url) { throw 'distributionUrl not found' }" ^
  "$version=[regex]::Match($url,'gradle-([^-]+)-bin\.zip').Groups[1].Value;" ^
  "if (-not $version) { throw 'Gradle version not found' }" ^
  "$cache=Join-Path '%APP_HOME%.gradle\wrapper\dists' ('gradle-' + $version + '-bin');" ^
  "$home=Join-Path $cache ('gradle-' + $version);" ^
  "$zip=Join-Path $cache ('gradle-' + $version + '-bin.zip');" ^
  "if (!(Test-Path (Join-Path $home 'bin\gradle.bat'))) {" ^
  "  New-Item -ItemType Directory -Force -Path $cache | Out-Null;" ^
  "  if (!(Test-Path $zip)) { Invoke-WebRequest -Uri $url -OutFile $zip };" ^
  "  Expand-Archive -LiteralPath $zip -DestinationPath $cache -Force;" ^
  "}" ^
  "& (Join-Path $home 'bin\gradle.bat') %*"

exit /b %ERRORLEVEL%
