package com.github.avbox.osc.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

import java.io.Serializable

@Entity(tableName = "vodRecord")
class VodRecord : Serializable {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0

    @JvmField
    @ColumnInfo(name = "vodId")
    var vodId: String? = null

    @JvmField
    @ColumnInfo(name = "updateTime")
    var updateTime: Long = 0

    @JvmField
    @ColumnInfo(name = "sourceKey")
    var sourceKey: String? = null

    @JvmField
    @ColumnInfo(name = "cid")
    var cid: String? = null

    @JvmField
    var dataJson: String? = null
}
