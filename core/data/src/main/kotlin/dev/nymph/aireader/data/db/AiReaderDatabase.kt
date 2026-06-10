package dev.nymph.aireader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class, SentenceEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AiReaderDatabase : RoomDatabase() {

    abstract fun documentDao(): DocumentDao

    abstract fun sentenceDao(): SentenceDao

    companion object {
        const val NAME: String = "aireader.db"

        fun build(context: Context): AiReaderDatabase =
            Room.databaseBuilder(context.applicationContext, AiReaderDatabase::class.java, NAME)
                .build()
    }
}
