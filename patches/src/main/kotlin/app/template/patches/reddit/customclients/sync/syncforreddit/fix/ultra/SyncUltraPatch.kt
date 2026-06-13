package app.template.patches.reddit.customclients.sync.syncforreddit.fix.ultra

import app.template.patches.reddit.customclients.sync.syncforreddit.api.SyncForRedditCompatible

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

val syncUltraPatch = bytecodePatch(
    name = "Unlock Sync Ultra",
    description = "Unlocks Sync Ultra."
) {
    compatibleWith(*SyncForRedditCompatible)
    
    execute {
        UltraHelperFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """
        )

        SyncUltraLifetimeFingerprint.method.addInstructions(
            0,
            """
                const-string v0, "Sync Ultra Lifetime"
                return-object v0
            """
        )
    }
}