$root=(Get-Location).Path
$g=Get-Content (Join-Path $root "src\BookGui.java")
"=== buildUi: first-tab creation 107..114 ==="
for($i=106;$i -lt 115 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== menu/menubar region 139..152 ==="
for($i=138;$i -lt 153 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== doCreateSheet 279..287 ==="
for($i=278;$i -lt 288 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== loadWorkbook sheet loop 470..480 ==="
for($i=469;$i -lt 481 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }
"=== current() 209..211 ===="
for($i=208;$i -lt 212 -and $i -lt $g.Count;$i++){ "{0,4}: {1}" -f ($i+1),$g[$i] }