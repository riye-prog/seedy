$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
cmake -S native -B native/build-overlay -A x64 "-DJAVA_HOME=$env:JAVA_HOME"
if ($LASTEXITCODE -ne 0) { throw 'Native configuration failed' }
cmake --build native/build-overlay --target seedy --config Release
if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
