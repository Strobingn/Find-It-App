package com.example.data.mosaic

/**
 * Pure resume and pause decisions for a saved mosaic project.
 *
 * These rules used to live inline in the tile-picker composable, where no test could reach them.
 * They decide whether reopening a project continues unfinished downloads or opens the finished
 * mosaic directly, whether a saved row still has work to resume, and what the user is told when a
 * transfer is paused part-way through.
 */
object MosaicProjectResume {

    /**
     * The state a project takes when the user starts or resumes it. A project that was already
     * fully downloaded reopens straight into the finished mosaic; anything else continues as a
     * download so the missing members are fetched first.
     */
    fun stateWhenStarted(project: MosaicProject, missingSourceCount: Int): MosaicProjectState =
        if (missingSourceCount == 0 && project.state == MosaicProjectState.READY) {
            MosaicProjectState.READY
        } else {
            MosaicProjectState.DOWNLOADING
        }

    /** Recovery note saved when a project download is paused or abandoned mid-transfer. */
    fun pausedMessage(readySourceCount: Int, totalSourceCount: Int): String =
        "Download paused. $readySourceCount of $totalSourceCount source files are ready."

    /**
     * Whether a saved project still has work to resume. A READY project whose source files have
     * all survived on disk only reopens; anything else must resume downloading first.
     */
    fun canResume(project: MosaicProject, availableSourceCount: Int): Boolean =
        project.state != MosaicProjectState.READY || availableSourceCount != project.tiles.size
}
