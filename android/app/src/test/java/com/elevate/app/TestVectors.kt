package com.elevate.app

/**
 * Hand-written unit-test vectors. They exercise parsing and time arithmetic only; they are not
 * observations of any video and never ship in the app (real labels live in src/main/assets).
 */
object TestVectors {
    const val SHA_A = "534e6ff4ca061a25dbd6a03ce91ef5f3adf5e4e9a81a481b80edbc4390368377"
    const val SHA_B = "1111111111111111111111111111111111111111111111111111111111111111"

    fun doc(
        frames: String,
        range: String = "[40000000, 40100000]",
        count: Int = 6,
        sha: String = "\"$SHA_A\"",
        schema: String = "elevate.trail_labels.v1",
        width: Int = 1920,
        height: Int = 1080,
    ) = """
        {"schema": "$schema",
         "clip": {"id": "test_clip", "width": $width, "height": $height, "rotation": 0, "accepted_file_sha256": [$sha]},
         "provenance": {"state": "user", "engine_version": "hand-labelled", "labelled_by": "@test"},
         "range_us": $range, "frame_count_in_range": $count,
         "frames": [$frames]}
    """.trimIndent()

    /** 20 ms frame spacing, one not-visible frame (40.06 s) and one unlabelled frame (40.08 s). */
    val FRAMES_50FPS = """
        {"t_us": 40000000, "ball": [100.0, 200.0]},
        {"t_us": 40020000, "ball": [110.0, 190.0]},
        {"t_us": 40040000, "ball": [120.0, 180.0]},
        {"t_us": 40060000, "ball": null},
        {"t_us": 40100000, "ball": [150.0, 160.0]}
    """.trimIndent()
}
