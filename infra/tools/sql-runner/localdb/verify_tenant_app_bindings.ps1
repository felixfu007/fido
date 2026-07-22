$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName 'System.Data'

$connStr = "Server=(localdb)\MSSQLLocalDB;Database=FidoServerDb;Integrated Security=True;Connect Timeout=30;"
$conn = New-Object System.Data.SqlClient.SqlConnection
$conn.ConnectionString = $connStr
$conn.Open()

function Run-Query {
    param([string]$Title, [string]$Sql)
    Write-Host ""
    Write-Host "===== $Title ====="
    $cmd = $conn.CreateCommand()
    $cmd.CommandText = $Sql
    $reader = $cmd.ExecuteReader()
    $cols = @()
    for ($i = 0; $i -lt $reader.FieldCount; $i++) { $cols += $reader.GetName($i) }
    Write-Host ($cols -join " | ")
    $rowCount = 0
    while ($reader.Read()) {
        $vals = @()
        for ($i = 0; $i -lt $reader.FieldCount; $i++) {
            $v = $reader.GetValue($i)
            if ($v -eq [System.DBNull]::Value) { $v = "NULL" }
            $vals += [string]$v
        }
        Write-Host ($vals -join " | ")
        $rowCount++
    }
    $reader.Close()
    Write-Host "-- row count: $rowCount --"
}

# 0. all 7 tables present
Run-Query -Title "All tables in dbo schema" -Sql @"
SELECT t.name AS table_name
FROM sys.tables t
WHERE t.schema_id = SCHEMA_ID('dbo')
ORDER BY t.name;
"@

# 1. tenant_app_bindings columns
Run-Query -Title "tenant_app_bindings columns" -Sql @"
SELECT c.name AS column_name, ty.name AS data_type, c.max_length, c.is_nullable
FROM sys.columns c
JOIN sys.types ty ON c.user_type_id = ty.user_type_id
WHERE c.object_id = OBJECT_ID('dbo.tenant_app_bindings')
ORDER BY c.column_id;
"@

# 2. PK
Run-Query -Title "tenant_app_bindings PRIMARY KEY" -Sql @"
SELECT kc.name AS constraint_name, kc.type_desc, c.name AS column_name
FROM sys.key_constraints kc
JOIN sys.index_columns ic ON ic.object_id = kc.parent_object_id AND ic.index_id = kc.unique_index_id
JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
WHERE kc.parent_object_id = OBJECT_ID('dbo.tenant_app_bindings') AND kc.type = 'PK';
"@

# 3. UNIQUE constraints
Run-Query -Title "tenant_app_bindings UNIQUE constraints" -Sql @"
SELECT kc.name AS constraint_name, c.name AS column_name, ic.key_ordinal
FROM sys.key_constraints kc
JOIN sys.index_columns ic ON ic.object_id = kc.parent_object_id AND ic.index_id = kc.unique_index_id
JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
WHERE kc.parent_object_id = OBJECT_ID('dbo.tenant_app_bindings') AND kc.type = 'UQ'
ORDER BY kc.name, ic.key_ordinal;
"@

# 4. CHECK constraints
Run-Query -Title "tenant_app_bindings CHECK constraints" -Sql @"
SELECT name AS constraint_name, definition
FROM sys.check_constraints
WHERE parent_object_id = OBJECT_ID('dbo.tenant_app_bindings');
"@

# 5. FK
Run-Query -Title "tenant_app_bindings FOREIGN KEY" -Sql @"
SELECT
    fk.name AS fk_name,
    OBJECT_NAME(fk.parent_object_id) AS parent_table,
    pc.name AS parent_column,
    OBJECT_NAME(fk.referenced_object_id) AS ref_table,
    rc.name AS ref_column
FROM sys.foreign_keys fk
JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
JOIN sys.columns pc ON pc.object_id = fkc.parent_object_id AND pc.column_id = fkc.parent_column_id
JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
WHERE fk.parent_object_id = OBJECT_ID('dbo.tenant_app_bindings');
"@

# 6. Index
Run-Query -Title "tenant_app_bindings indexes" -Sql @"
SELECT i.name AS index_name, i.type_desc, i.is_unique,
    STUFF((SELECT ', ' + c.name
           FROM sys.index_columns ic
           JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
           WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id
           ORDER BY ic.key_ordinal
           FOR XML PATH('')), 1, 2, '') AS columns
FROM sys.indexes i
WHERE i.object_id = OBJECT_ID('dbo.tenant_app_bindings') AND i.name IS NOT NULL
ORDER BY i.name;
"@

# 7. Specific index from 003
Run-Query -Title "IX_appbind_tenant_status exists?" -Sql @"
SELECT name, type_desc, is_unique
FROM sys.indexes
WHERE object_id = OBJECT_ID('dbo.tenant_app_bindings') AND name = 'IX_appbind_tenant_status';
"@

$conn.Close()
Write-Host ""
Write-Host "Verification complete."
