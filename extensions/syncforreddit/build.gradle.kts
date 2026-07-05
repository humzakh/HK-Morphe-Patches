extension {
    name = "extensions/syncforreddit.mpe"
}

android {
    namespace = "app.morphe.extension.syncforreddit"
}

dependencies {
    compileOnly(project(":extensions:syncforreddit:stubs"))
}
