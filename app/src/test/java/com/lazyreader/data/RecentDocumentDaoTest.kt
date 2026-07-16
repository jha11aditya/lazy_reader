package com.lazyreader.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentDocumentDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: RecentDocumentDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.recentDocumentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun document(uri: String, lastOpenedAt: Long = 0L, currentPage: Int = 0) = RecentDocument(
        uri = uri,
        displayName = "Doc $uri",
        totalPages = 10,
        currentPage = currentPage,
        lastOpenedAt = lastOpenedAt,
        type = RecentDocument.TYPE_PDF,
    )

    @Test
    fun `upsert then getByUri round trips`() = runTest {
        dao.upsert(document("uri-1"))

        val loaded = dao.getByUri("uri-1")

        assertEquals("Doc uri-1", loaded?.displayName)
    }

    @Test
    fun `getByUri returns null when absent`() = runTest {
        assertNull(dao.getByUri("missing"))
    }

    @Test
    fun `upsert with existing uri replaces the row`() = runTest {
        dao.upsert(document("uri-1", currentPage = 1))
        dao.upsert(document("uri-1", currentPage = 5))

        assertEquals(1, dao.observeAll().first().size)
        assertEquals(5, dao.getByUri("uri-1")?.currentPage)
    }

    @Test
    fun `observeAll orders by lastOpenedAt descending`() = runTest {
        dao.upsert(document("older", lastOpenedAt = 100))
        dao.upsert(document("newer", lastOpenedAt = 200))

        val all = dao.observeAll().first()

        assertEquals(listOf("newer", "older"), all.map { it.uri })
    }

    @Test
    fun `updateProgress only touches page and lastOpenedAt`() = runTest {
        dao.upsert(document("uri-1", lastOpenedAt = 100, currentPage = 0))

        dao.updateProgress("uri-1", page = 7, openedAt = 999)

        val updated = dao.getByUri("uri-1")
        assertEquals(7, updated?.currentPage)
        assertEquals(999L, updated?.lastOpenedAt)
        assertEquals("Doc uri-1", updated?.displayName)
    }

    @Test
    fun `deleteByUri removes the row`() = runTest {
        dao.upsert(document("uri-1"))

        dao.deleteByUri("uri-1")

        assertNull(dao.getByUri("uri-1"))
    }
}
