$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
npx --yes firebase-tools@14.12.0 emulators:start --only auth --project demo-orka-product --config firebase.json
