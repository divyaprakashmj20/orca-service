#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
python3 - <<'PY'
import hashlib,json,pathlib
for entry in json.loads(pathlib.Path('release-manifest.json').read_text())['files']:
    path=pathlib.Path(entry['path'])
    assert not path.is_absolute() and '..' not in path.parts
    data=path.read_bytes()
    assert len(data)==entry['bytes'] and hashlib.sha256(data).hexdigest()==entry['sha256'], str(path)
print('Verified the public iOS release assets and native source.')
PY
npm ci --ignore-scripts --no-audit --no-fund
gem install xcodeproj --user-install --no-document
ruby add-ui-tests.rb
xcodebuild -version
xcodebuild -project ios/App/App.xcodeproj -scheme OrkaSideload -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build/device \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY='' \
  CURRENT_PROJECT_VERSION="${GITHUB_RUN_NUMBER:-1}" MARKETING_VERSION=1.0.2 build > build-device.log 2>&1 || { tail -n 100 build-device.log; exit 1; }
mkdir -p build/package/Payload
ditto build/device/Build/Products/Release-iphoneos/App.app build/package/Payload/Orka.app
(cd build/package && ditto -c -k --sequesterRsrc --keepParent Payload Orka-1.0.2-unsigned.ipa)
python3 - <<'PY'
import hashlib,json,pathlib,plistlib,zipfile
ipa=pathlib.Path('build/package/Orka-1.0.2-unsigned.ipa')
with zipfile.ZipFile(ipa) as z:
    p=plistlib.loads(z.read('Payload/Orka.app/Info.plist'))
    assert p['CFBundleIdentifier']=='com.lytspeed.orka'
    assert p['MinimumOSVersion']=='16.4'
    assert p['CFBundleShortVersionString']=='1.0.2'
    assert 'NSAppTransportSecurity' not in p
    assert not any(n.endswith('.mobileprovision') or n.endswith('.p12') or n.endswith('.p8') for n in z.namelist())
    c=json.loads(z.read('Payload/Orka.app/capacitor.config.json'))
    assert 'server' not in c and c['appId']=='com.lytspeed.orka'
report=dict(version=p['CFBundleShortVersionString'],build=p['CFBundleVersion'],minimumIos=p['MinimumOSVersion'],bundleId=p['CFBundleIdentifier'],bytes=ipa.stat().st_size,sha256=hashlib.sha256(ipa.read_bytes()).hexdigest(),signed=False,remotePushSupported=False)
pathlib.Path('build/package/release.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
PY
simulator_id=$(xcrun simctl list devices available -j | python3 -c 'import json,sys; j=json.load(sys.stdin); print(next(d["udid"] for values in j["devices"].values() for d in values if d["name"].startswith("iPhone") and d["isAvailable"]))')
xcrun simctl boot "$simulator_id" || true
xcrun simctl bootstatus "$simulator_id" -b
xcodebuild test -project ios/App/App.xcodeproj -scheme OrkaSideload -configuration Debug \
  -destination "platform=iOS Simulator,id=$simulator_id" -derivedDataPath build/simulator \
  -resultBundlePath build/ios-smoke.xcresult CODE_SIGNING_ALLOWED=NO \
  > build-simulator.log 2>&1 || { tail -n 100 build-simulator.log; exit 1; }
xcrun simctl io "$simulator_id" screenshot build/package/iphone-simulator.png
curl --fail --silent --show-error --max-time 20 -H 'Origin: capacitor://localhost' \
  https://orka-service.duckdns.org/api/notifications/health > build/package/api-health.json
python3 -c 'import json; assert json.load(open("build/package/api-health.json")).get("fcmEnabled") is True'
echo 'Unsigned device IPA and native simulator UI checks completed.'
