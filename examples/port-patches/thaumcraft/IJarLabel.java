// SPDX-License-Identifier: MIT
package thaumcraft.api.aspects;

import org.jetbrains.annotations.Nullable;

/** Read-only label of a fillable or void jar, independently of its stored essentia and suction. */
public interface IJarLabel {
    /** The labelled aspect, or null if the jar has no label (including when it retains a suction type). */
    @Nullable Aspect getFilter();
}
