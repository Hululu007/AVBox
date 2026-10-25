package com.github.avbox.osc.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

private const val VodFollowCreateSql = "CREATE TABLE IF NOT EXISTS `vodFollow` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `vodId` TEXT, `sourceKey` TEXT, `cid` TEXT, `name` TEXT, `pic` TEXT, `addedTime` INTEGER NOT NULL, `updateDays` TEXT NOT NULL, `updateHour` INTEGER NOT NULL)"

val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(VodFollowCreateSql)
    }
}
