package io.music_assistant.client

import androidx.compose.ui.window.ComposeUIViewController
import io.music_assistant.client.di.initKoin
import io.music_assistant.client.di.iosModule
import io.music_assistant.client.nowplaying.NowPlayingManager
import io.music_assistant.client.ui.compose.App
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private object NowPlayingBootstrapper : KoinComponent {
    val nowPlaying: NowPlayingManager by inject()
}

fun MainViewController() = ComposeUIViewController(
    configure = {
        initKoin(iosModule())
        // Touch the singleton so it initializes and starts observing state
        NowPlayingBootstrapper.nowPlaying
    }
) { App() }