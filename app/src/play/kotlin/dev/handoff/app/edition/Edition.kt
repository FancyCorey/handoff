package dev.handoff.app.edition

/** What makes this build the Google Play edition. */
object Edition {
    const val NAME = "Google Play edition"

    /** One line for setup. The ad SDK collects data of its own, so this edition can't claim "no tracking". */
    const val PRIVACY_SUMMARY = "No account and no cloud. Your devices talk only to each other, on your network. " +
        "The one ad comes from Google; see Settings > Privacy."

    /**
     * Licenses of components only this edition contains, shown after the shared notices (which
     * already hold the full Apache License 2.0 text, section 5). Keep in step with
     * THIRD_PARTY_NOTICES.md, "Google Play edition".
     */
    val EXTRA_NOTICES: String = """


        GOOGLE PLAY EDITION

        This edition also contains Google's advertising and consent libraries and the components they bring with them. The GitHub edition contains none of these.

        Not open source, used under the Android Software Development Kit License (https://developer.android.com/studio/terms):
        - Google Mobile Ads SDK (com.google.android.libraries.ads.mobile.sdk:ads-mobile-sdk 1.5.0)
        - Google User Messaging Platform (com.google.android.ump:user-messaging-platform 4.0.0)
        - Google Play services libraries: ads-identifier, appset, base, basement, cronet, tasks; com.google.android.play:hsdp

        Apache License 2.0 (full text in section 5):
        - AndroidX Browser, WebKit and WorkManager, The Android Open Source Project
        - Guava and failureaccess, The Guava Authors
        - Gson, Google Inc.
        - Tink, Google LLC
        - Error Prone annotations, The Error Prone Authors
        - J2ObjC annotations, Google Inc.
        - JSR 305 annotations (FindBugs)
        - OkHttp, Square, Inc.
        - kotlinx-coroutines-guava, JetBrains s.r.o. and Kotlin Programming Language contributors

        BSD 3-Clause License:
        - Cronet, Copyright 2015 The Chromium Authors
        - Protocol Buffers (protobuf-javalite), Copyright 2008 Google Inc.

        Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
        1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
        2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
        3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

        THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

        MIT License:
        - Checker Framework qualifiers (checker-qual), Copyright 2004-present by the Checker Framework developers

        Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

        The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

        THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
    """.trimIndent()
}
