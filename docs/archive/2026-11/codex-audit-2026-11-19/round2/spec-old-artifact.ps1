$ErrorActionPreference = 'Stop'
$audit = $PSScriptRoot
$checkout = Join-Path $audit 'checkout'
$old = Join-Path $audit 'old-master'
$java = 'C:/Users/yuwen/.jdks/corretto-21.0.7/bin/java.exe'
$javaHome = Split-Path -Parent $java
$jar = Join-Path $old 'target/eye-diagnosis-0.0.1-SNAPSHOT.jar'
$mysql = 'iris-rc2-spec-mysql'
$env:SPRING_PROFILES_ACTIVE = 'ci'
$env:SPRING_DATASOURCE_USERNAME = 'eyeuser'
$env:SPRING_DATASOURCE_PASSWORD = 'ci-password'
$env:SPRING_DATA_REDIS_PORT = (docker port iris-rc2-spec-redis 6379/tcp).Split(':')[-1]
$env:SPRING_AI_VECTORSTORE_CHROMA_CLIENT_PORT = (docker port iris-rc2-spec-chroma 8000/tcp).Split(':')[-1]
$env:SPRING_AI_VECTORSTORE_CHROMA_CLIENT_HOST = 'http://127.0.0.1'
$env:SPRING_AI_DASHSCOPE_BASE_URL = 'http://127.0.0.1:1'
$env:SPRING_AI_OPENAI_BASE_URL = 'http://127.0.0.1:1'
$env:SPRING_AUTOCONFIGURE_EXCLUDE = 'com.alibaba.cloud.ai.agent.studio.SaaStudioWebModuleAutoConfiguration'
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '0'
$mysqlPort = (docker port $mysql 3306/tcp).Split(':')[-1]
$oldSha = git -C $checkout rev-parse a0efaf46
$rcSha = git -C $checkout rev-parse HEAD
if ($rcSha -ne '03b14efd8f9bcc8d6326a104396d8b68a30d5e8b') { throw 'RC SHA mismatch' }
"oldSHA=$oldSha rcSHA=$rcSha"
"oldJarSHA256=$((Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash)"
$jarEntries = & (Join-Path $javaHome 'jar.exe') tf $jar
"oldJarMigrations=$($jarEntries.Where({ $_ -match 'db/migration/V\d+' }).Count) oldJarContainsV18=$([bool]($jarEntries -match 'db/migration/V18'))"
[xml]$xml = Get-Content -Raw (Join-Path $checkout 'target/surefire-reports/TEST-com.csust.eyediagnosis.agent.AgentBudgetExhaustionTest.xml')
$classPath = ($xml.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
# Use target/classes from the exact detached RC; the separate master leg uses its packaged executable JAR.
$classPath = (($classPath -split ';') | Where-Object { $_ -notmatch '[\\/]target[\\/]test-classes$' }) -join ';'
$classPath = $classPath.Replace('\','/')
$oldArgs = (Join-Path $audit 'old-master-app.args')
$newArgs = (Join-Path $audit 'candidate-app.args')
@('-Dfile.encoding=UTF-8','-Xmx450m','-jar',('"'+$jar.Replace('\','/')+'"')) | Set-Content -LiteralPath $oldArgs -Encoding utf8
@('-Dfile.encoding=UTF-8','-Xmx450m','-cp',('"'+$classPath+'"'),'com.csust.eyediagnosis.EyeDiagnosisApplication') | Set-Content -LiteralPath $newArgs -Encoding utf8
$nonce = [guid]::NewGuid().ToString('N').Substring(0,6)
$priorAccount = "rollback${nonce}before"
$afterAccount = "rollback${nonce}after"
$password = 'IrisLabSyntheticPwd-1'

function Register-Account([string]$base,[string]$account) {
    $registration = Invoke-RestMethod "$base/user/register" -Method Post -Headers @{ Origin = [uri]::new($base).GetLeftPart([UriPartial]::Authority) } -ContentType 'application/json' -Body (@{
        userAccount = $account
        userPassword = $password
        checkPassword = $password
    } | ConvertTo-Json) -TimeoutSec 12
    if ($registration.code -ne 0) { throw "Registration returned $($registration.code): $($registration.message)" }
    return $registration.data
}

function Login-Account([string]$base,[string]$account) {
    $login = Invoke-RestMethod "$base/user/login" -Method Post -Headers @{ Origin = [uri]::new($base).GetLeftPart([UriPartial]::Authority) } -ContentType 'application/json' -Body (@{
        userAccount = $account
        userPassword = $password
    } | ConvertTo-Json) -SessionVariable cookies -TimeoutSec 12
    if ($login.code -ne 0) { throw "Login returned $($login.code): $($login.message)" }
    $read = Invoke-RestMethod "$base/user/get/login" -WebSession $cookies -TimeoutSec 12
    if ($read.code -ne 0 -or $read.data.userAccount -ne $account) { throw "Authenticated read returned $($read.code)" }
    return $read.data.id
}

function Invoke-App([string]$stage,[string]$db,[string]$appArgs,[string]$config,[scriptblock]$checks) {
    $env:SPRING_CONFIG_ADDITIONAL_LOCATION = 'file:'+$config.Replace('\','/')
    $env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:$mysqlPort/$db" + '?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false'
    $env:SPRING_FLYWAY_SCHEMAS = $db
    $stdout = Join-Path $audit "$stage.log"
    $stderr = Join-Path $audit "$stage.err.log"
    $process = Start-Process -FilePath $java -ArgumentList "@$appArgs" -PassThru -WindowStyle Hidden -WorkingDirectory $audit -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    try {
        $port = $null
        for ($attempt = 0; $attempt -lt 120; $attempt++) {
            if ($process.HasExited) { Get-Content -Tail 45 $stdout; Get-Content -Tail 25 $stderr; throw "$stage exited $($process.ExitCode)" }
            $started = Select-String -Path $stdout -Pattern 'Tomcat started on port (\d+)' | Select-Object -Last 1
            if ($started) { $port = $started.Matches[0].Groups[1].Value; break }
            Start-Sleep -Milliseconds 500
        }
        if (-not $port) { Get-Content -Tail 45 $stdout; throw "$stage did not boot" }
        $base = "http://127.0.0.1:$port/api"
        $apiVersion = Invoke-RestMethod "$base/v3/api-docs" -TimeoutSec 25
        if (-not $apiVersion.openapi) { throw "$stage OpenAPI smoke did not succeed" }
        $latest = docker exec $mysql mysql -N -ueyeuser -pci-password $db -e "SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;" 2>$null
        "$stage port=$port db=$db latestMigration=$latest openApi=$($apiVersion.openapi)"
        & $checks $base $latest
    } finally {
        if (-not $process.HasExited) { Stop-Process -Id $process.Id; $process.WaitForExit() }
    }
}
$oldConfig = Join-Path $old 'src/test/resources/application-ci.yml'
$newConfig = Join-Path $checkout 'src/test/resources/application-ci.yml'
Invoke-App 'spec-old-at-v17' 'iris_rc2_spec2' $oldArgs $oldConfig {
    param($base,$version)
    if ($version -ne '17') { throw 'Expected V17' }
    "old pre-upgrade register id=$(Register-Account $base $priorAccount)"
    "old pre-upgrade authenticated read id=$(Login-Account $base $priorAccount)"
}
$dump = Join-Path $audit 'spec-v17-synthetic.sql'
docker exec $mysql mysqldump -uroot -pci-root-password --single-transaction --routines --triggers iris_rc2_spec2 2>$null > $dump
if ($LASTEXITCODE -ne 0 -or (Get-Item $dump).Length -eq 0) { throw 'Dump failed' }
"snapshotBytes=$((Get-Item $dump).Length) snapshotSHA256=$((Get-FileHash $dump).Hash)"
Invoke-App 'spec-rc-upgrade-to-v28' 'iris_rc2_spec2' $newArgs $newConfig {
    param($base,$version)
    if ($version -ne '28') { throw 'Expected V28' }
    "rc at v28 authenticated read id=$(Login-Account $base $priorAccount)"
    "rc post-backup register id=$(Register-Account $base $afterAccount)"
}
Invoke-App 'spec-old-rollback-on-v28' 'iris_rc2_spec2' $oldArgs $oldConfig {
    param($base,$version)
    if ($version -ne '28') { throw 'Expected V28' }
    "old on v28 authenticated prior account id=$(Login-Account $base $priorAccount)"
    "old on v28 authenticated post-upgrade account id=$(Login-Account $base $afterAccount)"
    "old on v28 register id=$(Register-Account $base "rollback${nonce}new")"
}
docker exec $mysql mysql -uroot -pci-root-password -e "CREATE DATABASE iris_rc2_spec2_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL ON iris_rc2_spec2_restore.* TO 'eyeuser'@'%';" 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Restore DB bootstrap failed' }
docker cp $dump "${mysql}:/tmp/spec-v17-synthetic.sql"
if ($LASTEXITCODE -ne 0) { throw 'Copy failed' }
docker exec $mysql mysql -uroot -pci-root-password iris_rc2_spec2_restore -e "source /tmp/spec-v17-synthetic.sql;" 2>$null
if ($LASTEXITCODE -ne 0) { throw 'Restore failed' }
Invoke-App 'spec-old-restored-v17' 'iris_rc2_spec2_restore' $oldArgs $oldConfig {
    param($base,$version)
    if ($version -ne '17') { throw 'Expected restored V17' }
    "old on restored v17 authenticated prior account id=$(Login-Account $base $priorAccount)"
    $lostAccountRows = docker exec $mysql mysql -N -ueyeuser -pci-password iris_rc2_spec2_restore -e "SELECT COUNT(*) FROM t_user WHERE userAccount = '$afterAccount';" 2>$null
    if ($lostAccountRows -ne '0') { throw 'Unexpected post-backup account found' }
    "old on restored v17 post-backup account rows=$lostAccountRows"
}


