package dev.hotreload.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hotreload.sample.feature.Greeting

class MainActivity : ComponentActivity() {
    // Hoisted to the Activity instance (not `remember`/`rememberSaveable` inside the
    // composable) so it survives a hot reload: the reload path clears and rebuilds the
    // root Composition in place (androidx.compose.runtime.HotReloader.saveStateAndDispose
    // + loadStateAndCompose), which discards every remembered slot — rememberSaveable
    // included, since its restore path only replays state that was captured by a real
    // Activity save/restore round trip, which a live reload never triggers. State that
    // must outlive a reload has to live outside the Composition, same as it would need to
    // for a ViewModel-backed screen.
    private var count by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(modifier = Modifier.padding(32.dp)) {
                Greeting(name = "World")
                Button(onClick = { count++ }) { Text("Count: $count") }
            }
        }
    }
}
