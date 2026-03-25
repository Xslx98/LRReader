# LANraragi API Verification Script v2
# Simplified — avoids PowerShell Invoke-WebRequest confirmation prompts

$BaseUrl = "http://192.168.6.180:3550"
$ApiKey = "KvMW2TgJ%mJ*evHH8D#8%QVPMiLp"

# Build Bearer token (Base64-encode the API key)
$TokenBytes = [System.Text.Encoding]::UTF8.GetBytes($ApiKey)
$Token = [Convert]::ToBase64String($TokenBytes)
$AuthHeader = @{ "Authorization" = "Bearer $Token" }

$ProgressPreference = 'SilentlyContinue'

$results = @()

function Log($msg) { Write-Output $msg }

function RunTest($name, $url, $useAuth) {
    Log "--- TEST: $name ---"
    Log "  URL: $url"
    try {
        $headers = if ($useAuth) { $AuthHeader } else { @{} }
        $resp = Invoke-RestMethod -Uri $url -Headers $headers -Method GET -TimeoutSec 10
        return $resp
    } catch {
        Log "  ERROR: $($_.Exception.Message)"
        return $null
    }
}

# TEST 1: Server Info (no auth)
$info = RunTest "Server Info (no auth)" "$BaseUrl/api/info" $false
if ($info) {
    Log "  name=$($info.name), version=$($info.version)"
    Log "  PASSED"
    $results += "PASS: Server Info (no auth) - $($info.name) v$($info.version)"
} else {
    $results += "FAIL: Server Info (no auth)"
}

# TEST 2: Server Info (with auth)  
$info2 = RunTest "Server Info (with auth)" "$BaseUrl/api/info" $true
if ($info2) {
    Log "  archives_per_page=$($info2.archives_per_page), has_password=$($info2.has_password)"
    Log "  PASSED"
    $results += "PASS: Server Info (auth) - per_page=$($info2.archives_per_page)"
} else {
    $results += "FAIL: Server Info (with auth)"
}

# TEST 3: Search
$search = RunTest "Search (no filter)" "$BaseUrl/api/search" $true
if ($search) {
    Log "  recordsTotal=$($search.recordsTotal), recordsFiltered=$($search.recordsFiltered), data.Count=$($search.data.Count)"
    Log "  PASSED"
    $results += "PASS: Search - total=$($search.recordsTotal), pageSize=$($search.data.Count)"
} else {
    $results += "FAIL: Search"
}

# TEST 4: First archive details
$firstArcid = $null
if ($search -and $search.data -and $search.data.Count -gt 0) {
    $first = $search.data[0]
    $firstArcid = $first.arcid
    Log ""
    Log "--- First archive from search ---"
    Log "  arcid=$($first.arcid)"
    Log "  title=$($first.title)"
    Log "  tags=$($first.tags)"
    Log "  pagecount=$($first.pagecount)"
    Log "  isnew=$($first.isnew)"
    Log "  extension=$($first.extension)"
    $results += "PASS: First archive - $($first.title)"
}

# TEST 5: Archive Metadata
if ($firstArcid) {
    $meta = RunTest "Archive Metadata" "$BaseUrl/api/archives/$firstArcid/metadata" $true
    if ($meta) {
        Log "  arcid=$($meta.arcid)"
        Log "  title=$($meta.title)"
        Log "  tags=$($meta.tags)"
        Log "  pagecount=$($meta.pagecount)"
        Log "  extension=$($meta.extension)"
        Log "  PASSED"
        $results += "PASS: Metadata - pages=$($meta.pagecount), ext=$($meta.extension)"
        
        # TEST 6: Tag Parsing
        Log ""
        Log "--- TEST: Tag Parsing ---"
        $rawTags = $meta.tags
        if ($rawTags) {
            $groups = [ordered]@{}
            foreach ($t in $rawTags.Split(",")) {
                $t = $t.Trim()
                if (-not $t) { continue }
                $ci = $t.IndexOf(":")
                if ($ci -gt 0) {
                    $ns = $t.Substring(0, $ci).Trim()
                    $val = $t.Substring($ci+1).Trim()
                } else {
                    $ns = "misc"
                    $val = $t
                }
                if (-not $groups.Contains($ns)) { $groups[$ns] = @() }
                $groups[$ns] += $val
            }
            foreach ($k in $groups.Keys) {
                Log "    [$k]: $($groups[$k] -join ', ')"
            }
            Log "  $($groups.Count) namespaces found"
            Log "  PASSED"
            $results += "PASS: Tag Parsing - $($groups.Count) namespaces"
        } else {
            Log "  No tags"
            $results += "SKIP: Tag Parsing - no tags"
        }
    } else {
        $results += "FAIL: Metadata"
    }
    
    # TEST 7: Thumbnail
    Log ""
    Log "--- TEST: Thumbnail ---"
    $thumbUrl = "$BaseUrl/api/archives/$firstArcid/thumbnail"
    Log "  URL: $thumbUrl"
    try {
        $thumbResp = Invoke-WebRequest -Uri $thumbUrl -Headers $AuthHeader -Method GET -TimeoutSec 15 -UseBasicParsing
        Log "  Status: $($thumbResp.StatusCode)"
        Log "  Content-Type: $($thumbResp.Headers['Content-Type'])"
        Log "  Size: $($thumbResp.RawContentLength) bytes"
        Log "  PASSED"
        $results += "PASS: Thumbnail - $($thumbResp.RawContentLength) bytes"
    } catch {
        Log "  ERROR: $($_.Exception.Message)"
        $results += "FAIL: Thumbnail - $($_.Exception.Message)"
    }
}

# TEST 8: Search with filter
$filtered = RunTest "Search (filter=test)" "$BaseUrl/api/search?filter=test" $true
if ($filtered) {
    Log "  recordsFiltered=$($filtered.recordsFiltered) / recordsTotal=$($filtered.recordsTotal)"
    Log "  PASSED"
    $results += "PASS: Filter search - filtered=$($filtered.recordsFiltered)/$($filtered.recordsTotal)"
} else {
    $results += "FAIL: Filter search"
}

# TEST 9: Categories
$cats = RunTest "Categories" "$BaseUrl/api/categories" $true
if ($cats) {
    Log "  Found $($cats.Count) categories"
    foreach ($c in $cats) {
        Log "    id=$($c.id), name=$($c.name), search=$($c.search)"
    }
    Log "  PASSED"
    $results += "PASS: Categories - $($cats.Count) found"
} else {
    $results += "FAIL: Categories"
}

# TEST 10: Pagination math
Log ""
Log "--- TEST: Pagination Math ---"
if ($search) {
    $pageSize = $search.data.Count
    $total = $search.recordsFiltered
    if ($pageSize -gt 0) {
        $totalPages = [Math]::Ceiling($total / $pageSize)
        $nextPage = if (1 -lt $totalPages) { 1 } else { 0 }
        Log "  LRR_PAGE_SIZE (actual from API): $pageSize"
        Log "  recordsFiltered: $total"
        Log "  totalPages: $totalPages"
        Log "  nextPage: $nextPage"
        
        # Check if our hardcoded page size matches
        $ourPageSize = 100
        if ($pageSize -ne $ourPageSize) {
            Log "  WARNING: API returns $pageSize archives per page, but our code uses $ourPageSize"
            Log "  We should update LRR_PAGE_SIZE in GalleryListHelper!"
            $results += "WARN: Page size mismatch - API=$pageSize, code=$ourPageSize"
        } else {
            Log "  Page size matches our hardcoded value"
            $results += "PASS: Page size - $pageSize"
        }
        Log "  PASSED"
        $results += "PASS: Pagination - pages=$totalPages, pageSize=$pageSize, total=$total"
    }
}

# TEST 11: Bridge simulation
Log ""
Log "--- TEST: GalleryInfo Bridge Simulation ---"
if ($firstArcid) {
    $gid = [Math]::Abs($firstArcid.GetHashCode()) -band 0x7FFFFFFF
    $thumbBridge = "$BaseUrl/api/archives/$firstArcid/thumbnail"
    Log "  arcid -> gid: $firstArcid -> $gid"
    Log "  thumb URL: $thumbBridge"
    Log "  category: 0"
    Log "  rating: -1.0"
    Log "  PASSED"
    $results += "PASS: Bridge - gid=$gid"
}

# SUMMARY
Log ""
Log "============================================"
Log "SUMMARY"
Log "============================================"
foreach ($r in $results) { Log "  $r" }
$passCount = ($results | Where-Object { $_ -match "^PASS" }).Count
$failCount = ($results | Where-Object { $_ -match "^FAIL" }).Count
$warnCount = ($results | Where-Object { $_ -match "^WARN" }).Count
Log ""
Log "Passed: $passCount | Failed: $failCount | Warnings: $warnCount"
