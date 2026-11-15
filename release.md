---
name: release
description: 检查上游 24 小时同步窗口，构建 Eden Touch 与 Eden-TouchG 两个签名 APK，并发布到 GitHub Release。
---

# Eden Touch 发布流程

本文件是 Eden Touch（`Xcom9527/eden-android-touch`）的本地发布 runbook，覆盖：

1. 检查最近一次上游同步时间，超过 24 小时先同步 `eden-emu/eden`
2. 跑触摸层测试
3. 用固定 release keystore 构建普通版和原神版 APK
4. 校验两个包的包名、应用名、签名和哈希
5. 推送源码与版本 tag
6. 创建 GitHub Release 并上传两个 APK
7. 同步两个 APK 到本机临时目录

> 适用环境：Windows + PowerShell 7，项目位于 `E:\Develop\Code\eden-android-touch`。其他机器请按实际路径调整。

---

## 0. 固定信息

| 项 | 普通版 | 原神版 |
|---|---|---|
| 应用名 | `Eden Touch` | `Eden-TouchG` |
| 包名 | `dev.eden.eden_emulator.touch` | `com.miHoYo.Yuanshen` |
| 构建变体 | `MainlineRelease` + `-PtouchAcceptance=true` | `GenshinSpoofRelease` |
| minSdk | 33 | 35 |
| APK 输出 | `apk/mainline/release/app-mainline-release.apk` | `apk/genshinSpoof/release/app-genshinSpoof-release.apk` |
| 资产名 | `Eden-Touch-arm64-v$version.apk` | `Eden-TouchG-arm64-v$version.apk` |
| 临时副本名 | `Eden-Touch-$version.apk` | `Eden-TouchG-$version.apk` |

固定路径：

| 项 | 值 |
|---|---|
| 工作目录 | `E:\Develop\Code\eden-android-touch` |
| 上游 | `https://git.eden-emu.dev/eden-emu/eden.git`，分支 `master` |
| 上游 remote | `upstream` |
| 签名文件 | `.signing\eden-touch-release.jks` |
| 签名凭据 | `.signing\credentials.txt`（key: `alias` / `storePassword`） |
| 临时 APK 目录 | `E:\File\临时` |
| GitHub 仓库 | `Xcom9527/eden-android-touch` |

`.signing/` 已加入 `.git/info/exclude`，不会提交。**不要把 keystore 或密码写进仓库。**

---

## 1. 检查上游同步时间（发布前必做）

规则：

- 最近一次同步上游代码距今 **不足 24 小时**：可以继续发布。
- **达到或超过 24 小时**：必须先 `fetch + merge` 上游，再跑测试，然后才能发布。
- 找不到同步记录时，按“从未同步”处理，先同步。

同步时间优先读取本机标记文件 `.cache/last-upstream-sync`；没有标记时，回退到最近一次 `Merge remote-tracking branch 'upstream/master'` 提交。

```powershell
Set-Location 'E:\Develop\Code\eden-android-touch'

$marker = '.cache/last-upstream-sync'
if (Test-Path $marker) {
    $lastSync = [datetimeoffset]::Parse((Get-Content $marker -Raw).Trim())
} else {
    $merge = git log -1 --merges --format='%cI' --grep="upstream/master"
    if ($merge) {
        $lastSync = [datetimeoffset]::Parse($merge)
    } else {
        $lastSync = [datetimeoffset]::MinValue
    }
}

$age = [datetimeoffset]::Now - $lastSync
Write-Host "Last upstream sync: $lastSync"
Write-Host "Age: $([math]::Round($age.TotalHours, 1)) hours"
```

### 1.1 超过 24 小时时同步上游

```powershell
git fetch upstream master

# 先看上游最新提交
git log -1 --format='%h %ci %s' --date=iso upstream/master

# 合并上游
git merge upstream/master
```

如果已经是最新，`git merge` 会提示 `Already up to date.`；仍然要更新标记时间：

```powershell
New-Item -ItemType Directory -Path '.cache' -Force | Out-Null
(Get-Date).ToString('o') | Set-Content '.cache/last-upstream-sync' -Encoding utf8
```

如果发生冲突，处理完并提交后再写标记：

```powershell
git add -u
git diff --cached --check
git commit --no-edit
(Get-Date).ToString('o') | Set-Content '.cache/last-upstream-sync' -Encoding utf8
```

### 1.2 合并冲突原则

最可能冲突的文件：

```
src/android/app/src/main/java/org/yuzu/yuzu_emu/fragments/EmulationFragment.kt
```

原则：

- 保留上游的 `combine(emulationStarted, areShadersBuilt)` 等待逻辑；
- 保留 Touch 的抽屉锁定、`surfaceInputOverlay` 显隐和 `resumeInputs()`；
- Touch 改动尽量收敛在 `overlay/touch/` 下，减少下次冲突。

同步后必须重新跑第 3 节测试，不能直接沿用同步前的测试结果。

---

## 2. 前置检查

```powershell
Set-Location 'E:\Develop\Code\eden-android-touch'

# 工作区必须没有未提交的改动（docs_touch/ 是长期未跟踪目录，可保留）
git status --short --branch

# 确认签名文件还在
Get-ChildItem '.signing' | Select-Object Name, Length
```

要求：

- `git status` 除 `?? docs_touch/` 外没有其它输出；
- `.signing\eden-touch-release.jks` 和 `credentials.txt` 存在；
- 如果换了机器，先把这两个文件从备份恢复，**不要重新生成密钥**，否则老用户无法覆盖升级。

---

## 3. 跑测试

### 3.1 环境变量

```powershell
$env:JAVA_HOME='E:\Develop\JDK21'
$env:ANDROID_HOME='E:\Develop\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:GRADLE_USER_HOME='E:\Develop\gradle-home'
$glslang='E:\Develop\Android\tools\glslang-16.6.0\bin'
if ($env:Path -notlike "*$glslang*") { $env:Path = "$env:Path;$glslang" }
```

### 3.2 触摸层 Robolectric 测试

```powershell
Set-Location 'E:\Develop\Code\eden-android-touch\src\android'

.\gradlew.bat :app:testMainlineRelWithDebInfoUnitTest `
  --tests "org.yuzu.yuzu_emu.overlay.touch.*" `
  --console=plain `
  '-Pandroid.builder.sdkDownload=false' `
  '-PYUZU_ANDROID_ARGS=-DCMAKE_NINJA_FORCE_RESPONSE_FILE=ON'
```

当前基线：162 个测试，5 个 Windows `File.renameTo` 平台问题失败，其余必须通过。若出现新的失败，先修复再发布。

### 3.3（可选）纯 Kotlin 测试

```powershell
& 'E:\Develop\Code\eden-android-touch\.cache\touch-tests-pwsh.ps1'
```

当前基线：124 个测试，5 个已知 Windows 失败。该脚本是本地脚本，不在 git 中；没有就跳过。

---

## 4. 打版本 tag

在合并提交上创建 annotated tag。**tag 要在构建前创建**，这样 `versionName` 会包含它。

```powershell
Set-Location 'E:\Develop\Code\eden-android-touch'
$version = '2026.11.14.8'   # 改成当天版本；同一天继续发布时递增末位
$tag = "v$version"

git tag -a $tag -m "Eden Touch $version (upstream $(git rev-parse --short upstream/master))"
git describe --always --long
```

预期 `git describe` 输出形如：

```
v2026.11.14.8-0-g72915069c6
```

普通版 `versionName` 为 `v$version-touch`，原神版为 `v$version`。`versionCode` 由构建时间自动生成，天然递增。

---

## 5. 构建两个签名 release APK

### 5.1 读取签名凭据

`ConvertFrom-StringData` 会把路径里的反斜杠当转义符，必须手工按第一个 `=` 分割：

```powershell
$signDir = 'E:\Develop\Code\eden-android-touch\.signing'
$cred = @{}
Get-Content (Join-Path $signDir 'credentials.txt') | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { $cred[$matches[1]] = $matches[2] }
}

$env:ANDROID_KEYSTORE_FILE = Join-Path $signDir 'eden-touch-release.jks'
$env:ANDROID_KEYSTORE_PASS = $cred['storePassword']
$env:ANDROID_KEY_ALIAS     = $cred['alias']

if ([string]::IsNullOrEmpty($env:ANDROID_KEYSTORE_FILE)) { throw 'keystore 路径为空' }
if ([string]::IsNullOrEmpty($env:ANDROID_KEYSTORE_PASS)) { throw 'keystore 密码为空' }
```

> 构建配置里 `keyPassword` 也读取 `ANDROID_KEYSTORE_PASS`，所以 keystore 密码和 key 密码必须一致。

### 5.2 构建普通版

```powershell
$env:JAVA_HOME='E:\Develop\JDK21'
$env:ANDROID_HOME='E:\Develop\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:GRADLE_USER_HOME='E:\Develop\gradle-home'
$glslang='E:\Develop\Android\tools\glslang-16.6.0\bin'
if ($env:Path -notlike "*$glslang*") { $env:Path = "$env:Path;$glslang" }

Set-Location 'E:\Develop\Code\eden-android-touch\src\android'
$log = 'E:\Develop\Code\eden-android-touch\release-build.log'

.\gradlew.bat :app:assembleMainlineRelease `
  -PtouchAcceptance=true `
  --console=plain `
  '-Pandroid.builder.sdkDownload=false' `
  '-PYUZU_ANDROID_ARGS=-DCMAKE_NINJA_FORCE_RESPONSE_FILE=ON -DENABLE_LTO=OFF' `
  *> $log

$code = $LASTEXITCODE
Get-Content $log -Tail 40
Write-Host "GRADLE_EXIT=$code"
if ($code -ne 0) { throw '普通版 release 构建失败' }
```

产物：

```
src/android/app/build/outputs/apk/mainline/release/app-mainline-release.apk
```

### 5.3 构建原神版 Eden-TouchG

**不要加 `-PtouchAcceptance=true`**，否则包名会变成 `com.miHoYo.Yuanshen.touch`，Genshin Spoof 失效。

```powershell
Set-Location 'E:\Develop\Code\eden-android-touch\src\android'
$log = 'E:\Develop\Code\eden-android-touch\release-build-touchg.log'

.\gradlew.bat :app:assembleGenshinSpoofRelease `
  --console=plain `
  '-Pandroid.builder.sdkDownload=false' `
  '-PYUZU_ANDROID_ARGS=-DCMAKE_NINJA_FORCE_RESPONSE_FILE=ON -DENABLE_LTO=OFF' `
  *> $log

$code = $LASTEXITCODE
Get-Content $log -Tail 40
Write-Host "GRADLE_EXIT=$code"
if ($code -ne 0) { throw 'Eden-TouchG release 构建失败' }
```

产物：

```
src/android/app/build/outputs/apk/genshinSpoof/release/app-genshinSpoof-release.apk
```

预期：应用名 `Eden-TouchG`，包名 `com.miHoYo.Yuanshen`，minSdk 35。

首次原生 release 构建较慢；之后有缓存通常 1–3 分钟。

---

## 6. APK 校验（必做）

```powershell
$normal = 'E:\Develop\Code\eden-android-touch\src\android\app\build\outputs\apk\mainline\release\app-mainline-release.apk'
$touchg = 'E:\Develop\Code\eden-android-touch\src\android\app\build\outputs\apk\genshinSpoof\release\app-genshinSpoof-release.apk'
$aapt = 'E:\Develop\Android\Sdk\build-tools\36.1.0\aapt2.exe'
$apksigner = 'E:\Develop\Android\Sdk\build-tools\36.1.0\apksigner.bat'

foreach ($apk in @($normal, $touchg)) {
    Write-Host "=== $apk ==="
    & $aapt dump badging $apk | Select-String '^package:|^application-label:|minSdkVersion'
    & $apksigner verify --print-certs $apk | Select-String 'Signer #1 certificate (DN|SHA-256)'
    Get-Item $apk | Select-Object Length
    (Get-FileHash $apk -Algorithm SHA256).Hash
}
```

必须满足：

- 普通版：`package: name='dev.eden.eden_emulator.touch'`、`application-label:'Eden Touch'`
- 原神版：`package: name='com.miHoYo.Yuanshen'`、`application-label:'Eden-TouchG'`、`minSdkVersion:'35'`
- 两个包签名证书 SHA-256 都是固定 release 证书：
  `87cea31d13dcaf994f39b4794376b2d17bb02e8469e4545d20b92b230ba4f279`
- 不是 `CN=Android Debug` 的 debug 证书

若签名不对，检查第 5.1 节环境变量是否在 Gradle 配置阶段就已设置；改环境变量后重新执行 assemble。

---

## 7. 推送源码与 tag

```powershell
Set-Location 'E:\Develop\Code\eden-android-touch'
git push origin master
git push origin $tag

git log -1 --oneline
git ls-remote origin refs/heads/master "refs/tags/$tag"
```

要求远端 `master` 与本地合并提交一致，tag 指向同一提交。

---

## 8. 创建 GitHub Release 并上传两个 APK

> 下面命令需要和前面的构建使用同一个 PowerShell 会话；如果新开窗口，先重新设置本节变量。

```powershell
Set-Location 'E:\Develop\Code\eden-android-touch'
$version = '2026.11.14.8'   # 与第 4 节保持一致
$tag = "v$version"
$repo = 'Xcom9527/eden-android-touch'
$normal = 'E:\Develop\Code\eden-android-touch\src\android\app\build\outputs\apk\mainline\release\app-mainline-release.apk'
$touchg = 'E:\Develop\Code\eden-android-touch\src\android\app\build\outputs\apk\genshinSpoof\release\app-genshinSpoof-release.apk'
```

### 8.1 方式 A：`gh` CLI（如果已安装并登录）

```powershell
gh release create $tag $normal $touchg `
  --title "Eden Touch $version" `
  --notes-file release-notes.md `
  --latest
```

需要先准备 `release-notes.md`（内容见 8.3）。

### 8.2 方式 B：GitHub REST API（本机未装 gh 时）

凭据从 Git Credential Manager 读取，不打印 token：

```powershell
$env:GIT_TERMINAL_PROMPT = '0'
$cred = @{}
("protocol=https`nhost=github.com`n`n" | git credential fill) | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { $cred[$matches[1]] = $matches[2] }
}
$headers = @{
    Authorization = "Bearer $($cred['password'])"
    'User-Agent'  = 'eden-touch-release'
}
```

创建 release 并上传两个资产：

```powershell
$normalSha = (Get-FileHash $normal -Algorithm SHA256).Hash
$touchgSha = (Get-FileHash $touchg -Algorithm SHA256).Hash
$body = @"
Eden Touch $version — unofficial Android touch fork of Eden.

- Upstream: https://git.eden-emu.dev/eden-emu/eden
- Source commit: https://github.com/$repo/commit/$(git rev-parse HEAD)
- Normal package: dev.eden.eden_emulator.touch
- Genshin Spoof package: com.miHoYo.Yuanshen

Normal APK SHA-256: $normalSha
Eden-TouchG APK SHA-256: $touchgSha

Eden Touch is an unofficial fork. Eden is licensed under GPLv3; the complete corresponding source is the linked repository/commit.
"@

$payload = @{
    tag_name = $tag
    target_commitish = 'master'
    name = "Eden Touch $version"
    body = $body
    draft = $false
    prerelease = $false
    generate_release_notes = $false
} | ConvertTo-Json

$release = Invoke-RestMethod -Headers $headers -Method Post `
    -ContentType 'application/json' -Body $payload `
    -Uri "https://api.github.com/repos/$repo/releases"

$uploadHeaders = @{
    Authorization = "Bearer $($cred['password'])"
    'User-Agent'  = 'eden-touch-release'
    'Content-Type' = 'application/vnd.android.package-archive'
}

foreach ($item in @(
    @{ Path = $normal; Name = "Eden-Touch-arm64-v$version.apk" },
    @{ Path = $touchg; Name = "Eden-TouchG-arm64-v$version.apk" }
)) {
    Invoke-RestMethod -Headers $uploadHeaders -Method Post -InFile $item.Path `
        -Uri "https://uploads.github.com/repos/$repo/releases/$($release.id)/assets?name=$($item.Name)"
}
```

### 8.3 Release 说明必须包含

```markdown
Eden Touch 2026.11.14.8 — unofficial Android touch fork of Eden.

- Upstream Eden: https://git.eden-emu.dev/eden-emu/eden (synced to <upstream-sha>)
- Source commit: https://github.com/Xcom9527/eden-android-touch/commit/<merge-sha>
- Signed with the Eden Touch release key (SHA-256: 87cea31d13dcaf994f39b4794376b2d17bb02e8469e4545d20b92b230ba4f279)

## Eden Touch (normal)
- Package: dev.eden.eden_emulator.touch
- Version: v2026.11.14.8-touch
- ABIs: arm64-v8a
- APK SHA-256: <normal-apk-sha256>

## Eden-TouchG (Genshin Spoof)
- Package: com.miHoYo.Yuanshen
- Version: v2026.11.14.8
- minSdk: 35 (Android 15+)
- ABIs: arm64-v8a
- APK SHA-256: <touchg-apk-sha256>
- 使用国服原神包名以套用系统/GPU 的原神游戏配置；不能与真正的国服原神共存，安装前请卸载原神并备份数据。它不会运行原神，也不会访问原神账号。

Eden Touch is an unofficial fork. Eden is licensed under GPLv3; the complete
corresponding source for this build is the linked repository/commit.
```

### 8.4 验证

```powershell
$rel = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$repo/releases/latest"
$rel.tag_name
$rel.assets | Select-Object name, size, state, digest
```

`state` 必须是 `uploaded`，两个 asset 的 `digest` 必须分别与本地 SHA-256 一致。

---

## 9. 同步到本机临时目录

固定规则：每次构建后把两个最新 APK 放到 `E:\File\临时`，删除上一版项目 APK；不要删无关文件。

```powershell
$version = '2026.11.14.8'   # 与第 4 节保持一致
$dir = 'E:\File\临时'
$normal = 'E:\Develop\Code\eden-android-touch\src\android\app\build\outputs\apk\mainline\release\app-mainline-release.apk'
$touchg = 'E:\Develop\Code\eden-android-touch\src\android\app\build\outputs\apk\genshinSpoof\release\app-genshinSpoof-release.apk'

# 只删除本项目旧 APK
Get-ChildItem $dir -File |
    Where-Object {
        $_.Name -like 'Eden-Touch-*.apk' -or
        $_.Name -like 'Eden-TouchG-*.apk' -or
        $_.Name -like 'app-mainline-relWithDebInfo*.apk'
    } |
    Remove-Item -Force

Copy-Item $normal (Join-Path $dir "Eden-Touch-$version.apk") -Force
Copy-Item $touchg (Join-Path $dir "Eden-TouchG-$version.apk") -Force

Get-ChildItem $dir -Filter '*.apk' | Select-Object Name, Length
(Get-FileHash (Join-Path $dir "Eden-Touch-$version.apk") -Algorithm SHA256).Hash
(Get-FileHash (Join-Path $dir "Eden-TouchG-$version.apk") -Algorithm SHA256).Hash
```

---

## 10. 发布后检查

- [ ] 发布前已检查上次上游同步时间；超过 24 小时已同步并重跑测试
- [ ] `origin/master` 包含发布提交
- [ ] tag 指向发布提交
- [ ] Release 为 latest / 非 draft / 非 prerelease
- [ ] 普通版标签为 `Eden Touch`，包名为 `.touch`
- [ ] 原神版标签为 `Eden-TouchG`，包名为 `com.miHoYo.Yuanshen`，minSdk 35
- [ ] 两个包签名证书均为 release 证书
- [ ] 两个 Release 资产 SHA-256 与本地一致
- [ ] 本地临时目录同时有 `Eden-Touch-*.apk` 和 `Eden-TouchG-*.apk`
- [ ] 已提醒测试用户：旧 `relWithDebInfo` 包与新包是两个独立应用
- [ ] 已提醒原神版用户：不能与真正的国服原神共存

---

## 11. 密钥与回滚

### 密钥

- 只使用 `.signing\eden-touch-release.jks`；
- `.signing\credentials.txt` 含明文密码，必须离线备份并限制访问；
- 密钥丢失 = 无法覆盖升级，只能换包名重新发布；
- 不要提交、不要上传到公开位置。

如果在新机器上且签名文件确实不存在，才生成新密钥（生成后无法覆盖旧版本升级）：

```powershell
$signDir = 'E:\Develop\Code\eden-android-touch\.signing'
New-Item -ItemType Directory -Path $signDir -Force | Out-Null
$alias = 'eden-touch'
$pass = [guid]::NewGuid().ToString('N') + 'Aa1!'
$ks = Join-Path $signDir 'eden-touch-release.jks'

& 'E:\Develop\JDK21\bin\keytool.exe' -genkeypair -v `
  -keystore $ks -alias $alias -keyalg RSA -keysize 4096 -validity 10000 `
  -storepass $pass -keypass $pass `
  -dname 'CN=Eden Touch, OU=Touch Fork, O=Eden Touch, L=Local, ST=Local, C=CN'

@("keystore=$ks", "alias=$alias", "storePassword=$pass", "keyPassword=$pass") |
  Set-Content (Join-Path $signDir 'credentials.txt') -Encoding utf8
```

生成后立即备份 `.signing` 整个目录。

### 回滚

- 构建失败：不要 push tag/release；删除本地 tag 后重试：
  ```powershell
  git tag -d $tag
  ```
- 已发布的错误版本：不要删除 tag 后复用同名 tag（GitHub Release 会混乱）；发一个新的补丁版本，并在 Release 说明中注明替代关系。

---

## 12. 已知注意事项

- 上游是 GitLab，不是 GitHub；`git fetch` 网络需可达 `git.eden-emu.dev`。
- 上游 `master` 变动频繁时，合并冲突会集中在 `EmulationFragment.kt`；解决时不要把 Touch 逻辑塞进更多上游文件。
- `-PtouchAcceptance=true` 才会得到 `.touch` 包名和 `Eden Touch` 应用名；原神版必须**不带**该参数。
- 原神版构建变体内部仍叫 `genshinSpoof`，但应用名和资产名统一为 `Eden-TouchG`。
- `relWithDebInfo` 是本地调试包，debug 签名、可调试，不能作为正式发布资产。
- GitHub 仓库目前是私有仓库；Release 链接需要登录对应账号才能下载。
- 发布必须遵守 GPLv3：Release 说明中保留上游地址、源码提交和非官方声明。
