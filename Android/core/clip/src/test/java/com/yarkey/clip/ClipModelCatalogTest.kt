package com.yarkey.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipModelCatalogTest {
    @Test
    fun `model ids are unique and resolve to their definitions`() {
        val definitions = ClipModelCatalog.definitions
        val ids = definitions.map { it.model.id }

        assertEquals(ids.size, ids.toSet().size)
        definitions.forEach { definition ->
            assertEquals(definition, ClipModelCatalog.definition(definition.model.id))
        }
    }

    @Test
    fun `catalog exposes RN50 and ViT-B-16 FP32 models`() {
        val rn50 = ClipModelCatalog.definition("chinese-clip-rn50-fp32-v1")
        val vit = ClipModelCatalog.definition("chinese-clip-vit-b-16-fp32-v1")

        assertNotNull(rn50)
        assertNotNull(vit)
        assertTrue(rn50!!.imageModelAsset.endsWith("rn50.img.fp32.onnx"))
        assertTrue(vit!!.imageModelAsset.endsWith("vit-b-16.img.fp32.onnx"))
        assertEquals("image", vit.imageInputName)
        assertEquals("text", vit.textInputName)
    }
}
