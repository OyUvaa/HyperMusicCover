package com.os4.musiccover

import org.junit.Assert.assertTrue
import org.junit.Test

class LyricParseSpeakerTest {

    @Test
    fun duetSpeakerPrefixShouldCarryForward() {
        val lines = listOf(
    LyricLine("男：第一句", null, 1000, 3000, false, null, null, null),
    LyricLine("第二句", null, 3000, 5000, false, null, null, null),
    LyricLine("男：第三句", null, 5000, 7000, false, null, null, null),
    LyricLine("女：第四句", null, 7000, 9000, false, null, null, null),
    LyricLine("第五句", null, 9000, 11000, false, null, null, null),
    LyricLine("女：第六句", null, 11000, 13000, false, null, null, null)
)
        val result = LyricParse.speakers(lines)

        assertTrue(!result[0].opposite)
        assertTrue(!result[1].opposite)
        assertTrue(!result[2].opposite)

        assertTrue(result[3].opposite)
        assertTrue(result[4].opposite)
        assertTrue(result[5].opposite)
    }
}