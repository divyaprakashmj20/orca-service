$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$env:FIREBASE_AUTH_EMULATOR_HOST = '127.0.0.1:9099'
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=integration'
