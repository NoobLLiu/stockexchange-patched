param(
    [string]$OutputJson = "data\item_database.json"
)

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$outputJson = Join-Path $projectRoot $OutputJson

# Find the Excel file (should be the first .xlsx in the project root)
$excelPath = Get-ChildItem -Path $projectRoot -Filter "*.xlsx" -File | Select-Object -First 1 -ExpandProperty FullName
if (-not $excelPath) {
    Write-Host "ERROR: No .xlsx file found in $projectRoot"
    exit 1
}

Write-Host "Excel: $excelPath"
Write-Host "Output: $outputJson"

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($excelPath)

# Read shared strings
$ssEntry = $zip.Entries | Where-Object { $_.FullName -eq "xl/sharedStrings.xml" } | Select-Object -First 1
$reader = New-Object System.IO.StreamReader($ssEntry.Open())
$ssXml = New-Object System.Xml.XmlDocument
$ssXml.LoadXml($reader.ReadToEnd())
$reader.Close()

$ns = New-Object System.Xml.XmlNamespaceManager($ssXml.NameTable)
$ns.AddNamespace("s", "http://schemas.openxmlformats.org/spreadsheetml/2006/main")

# Get all shared string values indexed by position
$stringNodes = $ssXml.SelectNodes("//s:si", $ns)
$sharedStrings = New-Object System.Collections.Generic.List[System.String]
for ($i = 0; $i -lt $stringNodes.Count; $i++) {
    $tNode = $stringNodes[$i].SelectSingleNode("s:t", $ns)
    if ($tNode -ne $null) {
        $sharedStrings.Add($tNode.InnerText)
    } else {
        # Check for rich text (multiple <r> elements with <t> inside)
        $textParts = $stringNodes[$i].SelectNodes("s:r/s:t", $ns)
        if ($textParts.Count -gt 0) {
            $combined = ""
            foreach ($part in $textParts) {
                $combined += $part.InnerText
            }
            $sharedStrings.Add($combined)
        } else {
            $sharedStrings.Add("")
        }
    }
}
Write-Host "Loaded $($sharedStrings.Count) shared strings"

# Read sheet1 to get cell data
$sheetEntry = $zip.Entries | Where-Object { $_.FullName -eq "xl/worksheets/sheet1.xml" } | Select-Object -First 1
$reader = New-Object System.IO.StreamReader($sheetEntry.Open())
$sheetXml = New-Object System.Xml.XmlDocument
$sheetXml.LoadXml($reader.ReadToEnd())
$reader.Close()
$zip.Dispose()

$ns2 = New-Object System.Xml.XmlNamespaceManager($sheetXml.NameTable)
$ns2.AddNamespace("s", "http://schemas.openxmlformats.org/spreadsheetml/2006/main")
$ns2.AddNamespace("r", "http://schemas.openxmlformats.org/officeDocument/2006/relationships")

# Parse rows
$rows = $sheetXml.SelectNodes("//s:sheetData/s:row", $ns2)
Write-Host "Found $($rows.Count) rows"

$items = @()
$skipRow1 = $true  # Skip header row

foreach ($row in $rows) {
    if ($skipRow1) {
        $skipRow1 = $false
        continue
    }
    
    $cells = $row.SelectNodes("s:c", $ns2)
    $chineseName = ""
    $itemId = ""
    
    foreach ($cell in $cells) {
        $cellRef = $cell.Attributes["r"].Value  # e.g., "A2", "B2"
        $colLetter = $cellRef -replace '[0-9]',''
        $typeAttr = $cell.Attributes["t"]  # "s" = shared string
        $valueNode = $cell.SelectSingleNode("s:v", $ns2)
        $value = if ($valueNode -ne $null) { $valueNode.InnerText } else { "" }
        
        if ($typeAttr -ne $null -and $typeAttr.Value -eq "s" -and $value -ne "") {
            $intVal = [int]::Parse($value)
            if ($intVal -ge 0 -and $intVal -lt $sharedStrings.Count) {
                $resolved = $sharedStrings[$intVal]
                if ($colLetter -eq "A") {
                    $chineseName = $resolved
                } elseif ($colLetter -eq "B") {
                    $itemId = $resolved
                }
            }
        } elseif ($typeAttr -eq $null -and $value -ne "") {
            if ($colLetter -eq "B") {
                $itemId = $value
            } elseif ($colLetter -eq "A") {
                $chineseName = $value
            }
        }
    }
    
    if ($chineseName -ne "" -or $itemId -ne "") {
        # Clean the Chinese name - remove [新增：...] markers, normalize spaces
        $cleanName = $chineseName -replace '\[.*?\]', '' -replace '\s+$', '' -replace '^\s+', ''
        if ($cleanName -ne "" -and $itemId -ne "") {
            $items += @{
                name = $cleanName
                id = $itemId
            }
        }
    }
}

Write-Host "Extracted $($items.Count) items"

# Ensure output directory exists
$outDir = Split-Path $outputJson -Parent
if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
}

# Convert to JSON (pretty-print)
$jsonString = ConvertTo-Json -InputObject $items
Set-Content -Path $outputJson -Value $jsonString -Encoding UTF8

Write-Host "Done! JSON file written to: $outputJson"
Write-Host "Sample items:"
$items | Select-Object -First 10 | Format-Table -AutoSize
