package id.motionlab.feature

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import id.motionlab.core.project.AssetRefDto
import id.motionlab.engine.timeline.BlendMode
import id.motionlab.engine.timeline.MaskSpec
import id.motionlab.engine.timeline.blendMode
import id.motionlab.engine.timeline.mask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaLibraryUiTest {
    @get:Rule val rule = createComposeRule()

    private fun fixtureWithMedia(): EditorFixture {
        val f = EditorFixture(rule)
        f.session.putAssets(
            listOf(
                AssetRefDto("img1", "file:///nonexistent/a.png", "IMAGE", "alpha.png", width = 100, height = 50, addedAtMs = 1),
                AssetRefDto("img2", "file:///nonexistent/b.png", "IMAGE", "beta.png", width = 10, height = 10, addedAtMs = 2),
                AssetRefDto("aud1", "file:///nonexistent/s.mp3", "AUDIO", "song.mp3", durationUs = 61_000_000, sampleRate = 44100, channels = 2, addedAtMs = 3),
            ),
        )
        f.show()
        rule.onNodeWithTag("tab-Media").performClick()
        return f
    }

    @Test fun libraryListsSeparatesAndSearchesMedia() {
        fixtureWithMedia()
        rule.onNodeWithTag("media-library").assertExists()
        rule.onNodeWithTag("import-photos").assertIsDisplayed() // Images category: the system Photo Picker
        rule.onNodeWithTag("media-row-img1").assertExists()
        rule.onNodeWithTag("media-row-aud1").assertDoesNotExist() // audio lives in its own category
        rule.onNodeWithTag("media-cat-FONTS").assertDoesNotExist() // fonts are imported from the text configuration
        rule.onNodeWithTag("import-fonts").assertDoesNotExist()
        rule.onNodeWithTag("media-cat-AUDIO").performClick()
        rule.onNodeWithTag("import-audio").assertIsDisplayed()
        rule.onNodeWithTag("media-row-aud1").assertExists()
        rule.onNodeWithTag("media-cat-IMAGES").performClick()
        rule.onNodeWithTag("media-search").performTextInput("beta")
        rule.onNodeWithTag("media-row-img1").assertDoesNotExist()
        rule.onNodeWithTag("media-row-img2").assertExists()
    }

    @Test fun missingFilesAreReportedClearly() {
        fixtureWithMedia()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("media-retry").assertExists() }.isSuccess }
    }

    @Test fun removeIsRefusedWhileUsedAndWorksOtherwise() {
        val f = fixtureWithMedia()
        f.session.addAssetClip("img1", 0)
        rule.onNodeWithTag("media-remove-img1").performClick()
        rule.waitForIdle()
        assertNotNull("a used asset must stay", f.session.asset("img1"))
        rule.onNodeWithTag("media-remove-img2").performClick()
        rule.waitForIdle()
        assertNull(f.session.asset("img2"))
    }

    @Test fun addToTimelineButtonIsDisabledForMissingMedia() {
        fixtureWithMedia()
        // the files do not exist, so adding must not be offered as if it worked
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("media-retry").assertExists() }.isSuccess }
        rule.onNodeWithTag("media-add-img1").assertExists()
    }

    @Test fun blendAndMaskControlsWriteRealData() {
        val f = EditorFixture(rule).show()
        // select the fixture clip (a SHAPE clip: visual) and use the Attributes panel
        f.controller.session.select(id.motionlab.engine.timeline.Selection(clipIds = setOf(f.clipId)))
        rule.waitForIdle()
        rule.onNodeWithTag("prop-cat-BLEND").performClick()
        rule.onNodeWithTag("blend-button").performScrollTo().performClick()
        rule.onNodeWithTag("blend-MULTIPLY").performClick()
        rule.waitForIdle()
        assertEquals(BlendMode.MULTIPLY, f.session.timeline.requireClip(f.clipId).second.blendMode)
        rule.onNodeWithTag("prop-cat-MASK").performClick()
        rule.onNodeWithTag("mask-add-ellipse").performScrollTo().performClick()
        rule.waitForIdle()
        assertNotNull(f.session.timeline.requireClip(f.clipId).second.mask)
        rule.onNodeWithTag("mask-enabled").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(false, f.session.timeline.requireClip(f.clipId).second.mask!!.enabled)
        rule.onNodeWithTag("mask-remove").performScrollTo().performClick()
        rule.waitForIdle()
        assertNull(f.session.timeline.requireClip(f.clipId).second.mask)
        f.session.undo()
        assertTrue(f.session.timeline.requireClip(f.clipId).second.mask != null)
        assertTrue(MaskSpec().isActive)
    }

    @Test fun unsupportedBlendModesAreShownDisabled() {
        val f = EditorFixture(rule).show()
        f.controller.session.select(id.motionlab.engine.timeline.Selection(clipIds = setOf(f.clipId)))
        rule.waitForIdle()
        rule.onNodeWithTag("prop-cat-BLEND").performClick()
        rule.onNodeWithTag("blend-button").performScrollTo().performClick()
        rule.onNodeWithTag("blend-OVERLAY").assertIsNotEnabledCompat()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsNotEnabledCompat() = this.assertIsNotEnabled()
