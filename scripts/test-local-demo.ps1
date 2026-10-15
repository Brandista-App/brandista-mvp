$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$api = 'http://127.0.0.1:8081/api/v1'
$password = [IO.File]::ReadAllText((Join-Path (Get-Location) 'target/launch-demo-password.local'))
function CallApi($method, $path, $headers = @{}, $body = $null) {
    $request = @{ Method=$method; Uri="$api$path"; Headers=$headers; TimeoutSec=15 }
    if ($null -ne $body) { $request.ContentType='application/json'; $request.Body=($body | ConvertTo-Json -Depth 8) }
    Invoke-RestMethod @request
}
function Assert($condition, $message) { if (!$condition) { throw $message } }
$customer = CallApi POST '/auth/login' @{} @{email='customer@demo.brandista.test';password=$password}
$admin = CallApi POST '/auth/login' @{} @{email='admin@demo.brandista.test';password=$password}
$customerHeaders = @{Authorization="Bearer $($customer.accessToken)"}
$adminHeaders = @{Authorization="Bearer $($admin.accessToken)"}
$forbidden = Invoke-WebRequest "$api/admin/brands" -Headers $customerHeaders -SkipHttpErrorCheck
Assert ($forbidden.StatusCode -eq 403) 'Customer accessed admin route'
$unauthorized = Invoke-WebRequest "$api/customers/me/cart" -SkipHttpErrorCheck
Assert ($unauthorized.StatusCode -eq 401) 'Anonymous accessed cart'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$application = CallApi POST '/brand-applications' @{} @{brandName="SAMPLE QA $stamp";contactName='Synthetic operator';contactEmail="qa-$stamp@example.test"}
$review = CallApi PATCH "/admin/brand-applications/$($application.id)/review" $adminHeaders @{decision='APPROVED';reviewNote='Isolated H2 smoke test; fictional brand'}
Assert ($review.status -eq 'APPROVED') 'Approval did not persist'
$allBrands = CallApi GET '/admin/brands' $adminHeaders
$brand = $allBrands | Where-Object name -EQ "SAMPLE QA $stamp"
Assert ($brand.status -eq 'INACTIVE') 'Approval must create an inactive brand'
$brand = CallApi PUT "/admin/brands/$($brand.id)" $adminHeaders @{name=$brand.name;slug=$brand.slug;description='Synthetic test brand; not a partner';status='ACTIVE'}
$category = (CallApi GET '/categories').content[0]
$product = CallApi POST '/admin/products' $adminHeaders @{name="SAMPLE QA Product $stamp";slug="sample-qa-$stamp";description='Synthetic API smoke';brandId=$brand.id;categoryId=$category.id;price=1234.50;availability='AVAILABLE'}
CallApi PATCH "/admin/products/$($product.id)/publish" $adminHeaders | Out-Null
$public = CallApi GET "/products/$($product.slug)"
Assert ($public.price -eq 1234.50) 'Published price differs'
$cart = CallApi POST '/customers/me/cart/items' $customerHeaders @{productId=$product.id;quantity=2}
$line = $cart.items | Where-Object productId -EQ $product.id
Assert ($line.lineSubtotal -eq 2469.00) 'Server line subtotal incorrect'
$reread = CallApi GET '/customers/me/cart' $customerHeaders
Assert (@($reread.items | Where-Object productId -EQ $product.id).Count -eq 1) 'Cart did not persist'
@{status='PASS';database='isolated file H2 only';anonymousCart=401;customerAdmin=403;brandApproval='PASS';activation='PASS';productPublication='PASS';cartPersistence='PASS';lineSubtotal=$line.lineSubtotal;productId=$product.id;timestamp=[DateTimeOffset]::Now.ToString('o')} | ConvertTo-Json
