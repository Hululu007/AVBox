package com.github.avbox.osc.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey

import java.io.Serializable

@Entity(tableName = "cache")
class CacheEntry : Serializable {
    @PrimaryKey(autoGenerate = false)
    @JvmField
    var key: String = ""

    @JvmField
    var data: ByteArray? = null
}
