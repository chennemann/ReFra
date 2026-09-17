package com.dot.gallery

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dot.gallery.feature_node.data.data_source.InternalDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClientCertificateMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), InternalDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun existingAccountsStartWithoutClientCertificates() {
        helper.createDatabase("client-certificate-migration", 45).use { db ->
            db.execSQL("""
                INSERT INTO cloud_server_config
                    (id, providerType, serverUrl, displayName, isActive, lastConnected,
                     syncEnabled, wifiOnly, syncIntervalMinutes, syncFolders)
                VALUES (7, 'NEXTCLOUD', 'https://example.com', 'Photos', 1, 0, 1, 1, 360, '')
            """.trimIndent())
        }
        helper.runMigrationsAndValidate("client-certificate-migration", 46, true).use { db ->
            db.query("SELECT serverUrl, clientCertificates FROM cloud_server_config WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("https://example.com", cursor.getString(0))
                assertEquals("{}", cursor.getString(1))
            }
        }
    }
}
