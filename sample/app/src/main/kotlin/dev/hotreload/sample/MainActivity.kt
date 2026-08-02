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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hotreload.sample.feature.Greeting

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // `remember`, not hoisted: MainActivity is a different file/class than the one
            // a hot reload edits (Greeting.kt), so tier-1 group-key invalidation (Task 12)
            // preserves this across a reload — it only re-executes the composable whose own
            // file was redefined. See README's supported/unsupported table for the tier
            // breakdown.
            var count by remember { mutableIntStateOf(0) }
            Column(modifier = Modifier.padding(32.dp)) {
                Greeting(name = "World")
                Button(onClick = { count++ }) { Text("Count: $count") }
            }
        }
    }
}
