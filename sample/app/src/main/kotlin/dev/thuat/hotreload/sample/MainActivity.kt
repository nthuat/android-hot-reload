package dev.thuat.hotreload.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.thuat.hotreload.sample.feature.Greeting
import dev.thuat.hotreload.sample.feature.ScreenHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // `remember`, not hoisted: MainActivity is a different file/class than the ones
            // a hot reload edits (Greeting.kt / ScreenHost.kt), so tier-1 group-key
            // invalidation (Task 12) preserves this across a reload — it only re-executes the
            // composable whose own file was redefined. See README's supported/unsupported
            // table for the tier breakdown.
            var count by remember { mutableIntStateOf(0) }
            // Rendered by MainActivity but declared as a class MEMBER in ScreenHost.kt — see
            // that file's doc comment. Exercises ComposeInvalidator.keysForClass's member-
            // composable candidate (F7).
            val screenHost = remember { ScreenHost() }
            Column(modifier = Modifier.padding(32.dp)) {
                Greeting(name = "World")
                screenHost.Body(label = "static")
                Button(onClick = { count++ }) { Text("Count: $count") }
            }
        }
    }
}
