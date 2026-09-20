package com.xnotes.core.util

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailCacheFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private fun cache(dir: File = folder.root, format: Int = 3) = ThumbnailCacheFiles(dir, format)

    @Test fun themeChangeDropsEveryDocumentAndThumbnailShape() {
        val cache = cache()
        val old = cache.useTheme("dark red")
        val keys = listOf("note", "note#page", "canvas", "canvas#page")
        keys.forEach { cache.store(it, old) { file -> file.writeText("old pixels") } }
        val current = cache.useTheme("light blue")
        assertNotEquals(old, current)
        for (key in keys) {
            assertNull(cache.load(key, current) { it.readText() })
            cache.store(key, old) { it.writeText("late old pixels") }
            assertNull(cache.load(key, current) { it.readText() })
        }
        cache.store("note", current) { it.writeText("new pixels") }
        assertEquals("new pixels", cache.load("note", current) { it.readText() })
    }

    @Test fun unchangedThemeKeepsThumbnailsAcrossPanes() {
        val first = cache()
        val generation = first.useTheme("dark")
        first.store("note", generation) { it.writeText("pixels") }
        val second = cache()
        assertEquals(generation, second.useTheme("dark"))
        assertEquals("pixels", second.load("note", generation) { it.readText() })
        assertEquals(generation, first.useTheme("dark"))
    }

    @Test fun previousLaunchThemeIsCheckedBeforeLoadingDiskCache() {
        val original = cache()
        val generation = original.useTheme("dark")
        original.store("canvas", generation) { it.writeText("pixels") }
        val restarted = folder.newFolder("restarted")
        folder.root.listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(restarted, it.name)) }
        val cache = cache(restarted)
        val current = cache.useTheme("light")
        assertNull(cache.load("canvas", current) { it.readText() })
        assertEquals(listOf("format"), restarted.listFiles()!!.map { it.name })
    }

    @Test fun previousLaunchWithSameThemeKeepsDiskCache() {
        val original = cache()
        val generation = original.useTheme("dark")
        original.store("canvas", generation) { it.writeText("pixels") }
        val restarted = folder.newFolder("restarted")
        folder.root.listFiles()!!.filter { it.isFile }.forEach { it.copyTo(File(restarted, it.name)) }
        val cache = cache(restarted)
        val current = cache.useTheme("dark")
        assertEquals("pixels", cache.load("canvas", current) { it.readText() })
    }

    @Test fun anotherPaneCannotPublishAnOldRenderEvenAfterReturningToSameTheme() {
        val first = cache()
        val second = cache()
        val old = first.useTheme("dark")
        second.useTheme("light")
        val current = second.useTheme("dark")
        assertFalse(first.isCurrent(old))
        first.store("note", old) { fail("Old generation must not invoke the writer") }
        assertNull(second.load("note", current) { it.readText() })
    }

    @Test fun renderFinishingAfterThemeChangeCannotRepopulateCache() {
        val cache = cache()
        val old = cache.useTheme("dark")
        val rendering = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val done = CountDownLatch(1)
        val worker = thread {
            rendering.countDown()
            finish.await(5, TimeUnit.SECONDS)
            cache.store("canvas", old) { it.writeText("stale") }
            done.countDown()
        }
        try {
            assertTrue(rendering.await(5, TimeUnit.SECONDS))
            val current = cache.useTheme("light")
            cache.store("canvas", current) { it.writeText("fresh") }
            finish.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals("fresh", cache.load("canvas", current) { it.readText() })
        } finally {
            finish.countDown()
            worker.join(5000)
        }
    }

    @Test fun legacyFormatIsDiscardedAndContentInvalidationStillWorks() {
        File(folder.root, "format").writeText("2")
        File(folder.root, "legacy.png").writeText("old")
        val cache = cache()
        val current = cache.useTheme("dark")
        assertFalse(File(folder.root, "legacy.png").exists())
        cache.store("note", current) { it.writeText("pixels") }
        cache.remove("note")
        assertNull(cache.load("note", current) { it.readText() })
        cache.store("note#page", current) { it.writeText("pixels") }
        cache.prune(emptySet())
        assertNull(cache.load("note#page", current) { it.readText() })
        assertTrue(cache.isCurrent(current))
    }
}
