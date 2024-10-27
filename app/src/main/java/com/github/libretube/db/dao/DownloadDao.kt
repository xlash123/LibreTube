package com.github.libretube.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.github.libretube.db.obj.Download
import com.github.libretube.db.obj.DownloadChapter
import com.github.libretube.db.obj.DownloadItem
import com.github.libretube.db.obj.DownloadWithItems
import com.github.libretube.enums.FileType

@Dao
interface DownloadDao {
    @Transaction
    @Query("SELECT * FROM download")
    suspend fun getAll(): List<DownloadWithItems>

    @Transaction
    @Query("SELECT * FROM download WHERE videoId = :videoId")
    suspend fun findById(videoId: String): DownloadWithItems?

    @Query("SELECT videoId FROM downloadItem WHERE type = :fileType ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomVideoIdByFileType(fileType: FileType): String?

    @Query("SELECT * FROM downloaditem WHERE id = :id")
    suspend fun findDownloadItemById(id: Int): DownloadItem?

    @Query("DELETE FROM downloaditem WHERE id = :id")
    suspend fun deleteDownloadItemById(id: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDownload(download: Download)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDownloadChapter(downloadChapter: DownloadChapter)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadItem(downloadItem: DownloadItem): Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateDownloadItem(downloadItem: DownloadItem)

    @Transaction
    @Delete
    suspend fun deleteDownload(download: Download)
}
