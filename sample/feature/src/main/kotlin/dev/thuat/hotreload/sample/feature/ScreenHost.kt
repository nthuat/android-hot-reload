package dev.thuat.hotreload.sample.feature

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

// Exercises ComposeInvalidator.keysForClass's second candidate: a composable that is a MEMBER
// of a class, not a top-level file-facade function like Greeting() in this same module. Editing
// Body()'s body redefines the ScreenHost class itself — its KeyMeta sibling lives on this
// file's facade, `ScreenHostKt$KeyMeta`, not `ScreenHost$KeyMeta` (which doesn't exist). Before
// the F7 fix this silently fell through to tier-2 (whole-composition rebuild, remember state
// lost) on every edit. See ComposeInvalidator.keysForClass's doc comment.
class ScreenHost {
    @Composable
    fun Body(label: String) {
        Text(text = "Member composable: $label")
    }
}
