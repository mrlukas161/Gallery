package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Záznam o vykonanom importe (proti dvojitému naimportovaniu tej istej sady).
@Entity(tableName = "import_manifests")
data class ImportManifestEntity(
    @PrimaryKey @ColumnInfo(name = "manifest_hash") val manifestHash: String,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "person_count") val personCount: Int,
)
