package com.lanraragi.reader.reading

/**
 * A whole-tank reading session to open: the published [seed] and the global
 * page to start on (-1 = let the provider restore the saved tank progress).
 * The UI turns it into the reader intent (GalleryOpenHelper.buildTankReadIntent).
 */
data class TankLaunch(val seed: TankSessionSeed, val startGlobalPage: Int)
