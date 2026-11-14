---
name: release
description: 合并 Eden 上游 master，构建签名的 Eden Touch APK，并发布到 GitHub Release。
---

# Eden Touch 发布流程

本文件是 Eden Touch（`Xcom9527/eden-android-touch`）的本地发布 runbook，覆盖：

1. 合并上游 `eden-emu/eden` 的最新 `master`
2. 跑触摸层测试
3. 用固定的 release keystore 构建正式 APK
4. 校验包名、应用名、签名和哈希
5. 推送合并提交与版本 tag
6. 创建 GitHub Release 并上传 APK
7. 同步一份到本机临时目录

> 适用环境：Windows + PowerShell 7，项目位于 `E:\Develop\Code\eden-android-touch`。其他机器请按实际路径调整。

---

## 0. 固定信息

| 项 | 值 |
|---|---|
| 工作目录 | `E:\Develop\Code\eden-android-touch` |
| 上游 | `https://git.eden-emu.dev/eden-emu/eden.git`，分支 `master` |
| 上游 remote 名 | `upstream` |
| Release 包名 | `dev.eden.eden_emulator.touch` |
| 应用名 | `Eden Touch` |
| 构建变体 | `MainlineRelease` + `-PtouchAcceptance=true` |
| 签名文件 | `.signing\eden-touch-release.jks` |
| 签名凭据 | `.signing\credentials.txt`（key: `alias` / `storePassword`） |
| 临时 APK 目录 | `E:\File\临时` |
| GitHub 仓库 | `Xcom9527/eden-android-touch` |

`.signing/` 已加入 `.git/info/exclude`，不会提交。**不要把 keystore 或密码写进仓库。**

---

## 1. 前置检查

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

## 2. 合并上游

### 2.1 配置 upstream remote

首次执行：

```powershell
git remote add upstream https://git.eden-emu.dev/eden-emu/eden.git
```

已存在会报错，可忽略或先检查：

```powershell
git remote -v
```

### 2.2 拉取并合并

```powershell
git fetch upstream master
git log -1 --format='%h %cd %s' --date=iso upstream/master
git merge upstream/master
```

如果 `git merge` 直接成功，跳到第 3 节。

### 2.3 处理冲突

目前最可能冲突的文件：

```
src/android/app/src/main/java/org/yuzu/yuzu_emu/fragments/EmulationFragment.kt
```

原因：上游会改启动/loader 流程，Touch 分支也在同一段代码里控制触摸层。

解决原则：

- 保留上游的 `combine(emulationStarted, areShadersBuilt)` 等待逻辑；
- 保留 Touch 的抽屉锁定、`surfaceInputOverlay` 显隐和 `resumeInputs()`。

合并后的 collector 应类似：

```kotlin
combine(
    emulationViewModel.emulationStarted,
    emulationViewModel.areShadersBuilt
) { started, shadersBuilt -> started && shadersBuilt }.collect(viewLifecycleOwner) { isReady ->
    if (isReady) {
        binding.drawerLayout.setDrawerLockMode(
            if (touchWorkspace?.isEditing == true || touchWorkspace?.isTesting == true) {
                DrawerLayout.LOCK_MODE_LOCKED_CLOSED
            } else {
                IntSetting.LOCK_DRAWER.getInt()
            }
        )
        if (touchWorkspace == null) {
            ViewUtils.showView(binding.surfaceInputOverlay)
        } else {
            binding.surfaceInputOverlay.visibility = View.GONE
            if (!touchPauseRequested && !emulationState.isPaused) {
                touchWorkspace?.resumeInputs()
            }
        }
        binding.loadingText.setText(R.string.loading)
        binding.loadingProgressIndicator.isIndeterminate = true
        ViewUtils.hideView(binding.loadingIndicator)
        // ... 其余上游 loading 清理逻辑保持不动
    }
}
```

处理完后：

```powershell
git add -u
git diff --cached --check
git commit --no-edit
```

合并提交信息保持默认的 `Merge remote-tracking branch 'upstream/master'` 即可。

如果冲突涉及 Touch 之外的文件，优先保留上游实现；Touch 的改动应尽量收敛到 `overlay/touch/` 下，减少下次冲突。

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

当前基线：150 个测试，5 个 Windows `File.renameTo` 平台问题失败，其余必须通过。若出现新的失败，先修复再发布。

### 3.3（可选）纯 Kotlin 测试

```powershell
& 'E:\Develop\Code\eden-android-touch\.cache\touch-tests-pwsh.ps1'
```

该脚本是本地脚本，不在 git 中；没有就跳过。

---

## 4. 打版本 tag

在合并提交上创建 annotated tag。**tag 要在构建前创建**，这样 `versionName` 会包含它。

```powershell
Set-Location 'E:\Develop\Code\eden-android-touch'
$version = '2026.11.14'   # 改成当天日期；同一天第二次发布用 2026.11.14.2
$tag = "v$version"

git tag -a $tag -m "Eden Touch $version (upstream $(git rev-parse --short upstream/master))"
git describe --always --long
```

预期 `git describe` 输出形如：

```
v2026.11.14-0-g39ffa4bc0b
```

最终 APK 的 `versionName` 会是 `v2026.11.14-touch`（`-touch` 来自 `touchAcceptance` 构建后缀），`versionCode` 由构建时间自动生成，天然递增。

---

## 5. 构建签名 release APK

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

### 5.2 构建

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
if ($code -ne 0) { throw 'release 构建失败' }
```

产物：

```
src/android/app/build/outputs/apk/mainline/release/app-mainline-release.apk
```

首次原生 release 构建较慢；之后有缓存通常 1–3 分钟。

---

## 6. APK 校验（必做）

```powershell
$apk = 'E:\Develop\Code\eden-android-touch\src\android\app\build\outputs\apk\mainline\release\app-mainline-release.apk'
$aapt = 'E:\Develop\Android\Sdk\build-tools\36.1.0\aapt2.exe'
$apksigner = 'E:\Develop\Android\Sdk\build-tools\36.1.0\apksigner.bat'

& $aapt dump badging $apk | Select-String '^package:|^application-label:'
& $apksigner verify --print-certs $apk | Select-String 'Signer #1 certificate (DN|SHA-256)'
Get-Item $apk | Select-Object Length
(Get-FileHash $apk -Algorithm SHA256).Hash
```

必须满足：

- `package: name='dev.eden.eden_emulator.touch'`
- `application-label:'Eden Touch'`
- 签名证书 SHA-256 为固定 release 证书：
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

## 8. 创建 GitHub Release 并上传 APK

> 下面命令需要和前面的构建使用同一个 PowerShell 会话；如果新开窗口，先重新设置本节变量。

```powershell
Set-Location 'E:\Develop\Code\eden-android-touch'
$version = '2026.11.14'   # 与第 4 节保持一致
$tag = "v$version"
$repo = 'Xcom9527/eden-android-touch'
$apk = 'E:\Develop\Code\eden-android-touch\src\android\app\build\outputs\apk\mainline\release\app-mainline-release.apk'
```

### 8.1 方式 A：`gh` CLI（如果已安装并登录）

```powershell
$asset = "Eden-Touch-arm64-v$version.apk"

gh release create $tag $apk `
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

创建 release：

```powershell
$apk = 'E:\Develop\Code\eden-android-touch\src\android\app\build\outputs\apk\mainline\release\app-mainline-release.apk'
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash
$body = @"
Eden Touch $version — unofficial Android touch fork of Eden.

- Upstream: https://git.eden-emu.dev/eden-emu/eden
- Source commit: https://github.com/$repo/commit/$(git rev-parse HEAD)
- Package: dev.eden.eden_emulator.touch
- Version: v$version-touch
- ABIs: arm64-v8a
- APK SHA-256: $sha

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
```

上传资产：

```powershell
$asset = "Eden-Touch-arm64-v$version.apk"
$uploadHeaders = @{
    Authorization = "Bearer $($cred['password'])"
    'User-Agent'  = 'eden-touch-release'
    'Content-Type' = 'application/vnd.android.package-archive'
}
Invoke-RestMethod -Headers $uploadHeaders -Method Post -InFile $apk `
    -Uri "https://uploads.github.com/repos/$repo/releases/$($release.id)/assets?name=$asset"
```

### 8.3 Release 说明必须包含

可直接把下面模板保存为 `release-notes.md`（替换占位符）：

```markdown
Eden Touch 2026.11.14 — unofficial Android touch fork of Eden.

- Upstream Eden: https://git.eden-emu.dev/eden-emu/eden (synced to <upstream-sha>)
- Source commit: https://github.com/Xcom9527/eden-android-touch/commit/<merge-sha>
- Package: dev.eden.eden_emulator.touch
- Version: v2026.11.14-touch
- ABIs: arm64-v8a
- Signed with the Eden Touch release key (SHA-256: 87cea31d13dcaf994f39b4794376b2d17bb02e8469e4545d20b92b230ba4f279)
- APK SHA-256: <apk-sha256>

Eden Touch is an unofficial fork. Eden is licensed under GPLv3; the complete
corresponding source for this build is the linked repository/commit.
```

### 8.4 验证

```powershell
$rel = Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$repo/releases/latest"
$rel.tag_name
$rel.assets | Select-Object name, size, state, digest
```

`state` 必须是 `uploaded`，`digest` 必须与本地 SHA-256 一致。

---

## 9. 同步到本机临时目录

固定规则：每次构建后把最新 APK 放到 `E:\File\临时`，并删除上一版本项目 APK；不要删无关文件。

```powershell
$version = '2026.11.14'   # 与第 4 节保持一致
$dir = 'E:\File\临时'
$apk = 'E:\Develop\Code\eden-android-touch\src\android\app\build\outputs\apk\mainline\release\app-mainline-release.apk'

# 只删除本项目旧 APK
Get-ChildItem $dir -File |
    Where-Object { $_.Name -like 'Eden-Touch-*.apk' -or $_.Name -like 'app-mainline-relWithDebInfo*.apk' } |
    Remove-Item -Force

$dst = Join-Path $dir "Eden-Touch-$version.apk"
Copy-Item $apk $dst -Force
Get-Item $dst | Select-Object FullName, Length
(Get-FileHash $dst -Algorithm SHA256).Hash
```

---

## 10. 发布后检查

- [ ] `origin/master` 包含合并提交
- [ ] tag 指向合并提交
- [ ] Release 为 latest / 非 draft / 非 prerelease
- [ ] APK 标签为 `Eden Touch`，包名为 `.touch`
- [ ] 签名证书为 release 证书
- [ ] Release 资产 SHA-256 与本地一致
- [ ] 本地临时目录只剩一个本项目 APK
- [ ] 已提醒测试用户：旧 `relWithDebInfo` 包与新 `.touch` 包是两个独立应用

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
- `-PtouchAcceptance=true` 才会得到 `.touch` 包名和 `Eden Touch` 应用名；不带该参数构建的是官方包名，不能用来发这个分支。
- `relWithDebInfo` 是本地调试包，debug 签名、可调试，不能作为正式发布资产。
- GitHub 仓库目前是私有仓库；Release 链接需要登录对应账号才能下载。
- 发布必须遵守 GPLv3：Release 说明中保留上游地址、源码提交和非官方声明。
