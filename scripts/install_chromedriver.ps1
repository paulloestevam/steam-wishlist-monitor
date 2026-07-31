<#
Downloads the latest ChromeDriver for major version 150 and extracts it to a target folder.

Usage (PowerShell):
  .\install_chromedriver.ps1 -TargetDir "C:\Users\paull\chromedriver"

This script will:
  - Query https://chromedriver.storage.googleapis.com/LATEST_RELEASE_150 to get the latest 150.x version
  - Download the corresponding chromedriver_win32.zip
  - Extract and overwrite chromedriver.exe into the target directory
#>

param(
    [string]$TargetDir = "C:\Users\paull\chromedriver",
    [switch]$Force
)

try {
    Write-Host "Checking latest ChromeDriver version for major 150..."
    $ver = Invoke-RestMethod "https://chromedriver.storage.googleapis.com/LATEST_RELEASE_150"
    Write-Host "Latest ChromeDriver for major 150: $ver"

    $zipName = "chromedriver_win32.zip"
    $uri = "https://chromedriver.storage.googleapis.com/$ver/$zipName"

    New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null

    $tmpZip = Join-Path $env:TEMP "chromedriver_$ver.zip"
    if (Test-Path $tmpZip) { Remove-Item $tmpZip -Force }

    Write-Host "Downloading $uri ..."
    Invoke-WebRequest -Uri $uri -OutFile $tmpZip

    Write-Host "Extracting to $TargetDir ..."
    Expand-Archive -LiteralPath $tmpZip -DestinationPath $TargetDir -Force

    # The zip contains chromedriver.exe at root. Ensure the file exists
    $exe = Join-Path $TargetDir 'chromedriver.exe'
    if (Test-Path $exe) {
        Write-Host "chromedriver.exe installed at: $exe"
    } else {
        Write-Error "Extraction did not produce chromedriver.exe. Check archive contents."
        exit 1
    }

    Write-Host "Cleaning up temporary files..."
    Remove-Item $tmpZip -Force

    Write-Host "Done. If your application points to this path, restart your app."
} catch {
    Write-Error "Failed: $_"
    exit 1
}

