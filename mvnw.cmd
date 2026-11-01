@echo off
rem ----------------------------------------------------------------------------
rem Lightweight Maven wrapper for Windows.
rem Downloads the pinned Maven version on first use (into
rem %USERPROFILE%\.m2\wrapper\dists), then runs it.
rem Usage: mvnw.cmd spring-boot:run
rem ----------------------------------------------------------------------------
setlocal
set "MVNW_BASEDIR=%~dp0"
set "MVNW_DEFAULT_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip"
set "MVNW_MAVEN_HOME="

for /f "usebackq delims=" %%M in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url = $env:MVNW_DEFAULT_URL; $propsFile = Join-Path $env:MVNW_BASEDIR '.mvn\wrapper\maven-wrapper.properties'; if (Test-Path $propsFile) { $m = [regex]::Match((Get-Content -Raw $propsFile), 'distributionUrl=(\S+)'); if ($m.Success) { $url = $m.Groups[1].Value } }; $file = Split-Path $url -Leaf; $name = $file -replace '-bin\.zip$',''; $root = $env:MAVEN_USER_HOME; if (-not $root) { $root = Join-Path $env:USERPROFILE '.m2' }; $dists = Join-Path $root 'wrapper\dists'; $mvnHome = Join-Path $dists $name; if (-not (Test-Path (Join-Path $mvnHome 'bin\mvn.cmd'))) { [Console]::Error.WriteLine('Downloading ' + $name + ' (first run only)...'); $null = New-Item -ItemType Directory -Force -Path $dists; $zip = Join-Path $dists $file; Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip; Expand-Archive -Force -Path $zip -DestinationPath $dists; Remove-Item $zip }; Write-Output $mvnHome"`) do set "MVNW_MAVEN_HOME=%%M"

if not defined MVNW_MAVEN_HOME (
  echo Error: could not download or locate Maven. 1>&2
  exit /b 1
)

cd /d "%MVNW_BASEDIR%"
call "%MVNW_MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
