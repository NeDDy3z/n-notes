package com.xnotes.core.tools

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.ConnectorEnd
import com.xnotes.core.model.Rgba
import com.xnotes.core.shapes.ConnectorPresets
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.shapes.ShapePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A connector chip is a connector, not a shape: whatever draws it, the pen or the shape tool, it
 * must start a connector rather than fall back to the rectangle the shape registry would offer.
 */
class ShapeConnectorGuardTest {

    private val stroke = 3.0

    @Test fun everyUmlChipResolvesToAConnector() {
        val uml = ConnectorPresets.pages.first { it.key == "uml" }.presets
        assertTrue("the UML page should not be empty", uml.isNotEmpty())
        for (chip in uml) {
            val cfg = ShapeConfig().choose(chip)
            assertEquals("chip ${chip.key} must stay a connector", chip.key, cfg.connector?.key)
            assertNotNull("chip ${chip.key} must start a connector", cfg.startConnector(ConnectorEnd(Pt.ZERO), Rgba(0, 0, 0), stroke))
        }
    }

    @Test fun aConnectorChipIsNotAShapeChip() {
        val cfg = ShapeConfig().choose(ConnectorPresets.byKey("uml_inheritance")!!)
        // A shape drawn from a connector key would fall back to the rectangle, which is the bug.
        assertNull(ShapePresets.byKey(cfg.preset))
        assertFalse("a connector key names no shape kind", ShapePresets.byKey(cfg.preset) != null)
    }

    @Test fun anOrdinaryShapeChipIsStillAShape() {
        val cfg = ShapeConfig().choose(ShapePresets.byKey("rectangle")!!)
        assertNull(cfg.connector)
        assertEquals(ShapeKind.RECTANGLE, cfg.shape)
        assertNotNull(cfg.startShape(Pt.ZERO, Rgba(0, 0, 0), stroke))
    }
}
