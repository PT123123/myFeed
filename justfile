# lanpub 从用户级配置解析局域网 F-Droid 仓库位置。
set windows-shell := ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command"]

APK := env_var_or_default("APK", "app/build/outputs/apk/release/app-release.apk")

default:
    @just --list

# 先构建 release APK，再用 just publish 发布；可通过 APK 环境变量指定其他产物。
publish:
    lanpub add "{{APK}}"; exit $LASTEXITCODE
