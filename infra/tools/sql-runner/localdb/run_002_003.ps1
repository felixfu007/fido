$ErrorActionPreference = 'Stop'
& "$PSScriptRoot\Invoke-SqlBatchFile.ps1" `
    -ConnectionString "Server=(localdb)\MSSQLLocalDB;Database=FidoServerDb;Integrated Security=True;Connect Timeout=30;" `
    -SqlFiles @(
        "D:\fido\infra\sql\002_create_tables.sql",
        "D:\fido\infra\sql\003_create_indexes.sql"
    )
