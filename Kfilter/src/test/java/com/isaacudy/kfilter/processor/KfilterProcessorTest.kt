package com.isaacudy.kfilter.processor

import com.isaacudy.kfilter.MediaType
import org.junit.Assert
import org.junit.Test
import java.util.*

class KfilterProcessorTest {

    @Test
    fun testSetExtension_noExtensionIn_imageExtensionOut() {
        // Arrange
        val mediaType = MediaType.IMAGE
        val savePath = "/storage/emulated/0/example/fileName"

        // Act
        val finalPath = getSavePathWithExtension(mediaType, savePath)

        // Assert
        Assert.assertTrue(finalPath == savePath + IMAGE_EXTENSION)
    }

    @Test
    fun testSetExtension_noExtensionIn_videoExtensionOut() {
        // Arrange
        val mediaType = MediaType.VIDEO
        val savePath = "/storage/emulated/0/example/fileName"

        // Act
        val finalPath = getSavePathWithExtension(mediaType, savePath)

        // Assert
        Assert.assertTrue(finalPath == savePath + VIDEO_EXTENSION)
    }

    @Test
    fun testSetExtension_noExtensionIn_noExtensionOut() {
        // Arrange
        val mediaType = MediaType.NONE
        val savePath = "/storage/emulated/0/example/fileName"

        // Act
        val finalPath = getSavePathWithExtension(mediaType, savePath)

        // Assert
        Assert.assertTrue(finalPath == savePath)
    }

    @Test
    fun testSetExtension_imageExtensionIn_noExtensionOut() {
        // Arrange
        val mediaType = MediaType.NONE
        val savePath = "/storage/emulated/0/example/fileName" + IMAGE_EXTENSION
        val expectedOutPath = "/storage/emulated/0/example/fileName"

        // Act
        val finalPath = getSavePathWithExtension(mediaType, savePath)

        // Assert
        Assert.assertTrue(finalPath == expectedOutPath)
    }

    @Test
    fun testSetExtension_videoExtensionIn_noExtensionOut() {
        // Arrange
        val mediaType = MediaType.NONE
        val savePath = "/storage/emulated/0/example/fileName" + VIDEO_EXTENSION
        val expectedOutPath = "/storage/emulated/0/example/fileName"

        // Act
        val finalPath = getSavePathWithExtension(mediaType, savePath)

        // Assert
        Assert.assertTrue(finalPath == expectedOutPath)
    }

    @Test
    fun testSetExtension_videoExtensionIn_imageExtensionOut() {
        // Arrange
        val mediaType = MediaType.IMAGE
        val savePath = "/storage/emulated/0/example/fileName" + VIDEO_EXTENSION
        val expectedOutPath = "/storage/emulated/0/example/fileName" + IMAGE_EXTENSION

        // Act
        val finalPath = getSavePathWithExtension(mediaType, savePath)

        // Assert
        Assert.assertTrue(finalPath == expectedOutPath)
    }

    @Test
    fun testSetExtension_imageExtensionIn_videoExtensionOut() {
        // Arrange
        val mediaType = MediaType.VIDEO
        val savePath = "/storage/emulated/0/example/fileName" + IMAGE_EXTENSION
        val expectedOutPath = "/storage/emulated/0/example/fileName" + VIDEO_EXTENSION

        // Act
        val finalPath = getSavePathWithExtension(mediaType, savePath)

        // Assert
        Assert.assertTrue(finalPath == expectedOutPath)
    }

    @Test
    fun testSetExtension_randomExtensionIn_imageExtensionOut() {
        // Arrange
        val mediaType = MediaType.IMAGE
        val savePath = "/storage/emulated/0/example/fileName" + "." + UUID.randomUUID().toString()
        val expectedOutPath = "/storage/emulated/0/example/fileName" + IMAGE_EXTENSION

        // Act
        val finalPath = getSavePathWithExtension(mediaType, savePath)

        // Assert
        Assert.assertTrue(finalPath == expectedOutPath)
    }

    @Test
    fun testSetExtension_noExtensionIn_imageExtensionOut_windowsPathSep() {
        // Arrange
        val mediaType = MediaType.IMAGE
        val savePath = "\\storage\\emulated\\0\\example\\fileName"
        val expectedOutPath = "\\storage\\emulated\\0\\example\\fileName" + IMAGE_EXTENSION

        // Act
        val finalPath = getSavePathWithExtension(mediaType, savePath)

        // Assert
        Assert.assertTrue(finalPath == expectedOutPath)
    }

    @Test
    fun testSetExtension_noExtensionIn_imageExtensionOut_pathWithDots() {
        // Arrange
        val mediaType = MediaType.IMAGE
        val savePath = "/storage/emula.ted/0/exa.mple/fileName"
        val expectedOutPath = "/storage/emula.ted/0/exa.mple/fileName" + IMAGE_EXTENSION

        // Act
        val finalPath = getSavePathWithExtension(mediaType, savePath)

        // Assert
        Assert.assertTrue(finalPath == expectedOutPath)
    }
}