package org.fossify.gallery.faces

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Poloha fotky (z EXIF GPS). has_geo=false = fotka bola spracovaná, ale nemá GPS (nečítať znova).
@Entity(tableName = "geo")
data class GeoEntity(
    @PrimaryKey @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lon") val lon: Double,
    @ColumnInfo(name = "has_geo") val hasGeo: Boolean,
)
