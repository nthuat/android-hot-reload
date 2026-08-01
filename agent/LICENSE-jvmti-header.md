# Vendored header: `include/jvmti.h`

`agent/src/main/cpp/include/jvmti.h` is vendored from the Android Open Source
Project (AOSP) ART runtime, tag `android-14.0.0_r1`:

https://android.googlesource.com/platform/art/+/refs/tags/android-14.0.0_r1/openjdkjvmti/include/jvmti.h

That file is itself sourced from OpenJDK's `jvmti.h`, which ART's
`openjdkjvmti` implementation targets. It is licensed under the **GNU
General Public License v2.0 with the Classpath Exception** (the standard
OpenJDK license), per the copyright header at the top of the file.

This header is used only at build time to compile the JVMTI agent
(`libhotreload_agent.so`) against the JVMTI API surface implemented by
ART's `openjdkjvmti`. It declares types and function signatures only — no
GPL-licensed implementation code from this file is linked into or shipped
as part of `libhotreload_agent.so`.
