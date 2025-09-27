package io.music_assistant.client.di

import io.music_assistant.client.player.PlatformContext
import io.music_assistant.client.nowplaying.NowPlayingManager
import org.koin.dsl.module

fun iosModule() = module {
    single { PlatformContext() }
    // Eager singleton to start observing and wiring Control Center / Now Playing
    single { NowPlayingManager(get()) }
}