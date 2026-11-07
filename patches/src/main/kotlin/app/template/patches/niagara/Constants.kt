package app.template.patches.niagara

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal val NiagaraLauncherCompatible = Compatibility(
    name = "Niagara Launcher",
    packageName = "bitpit.launcher",
    targets = listOf(AppTarget(version = "1.16.34"))
)
