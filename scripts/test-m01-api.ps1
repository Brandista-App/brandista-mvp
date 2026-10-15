param([switch]$AfterRestart)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$api='http://127.0.0.1:8082/api/v1'
function Api($method,$path,$token=$null,$body=$null) {
    $request=@{Method=$method;Uri="$api$path";TimeoutSec=20}
    if($token){$request.Headers=@{Authorization="Bearer $token"}}
    if($null -ne $body){$request.ContentType='application/json';$request.Body=$body|ConvertTo-Json -Depth 8}
    Invoke-RestMethod @request
}
function Assert($condition,$message){if(!$condition){throw $message}}
$sessionFile='target/m01-api-session.local.json'
if($AfterRestart){
    $session=Get-Content $sessionFile -Raw|ConvertFrom-Json
    $login=Api POST '/auth/login' $null @{email=$session.email;password=$session.password}
    $cart=Api GET '/customers/me/cart' $login.accessToken
    Assert ($cart.items.Count -eq 2 -and $cart.subtotal -eq 6250.40) 'Persisted multi-brand cart differs after restart'
    @{result='PASS';check='MySQL cart/customer survived application restart';customerId=$session.customerId;subtotal=$cart.subtotal;itemCount=$cart.items.Count}|ConvertTo-Json
    exit
}
$stamp=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$email="m01-api-$stamp@example.test"
$customer=Api POST '/registrations' $null @{email=$email;password=$password}
$login=Api POST '/auth/login' $null @{email=$email;password=$password}
Assert ($login.account.role -eq 'CUSTOMER') 'Public registration granted non-customer role'
$secondPassword=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$second=Api POST '/registrations' $null @{email="m01-other-$stamp@example.test";password=$secondPassword}
$otherLogin=Api POST '/auth/login' $null @{email="m01-other-$stamp@example.test";password=$secondPassword}
$shirt=Api GET '/products/demo-m01-linen-shirt'
$tote=Api GET '/products/demo-m01-canvas-tote'
Assert ($shirt.brandId -ne $tote.brandId) 'Catalogue must contain different brands'
$cart=Api POST '/customers/me/cart/items' $login.accessToken @{productId=$shirt.id;quantity=1}
$shirtLine=$cart.items|Where-Object productId -EQ $shirt.id
Api PATCH "/customers/me/cart/items/$($shirtLine.itemId)" $login.accessToken @{quantity=2}|Out-Null
$cart=Api POST '/customers/me/cart/items' $login.accessToken @{productId=$tote.id;quantity=1}
Assert ($cart.items.Count -eq 2 -and $cart.subtotal -eq 6250.40) 'Multi-brand subtotal should be PKR 6250.40'
$denied=Invoke-WebRequest "$api/customers/me/cart/items/$($shirtLine.itemId)" -Method Patch -Headers @{Authorization="Bearer $($otherLogin.accessToken)"} -ContentType application/json -Body '{"quantity":99}' -SkipHttpErrorCheck
Assert ($denied.StatusCode -eq 404) 'Another customer can mutate the cart'
$deniedDelete=Invoke-WebRequest "$api/customers/me/cart/items/$($shirtLine.itemId)" -Method Delete -Headers @{Authorization="Bearer $($otherLogin.accessToken)"} -SkipHttpErrorCheck
Assert ($deniedDelete.StatusCode -eq 404) 'Another customer can remove the cart item'
$anonymous=Invoke-WebRequest "$api/customers/me/cart" -SkipHttpErrorCheck
$admin=Invoke-WebRequest "$api/admin/brands" -Headers @{Authorization="Bearer $($login.accessToken)"} -SkipHttpErrorCheck
Assert ($anonymous.StatusCode -eq 401 -and $admin.StatusCode -eq 403) 'Route role guards failed'
$invalid=Invoke-WebRequest "$api/customers/me/cart/items/$($shirtLine.itemId)" -Method Patch -Headers @{Authorization="Bearer $($login.accessToken)"} -ContentType application/json -Body '{"quantity":0}' -SkipHttpErrorCheck
Assert ($invalid.StatusCode -eq 400) 'Invalid cart quantity was accepted'
$reread=Api GET '/customers/me/cart' $login.accessToken
Assert ($reread.subtotal -eq 6250.40) 'Denied/invalid mutations changed the cart'
# Only generated synthetic test credentials; ignored local file, never printed.
@{email=$email;password=$password;customerId=$customer.id}|ConvertTo-Json|Set-Content $sessionFile
@{result='PASS';database='MySQL brandista_m01';customerId=$customer.id;secondCustomerId=$second.id;role=$login.account.role;multiBrandItems=$cart.items.Count;subtotal=$cart.subtotal;crossCustomerPatch=$denied.StatusCode;crossCustomerDelete=$deniedDelete.StatusCode;anonymousCart=$anonymous.StatusCode;customerAdmin=$admin.StatusCode;invalidQuantity=$invalid.StatusCode;checkout='BLOCKED: not implemented'}|ConvertTo-Json
