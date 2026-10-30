package com.z30lab.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Kotlin port of the iOS CodecTests, run against the same binary fixtures. */
class CodecTests {
    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun np3(name: String): ByteArray = resource("$name.NP3")
    private fun bin(name: String): ByteArray = resource("$name.bin")

    private fun encodedNP3(records: Map<Long, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(78, 67, 80, 0))
        for ((tag, bytes) in records.toSortedMap()) {
            putBig32(out, tag)
            putBig32(out, bytes.size.toLong())
            out.write(bytes)
        }
        putBig32(out, 0L)
        return out.toByteArray()
    }

    private fun putBig32(out: java.io.ByteArrayOutputStream, value: Long) {
        for (shift in 24 downTo 0 step 8) out.write(((value shr shift) and 0xFF).toInt())
    }

    @Test
    fun testBatchImportKeepsBothFilesAndSkipsDuplicates() {
        val root = File.createTempFile("z30", null).let { it.delete(); it.mkdir(); it }
        try {
            val srcDir = File(root, "src").also { it.mkdir() }
            val folder = File(root, "library").also { it.mkdir() }
            val first = File(srcDir, "a.NCP").also { it.writeBytes(resource("neutral-curve.NCP")) }
            val second = File(srcDir, "b.NCP").also { it.writeBytes(resource("mono-curve.NCP")) }
            val originals = listOf(first.readBytes(), second.readBytes())
            val result = RecipeLibraryImporter.importFiles(listOf(first, second, first), folder)
            assertEquals(2, result.importedIDs.size)
            assertEquals(1, result.duplicateIDs.size)
            assertTrue(result.failures.isEmpty())
            val stored = result.importedIDs.map { File(folder, it).readBytes() }
            assertArrayEquals(originals[0], stored[0])
            assertArrayEquals(originals[1], stored[1])
            val repeated = RecipeLibraryImporter.importFiles(listOf(second, first), folder)
            assertTrue(repeated.importedIDs.isEmpty())
            assertEquals(2, repeated.duplicateIDs.size)
            assertEquals(2, folder.listFiles()!!.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun testBatchImportReportsBadFileAndStillImportsFollowingValidFile() {
        val root = File.createTempFile("z30", null).let { it.delete(); it.mkdir(); it }
        try {
            val bad = File(root, "broken.NCP").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val valid = File(root, "valid.NP3").also { it.writeBytes(np3("standard")) }
            val result = RecipeLibraryImporter.importFiles(listOf(bad, valid), File(root, "Library"))
            assertEquals(1, result.importedIDs.size)
            assertEquals(1, result.failures.size)
            assertTrue(result.failures[0].contains("broken.NCP"))
            val stored = File(File(root, "Library"), result.importedIDs[0]).readBytes()
            assertArrayEquals(valid.readBytes(), stored)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun testZeroStateFlagRetainsSourceAndCurve() {
        val source = resource("neutral-state-zero.NCP")
        val recipe = ClassicNcpRecipe.parse(source)
        assertEquals("FIXTURE-COLOR", recipe.name)
        assertEquals(0, recipe.settings[26].toInt())
        assertArrayEquals(source, recipe.original)
        assertNull(recipe.transferIssue)
        val payload = recipe.wirePayload()
        assertEquals(614, payload.size)
        assertEquals(8, payload[25].toInt())
        assertArrayEquals(source.slice(56, 578), payload.slice(36, 578))
        assertEquals(6, recipe.curve!![8].toInt())
        val fullControl = source.clone()
        fullControl[38] = 2.toByte()
        assertArrayEquals(payload, PhoneRecipeDocument.parse(fullControl).verifiedPayload())
        val invalid = source.clone()
        invalid[38] = 255.toByte()
        try {
            ClassicNcpRecipe.parse(invalid)
            fail("Expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun testSyntheticNCPRetainsCurvePointsAndEveryLUTEntry() {
        val source = resource("neutral-curve.NCP")
        val recipe = ClassicNcpRecipe.parse(source)
        assertArrayEquals(source, recipe.original)
        assertEquals("NEUTRAL-TEST", recipe.name)
        assertEquals(0x03C2, recipe.base)
        val curve = recipe.curve!!
        assertEquals(578, curve.size)
        assertEquals(3, curve[8].toInt())
        assertArrayEquals(byteArrayOf(0, 0, 82, 82, 255.toByte(), 255.toByte()), curve.slice(9, 6))
        assertArrayEquals(source.slice(120, 514), curve.slice(64, 514))
        assertEquals(0, curve[64].toInt())
        assertEquals(0, curve[65].toInt())
        val document = PhoneRecipeDocument.parse(source)
        assertEquals("NCP", document.fileExtension)
        assertNull(document.transferIssue)
        assertArrayEquals(bin("neutral-payload"), document.verifiedPayload())
        try {
            StandardNp3Recipe.parse(source)
            fail("Expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun testNCPRejectsTruncations() {
        val source = resource("neutral-curve.NCP")
        for (count in 0 until source.size) {
            try {
                ClassicNcpRecipe.parse(source.copyOfRange(0, count))
                fail("Expected failure for length $count")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun testNCPAndNP3FixturesProduceIdenticalCompleteDataset() {
        val ncp = PhoneRecipeDocument.parse(resource("neutral-curve.NCP")).verifiedPayload()
        val exported = PhoneRecipeDocument.parse(np3("neutral-export")).verifiedPayload()
        assertArrayEquals(ncp, exported)
        assertArrayEquals(ncp, bin("neutral-payload"))
        for (index in ncp.indices) {
            if (index == 23) continue
            val wrong = ncp.clone()
            wrong[index] = (wrong[index].toInt() xor 1).toByte()
            try {
                RecipeReadback.verify(ncp, wrong, wrong)
                fail("Expected verification failure at $index")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun testMonochromeNCPIsNotBlockedByNeutralWhitelist() {
        val source = resource("mono-curve.NCP")
        val recipe = PhoneRecipeDocument.parse(source)
        val payload = recipe.verifiedPayload()
        assertEquals("FIXTURE-MONO", recipe.name)
        assertNull(recipe.transferIssue)
        assertEquals(4, payload[0].toInt())
        assertEquals(614, payload.size)
        assertEquals(12, payload[25].toInt())
        assertEquals(0, payload[32].toInt())
        assertEquals(0, payload[33].toInt())
        assertArrayEquals(Np3Document.parse(source).records[2L], payload.slice(36, 578))
        RecipeReadback.verify(payload, payload, payload)
    }

    @Test
    fun testAllSixTraditionalNCPBasesCanProduceWirelessData() {
        val color = Np3Document.parse(resource("neutral-curve.NCP")).records
        for ((base, wire) in ClassicNcpRecipe.wireBaseIDs) {
            // Deep copy: Swift dictionary+Data copies values; Kotlin HashMap shares ByteArray references.
            val records = HashMap<Long, ByteArray>()
            for ((k, v) in color) records[k] = v.clone()
            records[1L]!![24] = (base shr 8).toByte()
            records[1L]!![25] = base.toByte()
            if (wire == 4) {
                records[1L]!![31] = 255.toByte()
                records[1L]!![32] = 255.toByte()
                records[1L]!![33] = 128.toByte()
                records[1L]!![34] = 128.toByte()
                records[1L]!![35] = 255.toByte()
            }
            val document = PhoneRecipeDocument.parse(encodedNP3(records))
            assertNull(document.transferIssue)
            assertEquals(wire, document.verifiedPayload()[0].toInt())
            assertArrayEquals(color[2L], document.verifiedPayload().slice(36, 578))
        }
    }

    @Test
    fun testNeutralNP3RejectsMissingCurveAndSupportsLUTOnly() {
        var records = Np3Document.parse(np3("neutral-export")).records.toMutableMap()
        records.remove(2L)
        try {
            NeutralNp3Recipe.parse(encodedNP3(records))
            fail("Expected failure")
        } catch (_: Exception) {
        }
        records = Np3Document.parse(np3("neutral-export")).records.toMutableMap()
        records[2L]!![8] = 0
        for (i in 9 until 64) records[2L]!![i] = 0
        assertEquals(614, NeutralNp3Recipe.parse(encodedNP3(records)).payload.size)
        records[2L]!![64] = 128.toByte()
        try {
            NeutralNp3Recipe.parse(encodedNP3(records))
            fail("Expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun testFlexibleColorCloudFileHasExplicitCompatibilityError() {
        val records = Np3Document.parse(np3("standard")).records.toMutableMap()
        records[0x100L] = "0310".toByteArray(Charsets.US_ASCII)
        records[0x300L] = byteArrayOf(0, 32)
        try {
            PhoneRecipeDocument.parse(encodedNP3(records))
            fail("Flexible Color must be rejected")
        } catch (e: CameraError.Invalid) {
            assertTrue(e.message!!.contains("灵活色彩"))
        }
    }

    @Test
    fun testReadbackAllowsOnlyTheTwoObservedFlags() {
        val source = StandardNp3Recipe.parse(np3("standard")).payload
        val observed = source.clone()
        observed[23] = 1
        assertTrue(RecipeReadback.verify(source, observed, observed).cameraFlagNormalized)
        for (index in source.indices) {
            if (index == 23) continue
            val wrong = observed.clone()
            wrong[index] = (wrong[index].toInt() xor 1).toByte()
            try {
                RecipeReadback.verify(source, wrong, wrong)
                fail("Expected failure at $index")
            } catch (_: Exception) {
            }
        }
        val bad = observed.clone()
        bad[23] = 2
        try {
            RecipeReadback.verify(source, bad, bad)
            fail("Expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun testNP3ConversionMatchesCameraImportedPattern() {
        val recipe = StandardNp3Recipe.parse(np3("parameter-map"))
        assertEquals("Z30LAB-MAP", recipe.name)
        assertArrayEquals(bin("parameter-map"), recipe.payload)
        C9RecipePayload(recipe.payload)
        assertEquals(252, recipe.payload[30].u())
        assertEquals(1, recipe.payload[31].u())
    }

    @Test
    fun testCameraExportedStandardNP3MatchesBodyDataExceptLoaderFlag() {
        val recipe = StandardNp3Recipe.parse(np3("standard"))
        val bodyData = bin("standard")
        assertEquals(1, bodyData[23].toInt())
        bodyData[23] = 0
        assertEquals("STANDARD-02", recipe.name)
        assertArrayEquals(bodyData, recipe.payload)
    }

    @Test
    fun testNP3AllTruncationsDuplicateRecordsAndTrailingDataAreRejected() {
        val source = np3("standard")
        for (length in 0 until source.size) {
            try {
                Np3Document.parse(source.copyOfRange(0, length))
                fail("Expected failure for length $length")
            } catch (_: Exception) {
            }
        }
        var duplicate = source.copyOfRange(0, source.size - 4)
        duplicate = duplicate + source.slice(4, 12)
        duplicate = duplicate + byteArrayOf(0, 0, 0, 0)
        try {
            Np3Document.parse(duplicate)
            fail("Expected failure")
        } catch (_: Exception) {
        }
        val trailing = source + byteArrayOf(0)
        try {
            Np3Document.parse(trailing)
            fail("Expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun testNP3RejectsUnsupportedFormatsParametersAndCurves() {
        val original = Np3Document.parse(np3("standard")).records.toMutableMap()
        for ((tag, value) in listOf<Pair<Long, ByteArray>>(
            0x100L to "0310".toByteArray(Charsets.US_ASCII),
            0x300L to byteArrayOf(3, 194.toByte()),
            2L to ByteArray(578),
            0x600L to byteArrayOf(0, 4),
            0x800L to byteArrayOf(1, 4),
            0x900L to byteArrayOf(255.toByte(), 4),
            0x1600L to byteArrayOf(149.toByte(), 4),
            0x1400L to byteArrayOf(255.toByte(), 1)
        )) {
            val records = HashMap(original)
            records[tag] = value
            try {
                StandardNp3Recipe.parse(encodedNP3(records))
                fail("Expected failure for tag 0x${tag.toString(16)}")
            } catch (_: Exception) {
            }
        }
        val records = HashMap(original)
        records[0x200L] = ByteArray(20) { 65 }
        try {
            StandardNp3Recipe.parse(encodedNP3(records))
            fail("Expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun testC9NameChangePreservesAllOtherBytesAndRejectsUnknownLayouts() {
        val original = bin("standard")
        val payload = C9RecipePayload(original)
        assertEquals("STANDARD-02", payload.name)
        val changed = payload.renamedForTest()
        assertEquals("Z30LAB-TEST", C9RecipePayload(changed).name)
        assertArrayEquals(original.slice(0, 2), changed.slice(0, 2))
        assertArrayEquals(original.slice(22, 14), changed.slice(22, 14))
        for (index in listOf(0, 22, 24, 35)) {
            val invalid = original.clone()
            invalid[index] = (invalid[index].toInt() xor 1).toByte()
            try {
                C9RecipePayload(invalid)
                fail("Expected failure at $index")
            } catch (_: Exception) {
            }
        }
        try {
            C9RecipePayload(original.copyOfRange(0, original.size - 1))
            fail("Expected failure")
        } catch (_: Exception) {
        }
        val unterminated = original.clone()
        unterminated.writeInto(2, ByteArray(20) { 65 })
        try {
            C9RecipePayload(unterminated)
            fail("Expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun testRecoveryRecordSurvivesStoreRecreation() {
        val folder = File.createTempFile("z30", null).let { it.delete(); it.mkdir(); it }
        try {
            val url = File(folder, "Recovery/selection.json")
            val record = SelectionRecovery(cameraIdentity = "fixture-identity", original = 8, temporary = 1)
            FileSelectionRecoveryStore(url).save(record)
            val reopened = FileSelectionRecoveryStore(url)
            assertEquals(record, reopened.load())
            reopened.clear()
            assertNull(reopened.load())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun testDeviceInfoParsing() {
        val info = DeviceInfo.parse(bin("deviceinfo"))
        assertEquals("Z 30", info.model)
        assertEquals("V1.20", info.firmware)
        assertTrue(info.operations.contains(0x90CD))
        assertTrue(info.operations.contains(0x90CC))
    }

    @Test
    fun testPropertyDescriptorParsing() {
        val desc = PictureControlDescriptor.parse(bin("photo-control-desc"))
        assertTrue(desc.writable)
        assertEquals(8, desc.current)
        assertEquals(37, desc.choices.size)
        assertEquals((201..209).toList(), desc.choices.takeLast(9))
    }

    @Test
    fun testTruncatedDataRejected() {
        val raw = bin("deviceinfo")
        for (count in listOf(0, 2, 20, raw.size - 1)) {
            try {
                DeviceInfo.parse(raw.copyOfRange(0, count))
                fail("Expected failure for length $count")
            } catch (_: Exception) {
            }
        }
        try {
            PictureControlDescriptor.parse(ByteArray(2))
            fail("Expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun testFragmentedAndCoalescedFrames() {
        val packets = listOf(PTPPacket(2L, ByteArray(53) { 4 }), PTPPacket(4L, ByteArray(0)))
        val wire = packets.fold(java.io.ByteArrayOutputStream()) { acc, p -> acc.write(p.encoded); acc }.toByteArray()
        val framer = PacketFramer()
        val received = mutableListOf<PTPPacket>()
        for (b in wire) received.addAll(framer.append(byteArrayOf(b)))
        assertEquals(listOf(2L, 4L), received.map { it.type })
        assertEquals(53, received.first().payload.size)
        val other = PacketFramer()
        assertEquals(2, other.append(wire).size)
    }

    @Test
    fun testInvalidFrameLengthRejected() {
        for (length in listOf(0L, 7L, Long.MAX_VALUE)) {
            val wire = java.io.ByteArrayOutputStream()
            put32(wire, length)
            put32(wire, 9L)
            val framer = PacketFramer()
            try {
                framer.append(wire.toByteArray())
                fail("Expected failure for length $length")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun testMultipartDataAndUnknownLength() {
        for (length in listOf(5L, -1L)) { // -1L = UInt64.max
            var t = PTPDataTransaction(12L)
            val start = java.io.ByteArrayOutputStream()
            put32(start, 12L)
            put64(start, length)
            assertNull(t.consume(PTPPacket(9L, start.toByteArray())))
            val a = java.io.ByteArrayOutputStream()
            put32(a, 12L)
            a.write(byteArrayOf(1, 2))
            val b = java.io.ByteArrayOutputStream()
            put32(b, 12L)
            b.write(byteArrayOf(3, 4, 5))
            assertNull(t.consume(PTPPacket(10L, a.toByteArray())))
            assertNull(t.consume(PTPPacket(12L, b.toByteArray())))
            val response = java.io.ByteArrayOutputStream()
            put16(response, 0x2001)
            put32(response, 12L)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), t.consume(PTPPacket(7L, response.toByteArray())))
        }
    }

    @Test
    fun testTransactionMismatchAndMissingEndRejected() {
        var t = PTPDataTransaction(5L)
        val response = java.io.ByteArrayOutputStream()
        put16(response, 0x2001)
        put32(response, 6L)
        try {
            t.consume(PTPPacket(7L, response.toByteArray()))
            fail("Expected failure")
        } catch (_: Exception) {
        }
        t = PTPDataTransaction(5L)
        val start = java.io.ByteArrayOutputStream()
        put32(start, 5L)
        put64(start, 2L)
        t.consume(PTPPacket(9L, start.toByteArray()))
        val finish = java.io.ByteArrayOutputStream()
        put16(finish, 0x2001)
        put32(finish, 5L)
        try {
            t.consume(PTPPacket(7L, finish.toByteArray()))
            fail("Expected failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun testDeclaredDataLengthEnforced() {
        val t = PTPDataTransaction(7L)
        val start = java.io.ByteArrayOutputStream()
        put32(start, 7L)
        put64(start, 3L)
        t.consume(PTPPacket(9L, start.toByteArray()))
        val end = java.io.ByteArrayOutputStream()
        put32(end, 7L)
        end.write(1)
        try {
            t.consume(PTPPacket(12L, end.toByteArray()))
            fail("Expected failure")
        } catch (_: Exception) {
        }
    }
}
