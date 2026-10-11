package com.elevate.app.ui.components

/** One plain-language explanation: a short title and one or two sentences (what it does, why it helps). */
data class Explanation(val title: String, val text: String)

/**
 * Every hold-to-explain text in the app, in one place so any screen can reuse it:
 * `Modifier.explainOnHold(Explanations.CourtCorners)`. Plain words, no em-dashes, under 220 characters.
 */
object Explanations {
    val CourtCorners = Explanation(
        "Court corners",
        "Tap the 4 corners so the app can measure real distances on the floor. It makes the speeds and landing spots true to the court.",
    )
    val OffFrameCorner = Explanation(
        "Corner out of view",
        "If a corner is cut off by the camera, mark it as off-frame. The app works it out from the lines you can see, so it is an estimate.",
    )
    val ThisIsMe = Explanation(
        "This is me",
        "Tap yourself on the video. The coach report then talks about your shots instead of both players' shots.",
    )
    val Analyse = Explanation(
        "Analyse",
        "Sends the match to the Elevate engine, which finds the shuttle and your hits. You can keep watching while it works.",
    )
    val LiveTrail = Explanation(
        "Live trail",
        "The line the shuttle flew along, drawn over your video as it plays. It helps you see where each shot went.",
    )
    val Hit = Explanation(
        "Hit",
        "The moment a racket met the shuttle. Tap one to jump the video to that shot.",
    )
    val Landing = Explanation(
        "Where it landed",
        "The spot on the court where the shuttle came down. It shows whether your shots reach the corners or stay short.",
    )
    val Rally = Explanation(
        "Rally",
        "One point from serve to the shuttle hitting the floor. Rallies let you review a point at a time.",
    )
    val Estimate = Explanation(
        "Estimate (dashed)",
        "A dashed line or ring means the app is guessing between two seen moments. Solid means it saw the shuttle there.",
    )
    val CoachReport = Explanation(
        "Coach report",
        "A short summary of what went well and what to work on, written from your own match. Tap a point to see the moment.",
    )
    val ReadLab = Explanation(
        "Train your read",
        "Pause on a real moment and guess where the shuttle goes next. It trains you to read the opponent earlier.",
    )
    val Relaxed = Explanation(
        "Relaxed",
        "Gives you 3 seconds to answer instead of 1.5, so you can read the shot more calmly.",
    )
    val ZonePicker = Explanation(
        "Landing zones",
        "The half court is split into 9 zones. Tap the one where you think the shuttle will come down.",
    )
    val Reflex = Explanation(
        "Reflex challenge",
        "A quick game: tap as fast as you can when the shuttle cue appears. It scores your reaction time.",
    )
    val ShuttleCue = Explanation(
        "Shuttle cue",
        "The early clue, such as the racket angle, that hints where the shuttle is heading. Spotting it sooner gives you time to move.",
    )
    val Listen = Explanation(
        "Listen",
        "Reads the coach report aloud so you can keep your eyes on the court. Tap again to stop.",
    )
    val Share = Explanation(
        "Share",
        "Makes a picture card of your best moment that you can send to friends or your coach.",
    )
    val CourtQuality = Explanation(
        "Court quality",
        "How well the corners fit the lines in your video. Higher means distances and landing spots are more trustworthy.",
    )
    val InPieces = Explanation(
        "Analysing in pieces",
        "Long matches are cut into 6-second pieces and analysed side by side, so results start appearing early.",
    )
    val UploadResumes = Explanation(
        "Upload resumes",
        "If the connection drops, the upload carries on from where it stopped. You do not have to start again.",
    )

    // Extras used by the setup and replay screens.
    val Sport = Explanation("Sport", "Picks the court size and rules the app measures against.")
    val Format = Explanation("Format", "Singles uses the narrow court, doubles the wide one. Choose what was played.")
    val AddMatch = Explanation("Add a match", "Pick a video from your gallery. It plays on this phone, and nothing is sent until you choose Analyse.")
    val Cloud = Explanation("Cloud status", "Shows whether the Elevate engine can be reached right now. Analysis needs it to be online.")
    val Ball = Explanation("Ball", "Tap the shuttle once so the app knows what to follow in free play.")
    val Magnifier = Explanation(
        "Magnifier",
        "While you drag a corner, a zoomed view shows the exact spot under your finger so you can place it precisely.",
    )
    val TrailToggle = Explanation("Trail", "Shows or hides the shuttle line over the video.")
    val CourtToggle = Explanation("Court lines", "Shows or hides the court outline the app fitted to your corners.")
    val Speed = Explanation("Speed", "Slows the video down so fast shots are easier to follow.")
    val Analysing = Explanation("Analysing", "The engine is working through your video. Each step ticks off when it finishes.")
    val StageVideo = Explanation("Video", "Your video is checked and prepared on this phone.")
    val StageUpload = Explanation("Upload", "The video is sent securely to the engine. It can resume if the connection drops.")
    val StageAnalyse = Explanation("Analysis", "The engine tracks the shuttle and finds each hit, in pieces side by side.")

    // Your game (player profile).
    val YourGame = Explanation("Your game", "A summary of every match analysed on this phone: totals, where shots land, style and training. It is built only from saved results.")
    val StyleDeepHitter = Explanation("Deep hitter", "At least 55% of landings fell in the back row of the court, from at least 15 landings. The evidence shows the exact count.")
    val StyleNetPlayer = Explanation("Net player", "At least 40% of landings fell in the front row near the net, from at least 15 landings. The evidence shows the exact count.")
    val StyleRallyBuilder = Explanation("Rally builder", "The typical (median) rally has 7 or more hits, from at least 4 rally candidates across your matches.")
    val StyleShortRallies = Explanation("Short rallies", "The typical (median) rally has 3 hits or fewer, from at least 4 rally candidates across your matches.")
    val StyleFastExchanges = Explanation("Fast exchanges", "The typical time between hits inside a rally is under 1.0 second, from at least 6 gaps across your matches.")
    val PlacementHeat = Explanation("Landing map", "Each box is the share of landings in that part of the court, seen from the hitter. Counts are shown as n of N landings.")
    val Trends = Explanation("Trends", "One value per match, oldest to newest. A line appears once at least 2 matches have that value.")
    val TrainingStats = Explanation("Training", "Your best Reflex score per match and your ReadLab exact reads. Saved on this phone only.")
    val FocusAreas = Explanation("Strength and focus", "A pattern is listed only when it shows up in at least 2 matches with enough data. It says how many.")

    /** Every entry, for the tests and for any help screen. */
    val all: List<Explanation> by lazy {
        listOf(
            CourtCorners, OffFrameCorner, ThisIsMe, Analyse, LiveTrail, Hit, Landing, Rally, Estimate, CoachReport,
            ReadLab, Relaxed, ZonePicker, Reflex, ShuttleCue, Listen, Share, CourtQuality, InPieces, UploadResumes,
            Sport, Format, AddMatch, Cloud, Ball, Magnifier, TrailToggle, CourtToggle, Speed, Analysing, StageVideo, StageUpload, StageAnalyse,
            YourGame, StyleDeepHitter, StyleNetPlayer, StyleRallyBuilder, StyleShortRallies, StyleFastExchanges,
            PlacementHeat, Trends, TrainingStats, FocusAreas,
        )
    }
}
