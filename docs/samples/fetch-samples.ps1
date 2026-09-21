<#
.SYNOPSIS
    Coleta respostas reais da API de consulta do PNCP e salva em docs/samples/.

.DESCRIPTION
    Faz cinco chamadas escolhidas por eixo de variacao, nao por familia de endpoint:
      1. contratacoes-proposta   -> lista de contratacoes com proposta aberta (o coracao do produto)
      2. contratacoes-publicacao -> lista por data de publicacao (envelope de paginacao)
      3. contratacao-detalhe     -> objeto completo, derivado do primeiro item das listas
      4. caso-vazio              -> consulta sem resultados (204 vs. lista vazia)
      5. caso-erro               -> parametro obrigatorio ausente (formato de erro)

    Para cada chamada grava dois arquivos em -OutDir:
      <nome>.headers.txt  URL, status HTTP e headers de resposta
      <nome>.json         corpo, formatado e cortado em -MaxRegistros registros

    Os arquivos sao gravados em UTF-8 SEM BOM de proposito: eles viram fixtures do
    WireMock na etapa 3, e um BOM no inicio do arquivo faz o Jackson falhar ao parsear.

.PARAMETER Modalidades
    Codigos de modalidade de contratacao. O default (6 e 8) e um palpite razoavel para
    Pregao Eletronico e Dispensa Eletronica, mas CONFIRME os codigos no Swagger ou no
    manual do PNCP antes de confiar. Codigo errado costuma devolver 204, nao erro.

.EXAMPLE
    .\fetch-samples.ps1 -Uf SP -Modalidades 6,8

.EXAMPLE
    .\fetch-samples.ps1 -Uf MG -Modalidades 6 -DataInicial 20260801 -DataFinal 20260831 -KeepFull

.NOTES
    Os nomes exatos dos parametros de query do PNCP (dataInicial, dataFinal,
    codigoModalidadeContratacao, uf, pagina) e o formato de data yyyyMMdd precisam ser
    confirmados no "Try it out" do Swagger. Se algo vier 400, o corpo do erro salvo em
    caso-erro.json normalmente diz qual parametro falta.
#>
[CmdletBinding()]
param(
    [string]   $Uf           = 'SP',
    [int[]]    $Modalidades  = @(6, 8),
    [string]   $DataInicial  = (Get-Date).AddDays(-30).ToString('yyyyMMdd'),
    [string]   $DataFinal    = (Get-Date).ToString('yyyyMMdd'),
    [string]   $OutDir       = '',
    [int]      $MaxRegistros = 3,
    [int]      $TamanhoPagina = 10,
    [int]      $TimeoutSeg   = 120,
    [switch]   $KeepFull
)

$ErrorActionPreference = 'Stop'

$BaseUrl     = 'https://pncp.gov.br/api/consulta'
$isModernPs  = $PSVersionTable.PSVersion.Major -ge 6
$utf8NoBom   = New-Object System.Text.UTF8Encoding($false)

# Windows PowerShell 5.1 ainda negocia TLS 1.0 por default em algumas maquinas.
if (-not $isModernPs) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
}

# ---------------------------------------------------------------- helpers

function Write-TextFile {
    param([string]$Path, [string]$Content)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Read-ResponseBody {
    param($Response)

    if ($null -ne $Response.RawContentStream -and $Response.RawContentStream.Length -gt 0) {
        $Response.RawContentStream.Position = 0
        $bytes = New-Object byte[] $Response.RawContentStream.Length
        [void]$Response.RawContentStream.Read($bytes, 0, $bytes.Length)
        return [System.Text.Encoding]::UTF8.GetString($bytes)
    }
    if ($null -ne $Response.Content) {
        if ($Response.Content -is [byte[]]) {
            return [System.Text.Encoding]::UTF8.GetString($Response.Content)
        }
        return [string]$Response.Content
    }
    return ''
}

function Invoke-Pncp {
    param([string]$Url)

    $callArgs = @{
        Uri             = $Url
        Method          = 'Get'
        Headers         = @{ 'Accept' = 'application/json'; 'User-Agent' = 'radar-pncp-samples/1.0' }
        TimeoutSec      = $TimeoutSeg
        UseBasicParsing = $true
    }
    # PS 7+ devolve 4xx/5xx normalmente; 5.1 lanca excecao e o corpo vem pela exception.
    if ($isModernPs) { $callArgs['SkipHttpErrorCheck'] = $true }

    try {
        $r = Invoke-WebRequest @callArgs
        $headers = @{}
        foreach ($k in $r.Headers.Keys) { $headers[$k] = ($r.Headers[$k] -join ', ') }
        return [pscustomobject]@{
            Status  = [int]$r.StatusCode
            Headers = $headers
            Body    = (Read-ResponseBody $r)
        }
    }
    catch {
        $resp = $null
        if ($_.Exception.PSObject.Properties.Name -contains 'Response') { $resp = $_.Exception.Response }

        # Timeout, DNS ou conexao recusada nao tem resposta HTTP nenhuma.
        if ($null -eq $resp) {
            return [pscustomobject]@{
                Status  = 0
                Headers = @{}
                Body    = ''
                Falha   = $_.Exception.Message
            }
        }

        $body = ''
        try {
            $stream = $resp.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
            $body   = $reader.ReadToEnd()
            $reader.Dispose()
        } catch { }

        $headers = @{}
        try { foreach ($k in $resp.Headers.AllKeys) { $headers[$k] = $resp.Headers[$k] } } catch { }

        return [pscustomobject]@{
            Status  = [int]$resp.StatusCode
            Headers = $headers
            Body    = $body
        }
    }
}

function Get-TrimmedJson {
    param([string]$Body, [int]$Max)

    if ([string]::IsNullOrWhiteSpace($Body)) { return $null }

    try { $obj = $Body | ConvertFrom-Json } catch { return $null }

    if ($obj -is [System.Array]) {
        if ($obj.Count -gt $Max) { $obj = @($obj[0..($Max - 1)]) }
    }
    elseif ($null -ne $obj -and $obj.PSObject) {
        foreach ($name in @('data', 'items', 'resultado', 'content', 'registros')) {
            if ($obj.PSObject.Properties.Name -contains $name) {
                if ($obj.$name -is [System.Array] -and $obj.$name.Count -gt $Max) {
                    $obj.$name = @($obj.$name[0..($Max - 1)])
                }
                break
            }
        }
    }

    return ($obj | ConvertTo-Json -Depth 40)
}

function Resolve-Field {
    param($Object, [string[]]$Paths)

    foreach ($path in $Paths) {
        $cursor = $Object
        $ok     = $true
        foreach ($segment in $path.Split('.')) {
            if ($null -eq $cursor -or -not $cursor.PSObject -or
                ($cursor.PSObject.Properties.Name -notcontains $segment)) { $ok = $false; break }
            $cursor = $cursor.$segment
        }
        if ($ok -and $null -ne $cursor -and "$cursor".Trim() -ne '') { return $cursor }
    }
    return $null
}

function Get-FirstRecord {
    param([string]$Body)

    if ([string]::IsNullOrWhiteSpace($Body)) { return $null }
    try { $obj = $Body | ConvertFrom-Json } catch { return $null }

    if ($obj -is [System.Array]) { if ($obj.Count -gt 0) { return $obj[0] } else { return $null } }

    foreach ($name in @('data', 'items', 'resultado', 'content', 'registros')) {
        if ($obj.PSObject.Properties.Name -contains $name -and $obj.$name -is [System.Array] -and $obj.$name.Count -gt 0) {
            return $obj.$name[0]
        }
    }
    return $null
}

function Save-Sample {
    param(
        [string]$Name,
        [string]$Url,
        [string]$Nota = ''
    )

    Write-Host ''
    Write-Host "-> $Name" -ForegroundColor Cyan
    Write-Host "   GET $Url" -ForegroundColor DarkGray

    $res = Invoke-Pncp -Url $Url

    if ($res.Status -eq 0) {
        Write-Host "   FALHOU: $($res.Falha)" -ForegroundColor Red
        Write-Host '   sem resposta HTTP (timeout ou rede). Seguindo para a proxima.' -ForegroundColor DarkYellow
        Write-TextFile (Join-Path $OutDir "$Name.headers.txt") @"
# $Name
# FALHA: sem resposta HTTP
fetched-at: $((Get-Date).ToString('o'))
request:    GET $Url
erro:       $($res.Falha)
"@
        return $res
    }

    $color = if ($res.Status -ge 200 -and $res.Status -lt 300) { 'Green' } else { 'Yellow' }
    $bytes = if ($res.Body) { $res.Body.Length } else { 0 }
    Write-Host "   HTTP $($res.Status)  ($bytes chars)" -ForegroundColor $color

    # ---- headers
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# $Name")
    if ($Nota) { $lines.Add("# $Nota") }
    $lines.Add("fetched-at: $((Get-Date).ToString('o'))")
    $lines.Add("request:    GET $Url")
    $lines.Add("status:     $($res.Status)")
    $lines.Add('')
    foreach ($k in ($res.Headers.Keys | Sort-Object)) { $lines.Add("$k`: $($res.Headers[$k])") }
    Write-TextFile (Join-Path $OutDir "$Name.headers.txt") ($lines -join "`r`n")

    # ---- corpo
    $jsonPath = Join-Path $OutDir "$Name.json"

    if ($res.Status -eq 204 -or [string]::IsNullOrWhiteSpace($res.Body)) {
        Write-Host '   sem corpo (204 / vazio) - nenhum .json gravado' -ForegroundColor DarkYellow
        Write-Host '   ATENCAO: seu codigo precisa tratar 204 antes de parsear.' -ForegroundColor DarkYellow
        if (Test-Path $jsonPath) { Remove-Item $jsonPath }
        return $res
    }

    $trimmed = Get-TrimmedJson -Body $res.Body -Max $MaxRegistros
    if ($null -eq $trimmed) {
        Write-Host '   corpo nao e JSON valido - salvando como recebido' -ForegroundColor DarkYellow
        Write-TextFile $jsonPath $res.Body
    }
    else {
        Write-TextFile $jsonPath $trimmed
        Write-Host "   -> $Name.json" -ForegroundColor DarkGray
    }

    if ($KeepFull) {
        Write-TextFile (Join-Path $OutDir "$Name.full.json") $res.Body
    }

    return $res
}

# ---------------------------------------------------------------- execucao

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    # $PSScriptRoot pode vir vazio no default do param em PS 5.1 chamado via -File.
    $OutDir = if (-not [string]::IsNullOrWhiteSpace($PSScriptRoot)) { $PSScriptRoot }
              elseif ($MyInvocation.MyCommand.Path) { Split-Path -Parent $MyInvocation.MyCommand.Path }
              else { (Get-Location).Path }
}

if (-not (Test-Path -LiteralPath $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
}
$OutDir = (Resolve-Path -LiteralPath $OutDir).Path

$modalidade = $Modalidades[0]

Write-Host ''
Write-Host '=== amostras da API de consulta do PNCP ===' -ForegroundColor White
Write-Host "destino:    $OutDir"
Write-Host "uf:         $Uf"
Write-Host "modalidade: $modalidade  (das informadas: $($Modalidades -join ', '))"
Write-Host "periodo:    $DataInicial a $DataFinal"
Write-Host "max regs:   $MaxRegistros por lista (tamanhoPagina=$TamanhoPagina, timeout=${TimeoutSeg}s)"

# 1. proposta aberta
$proposta = Save-Sample `
    -Name 'contratacoes-proposta' `
    -Url  "$BaseUrl/v1/contratacoes/proposta?dataFinal=$DataFinal&codigoModalidadeContratacao=$modalidade&uf=$Uf&pagina=1&tamanhoPagina=$TamanhoPagina" `
    -Nota 'Contratacoes com recebimento de propostas aberto. Base do produto.'

# 2. lista por publicacao
$publicacao = Save-Sample `
    -Name 'contratacoes-publicacao' `
    -Url  "$BaseUrl/v1/contratacoes/publicacao?dataInicial=$DataInicial&dataFinal=$DataFinal&codigoModalidadeContratacao=$modalidade&uf=$Uf&pagina=1&tamanhoPagina=$TamanhoPagina" `
    -Nota 'Lista por data de publicacao. Revela o envelope de paginacao.'

# 3. detalhe, derivado do primeiro registro que tiver aparecido
$first = Get-FirstRecord -Body $proposta.Body
if ($null -eq $first) { $first = Get-FirstRecord -Body $publicacao.Body }

if ($null -eq $first) {
    Write-Host ''
    Write-Host '-> contratacao-detalhe   PULADO' -ForegroundColor Yellow
    Write-Host '   nenhuma lista devolveu registros; ajuste -Uf, -Modalidades ou o periodo.' -ForegroundColor Yellow
}
else {
    $cnpj = Resolve-Field $first @('orgaoEntidade.cnpj', 'orgaoCnpj', 'cnpjOrgao', 'orgaoEntidade.cnpjOrgao')
    $ano  = Resolve-Field $first @('anoCompra', 'ano', 'anoContratacao')
    $seq  = Resolve-Field $first @('sequencialCompra', 'sequencial', 'numeroSequencial')

    if ($null -eq $cnpj -or $null -eq $ano -or $null -eq $seq) {
        Write-Host ''
        Write-Host '-> contratacao-detalhe   PULADO' -ForegroundColor Yellow
        Write-Host '   nao achei cnpj/ano/sequencial no primeiro registro com os nomes esperados.' -ForegroundColor Yellow
        Write-Host '   abra contratacoes-proposta.json, veja como os campos se chamam e monte a URL na mao:' -ForegroundColor Yellow
        Write-Host "   $BaseUrl/v1/orgaos/{cnpj}/compras/{ano}/{sequencial}" -ForegroundColor DarkGray
    }
    else {
        $cnpjLimpo = ("$cnpj" -replace '[^\d]', '')
        Save-Sample `
            -Name 'contratacao-detalhe' `
            -Url  "$BaseUrl/v1/orgaos/$cnpjLimpo/compras/$ano/$seq" `
            -Nota "Detalhe derivado do primeiro registro da lista (cnpj=$cnpjLimpo ano=$ano seq=$seq)." | Out-Null
    }
}

# 4. caso vazio: janela no futuro
$amanha = (Get-Date).AddDays(1).ToString('yyyyMMdd')
Save-Sample `
    -Name 'caso-vazio' `
    -Url  "$BaseUrl/v1/contratacoes/publicacao?dataInicial=$amanha&dataFinal=$amanha&codigoModalidadeContratacao=$modalidade&uf=$Uf&pagina=1&tamanhoPagina=$TamanhoPagina" `
    -Nota 'Consulta sem resultados. Observe se vem 204 sem corpo ou 200 com lista vazia.' | Out-Null

# 5. caso de erro: sem parametro obrigatorio
Save-Sample `
    -Name 'caso-erro' `
    -Url  "$BaseUrl/v1/contratacoes/publicacao?dataInicial=$DataInicial&dataFinal=$DataFinal&uf=$Uf&pagina=1&tamanhoPagina=$TamanhoPagina" `
    -Nota 'Chamada sem codigoModalidadeContratacao de proposito. Formato de erro da API.' | Out-Null

# ---------------------------------------------------------------- resumo

Write-Host ''
Write-Host '=== arquivos em docs/samples ===' -ForegroundColor White
Get-ChildItem -Path $OutDir -File |
    Where-Object { $_.Extension -in @('.json', '.txt') } |
    Sort-Object Name |
    Format-Table Name, @{ Name = 'KB'; Expression = { [math]::Round($_.Length / 1KB, 1) } } -AutoSize

Write-Host 'Proximos passos:' -ForegroundColor White
Write-Host '  1. Abra cada .json e confira se os campos batem com o modelo da etapa 2.'
Write-Host '  2. Veja em caso-vazio.headers.txt se a API responde 204 ou 200 com lista vazia.'
Write-Host '  3. Confirme no detalhe se o edital (PDF) vem por aqui ou nao. Isso decide a etapa 5.'
Write-Host '  4. git add docs/samples && commit. Estes arquivos sao fixtures, nao lixo.'
Write-Host ''