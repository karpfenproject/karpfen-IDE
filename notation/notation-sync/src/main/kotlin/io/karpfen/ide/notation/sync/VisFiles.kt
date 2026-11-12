/**
 * Copyright 2026 Karl Kegel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.karpfen.ide.notation.sync

import io.karpfen.ide.notation.diagram.layout.VisLayout
import io.karpfen.ide.notation.diagram.layout.VisLayoutJson
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The companion files that hold the layout of a diagram made by hand. The companion of a model file sits next to it
 * and has its name with [SUFFIX] appended, so `lamp.kstates` has `lamp.kstates.vis`.
 *
 * A companion file only holds where things are drawn. The model file stays the source of truth. A companion file
 * that is missing, unreadable or no JSON simply means there is no layout by hand.
 *
 * The calls block. Callers run them off the UI thread.
 */
public object VisFiles {

    /** What is appended to the name of a model file to name its companion file. */
    public const val SUFFIX: String = ".vis"

    /** The companion file of the model file [model]. */
    public fun pathFor(model: Path): Path = model.resolveSibling(model.fileName.toString() + SUFFIX)

    /** The layout in the companion file [file], or null if there is none that can be read. */
    public fun read(file: Path): VisLayout? =
        try {
            if (Files.isRegularFile(file)) VisLayoutJson.decode(Files.readString(file)) else null
        } catch (e: IOException) {
            null
        }

    /** Writes [layout] to the companion file [file]. Returns false if the file could not be written. */
    public fun write(file: Path, layout: VisLayout): Boolean =
        try {
            Files.writeString(file, VisLayoutJson.encode(layout))
            true
        } catch (e: IOException) {
            false
        }
}
